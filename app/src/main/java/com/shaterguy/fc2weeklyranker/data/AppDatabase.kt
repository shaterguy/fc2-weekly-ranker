package com.shaterguy.fc2weeklyranker.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object DownloadStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val STOPPED = "STOPPED"
    const val FINALIZING = "FINALIZING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}

internal object DownloadQueuePolicy {
    fun enqueueOrder(previousStatus: String?, previousOrder: Long, currentMax: Long?): Long =
        if (previousStatus == DownloadStatus.PAUSED && previousOrder > 0L) previousOrder
        else AppDatabase.nextEnqueueOrder(currentMax)

    fun retryCount(previousStatus: String?, previousRetryCount: Int): Int =
        if (previousStatus == DownloadStatus.PAUSED) previousRetryCount else 0

    fun restartsFromBeginning(previousStatus: String?): Boolean =
        previousStatus == DownloadStatus.STOPPED || previousStatus == DownloadStatus.COMPLETED
}

@Entity(tableName = "posts", indices = [Index("snapshotKey"), Index("postedAtEpochMillis")])
data class PostEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val postedAtEpochMillis: Long,
    val recommendationCount: Int,
    val dailyRate: Double,
    val snapshotKey: String,
    val fetchedAtEpochMillis: Long,
)

data class KnownPostDate(
    val id: String,
    val postedAtEpochMillis: Long,
)

@Entity(
    tableName = "favorites",
    foreignKeys = [ForeignKey(entity = PostEntity::class, parentColumns = ["id"], childColumns = ["postId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("postId")],
)
data class FavoriteEntity(@PrimaryKey val postId: String, val createdAtEpochMillis: Long)

@Entity(
    tableName = "videos",
    foreignKeys = [ForeignKey(entity = PostEntity::class, parentColumns = ["id"], childColumns = ["postId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("postId")],
)
data class VideoEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val url: String,
    val referer: String,
    val userAgent: String,
    val sourceKind: String,
    val ordinal: Int,
    val discoveredAtEpochMillis: Long,
)

@Entity(
    tableName = "downloads",
    foreignKeys = [ForeignKey(entity = VideoEntity::class, parentColumns = ["id"], childColumns = ["videoId"], onDelete = ForeignKey.CASCADE)],
)
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val status: String,
    val contentUri: String?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val errorCode: String?,
    val updatedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "0") val enqueueOrder: Long,
    @ColumnInfo(defaultValue = "0") val retryCount: Int,
)

data class DownloadListItem(
    val videoId: String,
    val status: String,
    val contentUri: String?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val errorCode: String?,
    val updatedAtEpochMillis: Long,
    val videoUrl: String,
    val videoOrdinal: Int,
    val postId: String,
    val postUrl: String,
)

@Dao
interface PostDao {
    @Query("SELECT * FROM posts WHERE snapshotKey = :snapshotKey ORDER BY dailyRate DESC, recommendationCount DESC, postedAtEpochMillis DESC, id DESC")
    fun postsForSnapshot(snapshotKey: String): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE postedAtEpochMillis >= :startInclusiveEpochMillis AND postedAtEpochMillis <= :upperInclusiveEpochMillis")
    fun postsForWindow(startInclusiveEpochMillis: Long, upperInclusiveEpochMillis: Long): Flow<List<PostEntity>>

    @Query("SELECT id, postedAtEpochMillis FROM posts")
    suspend fun knownPostDates(): List<KnownPostDate>

    @Query("SELECT COUNT(*) FROM posts WHERE snapshotKey = :snapshotKey")
    suspend fun snapshotCount(snapshotKey: String): Int

    @Query("SELECT * FROM posts WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PostEntity?

    @Query("SELECT * FROM posts WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<PostEntity?>

    @Query(
        """
        SELECT candidate.*
        FROM posts candidate
        INNER JOIN posts current_post ON current_post.id = :postId
        WHERE (candidate.postedAtEpochMillis < current_post.postedAtEpochMillis)
           OR (candidate.postedAtEpochMillis = current_post.postedAtEpochMillis AND candidate.id < current_post.id)
        ORDER BY candidate.postedAtEpochMillis DESC, candidate.id DESC
        LIMIT 1
        """,
    )
    fun observePrevious(postId: String): Flow<PostEntity?>

    @Query(
        """
        SELECT candidate.*
        FROM posts candidate
        INNER JOIN posts current_post ON current_post.id = :postId
        WHERE (candidate.postedAtEpochMillis > current_post.postedAtEpochMillis)
           OR (candidate.postedAtEpochMillis = current_post.postedAtEpochMillis AND candidate.id > current_post.id)
        ORDER BY candidate.postedAtEpochMillis ASC, candidate.id ASC
        LIMIT 1
        """,
    )
    fun observeNext(postId: String): Flow<PostEntity?>

    @Query("SELECT p.* FROM posts p INNER JOIN favorites f ON p.id = f.postId ORDER BY f.createdAtEpochMillis DESC")
    fun favorites(): Flow<List<PostEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE postId = :postId)")
    suspend fun isFavorite(postId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE postId = :postId)")
    fun observeFavorite(postId: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(posts: List<PostEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE postId = :postId")
    suspend fun removeFavorite(postId: String)
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos WHERE postId = :postId ORDER BY ordinal ASC, discoveredAtEpochMillis ASC")
    fun forPost(postId: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE postId = :postId ORDER BY ordinal ASC, discoveredAtEpochMillis ASC")
    suspend fun currentForPost(postId: String): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): VideoEntity?

    @Upsert
    suspend fun upsert(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE postId = :postId AND sourceKind = 'IFRAME'")
    suspend fun deleteResolversForPost(postId: String)

    @Transaction
    suspend fun reconcileForPost(postId: String, videos: List<VideoEntity>) {
        deleteResolversForPost(postId)
        if (videos.isNotEmpty()) upsert(videos)
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE videoId = :videoId LIMIT 1")
    fun observe(videoId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId LIMIT 1")
    suspend fun byVideoId(videoId: String): DownloadEntity?

    @Query("SELECT MAX(enqueueOrder) FROM downloads")
    suspend fun maxEnqueueOrder(): Long?

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING', 'FINALIZING') ORDER BY enqueueOrder ASC, videoId ASC")
    suspend fun currentSchedulableDownloads(): List<DownloadEntity>

    @Query(
        """
        SELECT d.videoId AS videoId, d.status AS status, d.contentUri AS contentUri,
               d.downloadedBytes AS downloadedBytes, d.totalBytes AS totalBytes,
               d.errorCode AS errorCode, d.updatedAtEpochMillis AS updatedAtEpochMillis,
               v.url AS videoUrl, v.ordinal AS videoOrdinal, v.postId AS postId, p.url AS postUrl
        FROM downloads d
        INNER JOIN videos v ON v.id = d.videoId
        INNER JOIN posts p ON p.id = v.postId
        WHERE d.status IN ('QUEUED', 'RUNNING', 'PAUSED', 'FINALIZING')
        ORDER BY d.enqueueOrder ASC, d.videoId ASC
        """,
    )
    fun activeDownloads(): Flow<List<DownloadListItem>>

    @Query(
        """
        SELECT d.videoId AS videoId, d.status AS status, d.contentUri AS contentUri,
               d.downloadedBytes AS downloadedBytes, d.totalBytes AS totalBytes,
               d.errorCode AS errorCode, d.updatedAtEpochMillis AS updatedAtEpochMillis,
               v.url AS videoUrl, v.ordinal AS videoOrdinal, v.postId AS postId, p.url AS postUrl
        FROM downloads d
        INNER JOIN videos v ON v.id = d.videoId
        INNER JOIN posts p ON p.id = v.postId
        WHERE d.status = 'COMPLETED'
        ORDER BY d.updatedAtEpochMillis DESC
        """,
    )
    fun completedDownloads(): Flow<List<DownloadListItem>>

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Transaction
    suspend fun prepareQueue(videoId: String, now: Long): DownloadEntity? {
        val previous = byVideoId(videoId)
        if (previous?.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.FINALIZING)) return null

        val restarting = DownloadQueuePolicy.restartsFromBeginning(previous?.status)
        val canFinalize = !restarting && previous?.let {
            it.contentUri != null && it.totalBytes != null && it.totalBytes > 0L && it.downloadedBytes >= it.totalBytes
        } == true
        val enqueueOrder = DownloadQueuePolicy.enqueueOrder(
            previousStatus = previous?.status,
            previousOrder = previous?.enqueueOrder ?: 0L,
            currentMax = maxEnqueueOrder(),
        )
        val state = DownloadEntity(
            videoId = videoId,
            status = if (canFinalize) DownloadStatus.FINALIZING else DownloadStatus.QUEUED,
            contentUri = if (restarting) null else previous?.contentUri,
            downloadedBytes = if (restarting) 0L else previous?.downloadedBytes ?: 0L,
            totalBytes = if (restarting) null else previous?.totalBytes,
            errorCode = null,
            updatedAtEpochMillis = now,
            enqueueOrder = enqueueOrder,
            retryCount = DownloadQueuePolicy.retryCount(previous?.status, previous?.retryCount ?: 0),
        )
        upsert(state)
        return state
    }

    @Query(
        """UPDATE downloads
        SET status = :toStatus, errorCode = :errorCode, updatedAtEpochMillis = :updatedAt
        WHERE videoId = :videoId AND status IN (:fromStatuses)""",
    )
    suspend fun transitionStatus(
        videoId: String,
        fromStatuses: List<String>,
        toStatus: String,
        errorCode: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """UPDATE downloads
        SET status = :toStatus, errorCode = :errorCode, retryCount = retryCount + 1,
            updatedAtEpochMillis = :updatedAt
        WHERE videoId = :videoId AND status = :fromStatus AND retryCount < :maxRetries""",
    )
    suspend fun recordRetryIfStatus(
        videoId: String,
        fromStatus: String,
        toStatus: String,
        errorCode: String,
        updatedAt: Long,
        maxRetries: Int,
    ): Int

    @Query(
        """UPDATE downloads
        SET contentUri = :contentUri, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes,
            errorCode = NULL, updatedAtEpochMillis = :updatedAt
        WHERE videoId = :videoId AND status = :requiredStatus""",
    )
    suspend fun updateProgressIfStatus(
        videoId: String,
        requiredStatus: String,
        contentUri: String?,
        downloadedBytes: Long,
        totalBytes: Long?,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM downloads WHERE videoId = :videoId AND status = 'COMPLETED'")
    suspend fun deleteCompletedHistory(videoId: String): Int
}

@Database(entities = [PostEntity::class, FavoriteEntity::class, VideoEntity::class, DownloadEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao
    abstract fun videoDao(): VideoDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN enqueueOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE downloads SET enqueueOrder = rowid WHERE enqueueOrder = 0")
            }
        }

        internal fun nextEnqueueOrder(currentMax: Long?): Long {
            val max = currentMax ?: 0L
            check(max < Long.MAX_VALUE) { "download enqueue order exhausted" }
            return max + 1L
        }
    }
}
