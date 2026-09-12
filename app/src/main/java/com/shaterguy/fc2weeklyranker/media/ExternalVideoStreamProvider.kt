package com.shaterguy.fc2weeklyranker.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.CookieManager
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom

internal data class ExternalVideoStreamHandle(
    val uri: Uri,
    val mimeType: String,
    val intentFlags: Int,
    private val sessionToken: String,
) : AutoCloseable {
    override fun close() {
        ExternalVideoStreamRegistry.release(sessionToken)
    }
}

internal object ExternalVideoStreamSessions {
    fun create(context: Context, video: VideoEntity): ExternalVideoStreamHandle =
        ExternalVideoStreamRegistry.create(context.applicationContext, video)
}

private object ExternalVideoStreamRegistry {
    private const val SESSION_PATH = "session"
    private const val ROOT_PATH = "root"
    private const val ROOT_RESOURCE_ID = "root"
    private const val AUTHORITY_SUFFIX = ".externalstream"
    private const val MAX_IDLE_SESSIONS = 12
    private const val SESSION_IDLE_TTL_MS = 60L * 60L * 1000L
    private const val SESSION_TOKEN_BYTES = 24
    private const val RESOURCE_TOKEN_BYTES = 12
    private const val PROXY_READ_CHUNK_BYTES = 64 * 1024
    private const val PROXY_MAX_CACHED_CHUNKS = 8

    private val random = SecureRandom()
    private val sessions = linkedMapOf<String, StreamSession>()

    data class Resolved(
        val session: StreamSession,
        val resource: RemoteResource,
    )

    @Synchronized
    fun create(context: Context, video: VideoEntity): ExternalVideoStreamHandle {
        val now = SystemClock.elapsedRealtime()
        cleanup(now)
        val token = newToken(SESSION_TOKEN_BYTES)
        val authority = context.packageName + AUTHORITY_SUFFIX
        val rootUri = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(SESSION_PATH)
            .appendPath(token)
            .appendPath(ROOT_PATH)
            .build()
        val session = StreamSession(
            token = token,
            rootUri = rootUri,
            video = video,
            resourceTokenFactory = { newToken(RESOURCE_TOKEN_BYTES) },
        )
        sessions[token] = session
        trimIdleSessions(now)
        return ExternalVideoStreamHandle(
            uri = rootUri,
            mimeType = inferExternalMediaKind(video.url).mimeType,
            intentFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            sessionToken = token,
        )
    }

    @Synchronized
    fun resolve(uri: Uri, expectedAuthority: String): Resolved? {
        if (uri.scheme != "content" || uri.authority != expectedAuthority) return null
        val segments = uri.pathSegments
        if (segments.size !in 3..4 || segments[0] != SESSION_PATH || segments[2] != ROOT_PATH) return null
        val sessionToken = segments[1]
        val session = sessions[sessionToken] ?: return null
        val now = SystemClock.elapsedRealtime()
        if (session.canExpire(now, SESSION_IDLE_TTL_MS)) {
            sessions.remove(sessionToken)
            session.close()
            return null
        }
        val resourceId = segments.getOrNull(3) ?: ROOT_RESOURCE_ID
        val resource = session.resource(resourceId) ?: return null
        session.touch()
        return Resolved(session, resource)
    }

    @Synchronized
    fun release(token: String) {
        sessions.remove(token)?.close()
    }

    @Synchronized
    private fun cleanup(now: Long) {
        val expired = sessions.entries
            .filter { it.value.canExpire(now, SESSION_IDLE_TTL_MS) }
            .map { it.key }
        expired.forEach { sessions.remove(it)?.close() }
    }

    @Synchronized
    private fun trimIdleSessions(now: Long) {
        while (sessions.size > MAX_IDLE_SESSIONS) {
            val candidate = sessions.entries.firstOrNull { it.value.canEvict(now) } ?: return
            sessions.remove(candidate.key)?.close()
        }
    }

    private fun newToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        val hex = "0123456789abcdef"
        return buildString(byteCount * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    class StreamSession(
        val token: String,
        private val rootUri: Uri,
        video: VideoEntity,
        private val resourceTokenFactory: () -> String,
    ) {
        private val requestContext = ExternalVideoRequestContext(
            referer = video.referer,
            userAgent = video.userAgent,
        )
        private val transport = ExternalHttpTransport(
            cookieProvider = { targetUrl ->
                CookieManager.getInstance().getCookie(targetUrl)?.takeIf(String::isNotBlank)
            },
        )
        private val resources = linkedMapOf<String, RemoteResource>()
        private val resourceIdsByUrl = linkedMapOf<String, String>()
        private var activeDescriptors = 0
        private var closed = false
        private var lastAccessMs = SystemClock.elapsedRealtime()

        init {
            resources[ROOT_RESOURCE_ID] = newResource(video.url)
            resourceIdsByUrl[video.url] = ROOT_RESOURCE_ID
        }

        @Synchronized
        fun resource(resourceId: String): RemoteResource? {
            if (closed) return null
            touchLocked()
            return resources[resourceId]
        }

        @Synchronized
        fun childUri(targetUrl: String): String {
            if (closed) throw IOException("External stream session is closed")
            touchLocked()
            val existing = resourceIdsByUrl[targetUrl]
            if (existing != null) return rootUri.buildUpon().appendPath(existing).build().toString()
            var resourceId = resourceTokenFactory()
            while (resources.containsKey(resourceId)) resourceId = resourceTokenFactory()
            resources[resourceId] = newResource(targetUrl)
            resourceIdsByUrl[targetUrl] = resourceId
            return rootUri.buildUpon().appendPath(resourceId).build().toString()
        }

        @Synchronized
        fun descriptorOpened() {
            if (closed) throw FileNotFoundException("External stream session is closed")
            activeDescriptors += 1
            touchLocked()
            ExternalVideoStreamKeepAliveState.descriptorOpened()
        }

        @Synchronized
        fun descriptorClosed() {
            if (activeDescriptors > 0) {
                activeDescriptors -= 1
                ExternalVideoStreamKeepAliveState.descriptorClosed()
            }
            touchLocked()
        }

        @Synchronized
        fun touch() {
            touchLocked()
        }

        @Synchronized
        fun canExpire(now: Long, idleTtlMs: Long): Boolean =
            !closed && activeDescriptors == 0 && now - lastAccessMs >= idleTtlMs

        @Synchronized
        fun canEvict(now: Long): Boolean =
            !closed && activeDescriptors == 0 && now >= lastAccessMs

        fun close() {
            val resourcesToRelease = synchronized(this) {
                if (closed) return
                closed = true
                val snapshot = resources.values.toList()
                resources.clear()
                resourceIdsByUrl.clear()
                snapshot
            }
            resourcesToRelease.forEach(RemoteResource::releaseTransientState)
        }

        private fun newResource(url: String): RemoteResource = RemoteResource(
            url = url,
            context = requestContext,
            transport = transport,
            mapChildUrl = ::childUri,
        )

        private fun touchLocked() {
            lastAccessMs = SystemClock.elapsedRealtime()
        }
    }

    class RemoteResource(
        private val url: String,
        private val context: ExternalVideoRequestContext,
        private val transport: ExternalHttpTransport,
        private val mapChildUrl: (String) -> String,
    ) {
        private var resolvedKind: ExternalMediaKind? = null
        private var seekable: SeekableExternalHttpResource? = null
        private var playlistBytes: ByteArray? = null

        @Synchronized
        fun mimeType(): String = runCatching { kind().mimeType }
            .getOrDefault(inferExternalMediaKind(url).mimeType)

        @Synchronized
        fun displayName(): String = when (kind()) {
            ExternalMediaKind.HLS -> "external-stream.m3u8"
            ExternalMediaKind.MP4 -> "external-stream.mp4"
            ExternalMediaKind.WEBM -> "external-stream.webm"
            ExternalMediaKind.MPEG_TS -> "external-stream.ts"
            ExternalMediaKind.VIDEO -> "external-stream.video"
            ExternalMediaKind.BINARY -> "external-stream.bin"
        }

        @Synchronized
        fun size(): Long = if (kind() == ExternalMediaKind.HLS) {
            playlist().size.toLong()
        } else {
            seekable().size()
        }

        @Synchronized
        fun read(offset: Long, size: Int): ByteArray {
            if (offset < 0L || size < 0) throw IOException("Invalid external stream read")
            if (size == 0) return ByteArray(0)
            return if (kind() == ExternalMediaKind.HLS) {
                val bytes = playlist()
                if (offset >= bytes.size) {
                    ByteArray(0)
                } else {
                    val start = offset.toInt()
                    val end = (start + size).coerceAtMost(bytes.size)
                    bytes.copyOfRange(start, end)
                }
            } else {
                seekable().read(offset, size)
            }
        }

        fun releaseTransientState() {
            val seekableToClose = synchronized(this) {
                val existing = seekable
                seekable = null
                playlistBytes = null
                existing
            }
            seekableToClose?.close()
        }

        @Synchronized
        private fun kind(): ExternalMediaKind {
            resolvedKind?.let { return it }
            val inferred = inferExternalMediaKind(url)
            if (inferred in setOf(
                    ExternalMediaKind.HLS,
                    ExternalMediaKind.MP4,
                    ExternalMediaKind.WEBM,
                    ExternalMediaKind.MPEG_TS,
                )
            ) {
                resolvedKind = inferred
                return inferred
            }
            val metadata = runCatching { transport.metadata(url, context) }.getOrNull()
            if (metadata != null) {
                val detected = inferExternalMediaKind(metadata.finalUrl, metadata.mimeType)
                resolvedKind = detected
                return detected
            }
            val playlistProbe = runCatching { transport.fetchSmallText(url, context, 256 * 1024) }.getOrNull()
            if (playlistProbe != null && playlistProbe.first.trimStart().startsWith("#EXTM3U")) {
                val rewritten = rewriteHlsPlaylist(playlistProbe.first, playlistProbe.second, mapChildUrl)
                    .toByteArray(Charsets.UTF_8)
                playlistBytes = rewritten
                resolvedKind = ExternalMediaKind.HLS
                return ExternalMediaKind.HLS
            }
            resolvedKind = inferred
            return inferred
        }

        @Synchronized
        private fun seekable(): SeekableExternalHttpResource = seekable ?: SeekableExternalHttpResource(
            url = url,
            context = context,
            transport = transport,
            chunkSize = PROXY_READ_CHUNK_BYTES,
            maxCachedChunks = PROXY_MAX_CACHED_CHUNKS,
        ).also { seekable = it }

        @Synchronized
        private fun playlist(): ByteArray {
            playlistBytes?.let { return it }
            val fetched = transport.fetchSmallText(url, context)
            val rewritten = rewriteHlsPlaylist(fetched.first, fetched.second, mapChildUrl)
                .toByteArray(Charsets.UTF_8)
            playlistBytes = rewritten
            resolvedKind = ExternalMediaKind.HLS
            return rewritten
        }
    }
}

class ExternalVideoStreamProvider : ContentProvider() {
    private lateinit var storageManager: StorageManager
    private lateinit var proxyHandler: Handler

    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        storageManager = providerContext.getSystemService(StorageManager::class.java)
        val proxyThread = HandlerThread("external-stream-proxy").apply { start() }
        proxyHandler = Handler(proxyThread.looper)
        return true
    }

    override fun getType(uri: Uri): String? = resolve(uri)?.resource?.mimeType()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val resolved = resolve(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        columns.forEach { column ->
            row.add(
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> runCatching { resolved.resource.displayName() }.getOrDefault("external-stream")
                    OpenableColumns.SIZE -> runCatching { resolved.resource.size() }.getOrNull()
                    else -> null
                },
            )
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("External stream provider is read-only")
        val resolved = resolve(uri) ?: throw FileNotFoundException("Unknown external stream")
        resolved.session.descriptorOpened()
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = ioToErrno("externalStreamSize") {
                ExternalVideoStreamKeepAliveState.streamActivity()
                resolved.resource.size()
            }

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = ioToErrno("externalStreamRead") {
                ExternalVideoStreamKeepAliveState.streamActivity()
                val bytes = resolved.resource.read(offset, size)
                bytes.copyInto(data, endIndex = bytes.size)
                bytes.size
            }

            override fun onRelease() {
                resolved.session.descriptorClosed()
            }
        }
        return try {
            storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                callback,
                proxyHandler,
            )
        } catch (throwable: Throwable) {
            resolved.session.descriptorClosed()
            if (throwable is FileNotFoundException) throw throwable
            throw FileNotFoundException("Unable to open external stream").apply { initCause(throwable) }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("External stream provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("External stream provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("External stream provider is read-only")

    private fun resolve(uri: Uri): ExternalVideoStreamRegistry.Resolved? {
        val providerContext = context ?: return null
        return ExternalVideoStreamRegistry.resolve(uri, providerContext.packageName + ".externalstream")
    }

    private inline fun <T> ioToErrno(operation: String, block: () -> T): T = try {
        block()
    } catch (_: FileNotFoundException) {
        throw ErrnoException(operation, OsConstants.ENOENT)
    } catch (_: IOException) {
        throw ErrnoException(operation, OsConstants.EIO)
    } catch (_: IllegalArgumentException) {
        throw ErrnoException(operation, OsConstants.EINVAL)
    } catch (_: IllegalStateException) {
        throw ErrnoException(operation, OsConstants.EIO)
    }
}
