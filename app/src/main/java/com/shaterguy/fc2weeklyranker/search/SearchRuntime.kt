package com.shaterguy.fc2weeklyranker.search

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.network.AvseeClient
import com.shaterguy.fc2weeklyranker.network.SearchPage
import com.shaterguy.fc2weeklyranker.network.isTransientNetworkError
import com.shaterguy.fc2weeklyranker.network.retryTransientGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class SearchRequest(
    val token: String,
    val query: String,
    val baseUrl: String,
) {
    fun toPersistableBundle(): PersistableBundle = PersistableBundle().apply {
        putString(KEY_TOKEN, token)
        putString(KEY_QUERY, query)
        putString(KEY_BASE_URL, baseUrl)
    }

    companion object {
        const val KEY_TOKEN = "search_token"
        const val KEY_QUERY = "search_query"
        const val KEY_BASE_URL = "search_base_url"

        fun from(bundle: PersistableBundle): SearchRequest? {
            val token = bundle.getString(KEY_TOKEN)?.takeIf(String::isNotBlank) ?: return null
            val query = bundle.getString(KEY_QUERY)?.takeIf(String::isNotBlank) ?: return null
            val baseUrl = bundle.getString(KEY_BASE_URL)?.takeIf(String::isNotBlank) ?: return null
            return SearchRequest(token, query, baseUrl)
        }
    }
}

internal data class SearchScheduleResult(
    val request: SearchRequest,
    val scheduled: Boolean,
)

internal enum class SearchSchedulerKind { WORK_MANAGER, UIDT }

internal object SearchRuntimePolicy {
    private const val MAX_TOTAL_PAGES = 500
    private val MAX_RUNTIME_NANOS = TimeUnit.MINUTES.toNanos(10)

    fun schedulerKind(sdkInt: Int): SearchSchedulerKind =
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) SearchSchedulerKind.UIDT else SearchSchedulerKind.WORK_MANAGER

    fun validateTotalPages(totalPages: Int) {
        check(totalPages <= MAX_TOTAL_PAGES) {
            "검색 범위가 너무 큽니다: ${totalPages}페이지. 검색어를 더 구체적으로 입력해 주세요."
        }
    }

    fun ensureWithinRuntime(startedAtNanos: Long, nowNanos: Long) {
        check(nowNanos - startedAtNanos < MAX_RUNTIME_NANOS) {
            "검색 시간이 10분을 초과했습니다. 검색어를 더 구체적으로 입력한 뒤 다시 시도해 주세요."
        }
    }
}

internal object SearchRecoveryPolicy {
    fun shouldInterrupt(status: String, schedulerActive: Boolean?): Boolean =
        status == SearchStatus.RUNNING && schedulerActive == false
}

internal class SearchScheduler(private val context: Context) {
    fun start(query: String, baseUrl: String): SearchScheduleResult {
        val request = SearchRequest(
            token = UUID.randomUUID().toString(),
            query = query.trim(),
            baseUrl = baseUrl,
        )
        require(request.query.isNotEmpty())
        cancelActive()
        val scheduled = when (SearchRuntimePolicy.schedulerKind(Build.VERSION.SDK_INT)) {
            SearchSchedulerKind.UIDT -> scheduleUidt(request)
            SearchSchedulerKind.WORK_MANAGER -> scheduleWorker(request)
        }
        return SearchScheduleResult(request, scheduled)
    }

    fun cancelActive() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { uidtScheduler().cancel(JOB_ID) }
        }
    }

    @SuppressLint("NewApi")
    fun cancel(token: String) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(workTag(token))
        workManager.cancelUniqueWork(WORK_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                val scheduler = uidtScheduler()
                if (scheduler.getPendingJob(JOB_ID)?.extras?.getString(SearchRequest.KEY_TOKEN) == token) {
                    scheduler.cancel(JOB_ID)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    suspend fun isActive(token: String): Boolean? = withContext(Dispatchers.IO) {
        when (SearchRuntimePolicy.schedulerKind(Build.VERSION.SDK_INT)) {
            SearchSchedulerKind.WORK_MANAGER -> runCatching {
                WorkManager.getInstance(context)
                    .getWorkInfosByTag(workTag(token))
                    .get()
                    .any { !it.state.isFinished }
            }.getOrNull()
            SearchSchedulerKind.UIDT -> runCatching {
                uidtScheduler().getPendingJob(JOB_ID)
                    ?.extras
                    ?.getString(SearchRequest.KEY_TOKEN) == token
            }.getOrNull()
        }
    }

    private fun scheduleWorker(request: SearchRequest): Boolean = runCatching {
        val work = OneTimeWorkRequestBuilder<SearchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    SearchRequest.KEY_TOKEN to request.token,
                    SearchRequest.KEY_QUERY to request.query,
                    SearchRequest.KEY_BASE_URL to request.baseUrl,
                ),
            )
            .addTag(workTag(request.token))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work)
        true
    }.getOrDefault(false)

    @SuppressLint("NewApi")
    private fun scheduleUidt(request: SearchRequest): Boolean = runCatching {
        val network = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, SearchJobService::class.java))
            .setExtras(request.toPersistableBundle())
            .setRequiredNetwork(network)
            .setUserInitiated(true)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        uidtScheduler().schedule(info) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)

    @SuppressLint("NewApi")
    private fun uidtScheduler(): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace(UIDT_NAMESPACE)

    companion object {
        private const val JOB_ID = 0x53454152
        private const val UIDT_NAMESPACE = "search"
        private const val WORK_NAME = "fc2-background-search"
        private const val WORK_TAG_PREFIX = "fc2-search-token:"

        internal fun workTag(token: String): String = "$WORK_TAG_PREFIX$token"
    }
}

internal object SearchNotifications {
    private const val CHANNEL_ID = "fc2_search"
    private const val NOTIFICATION_ID = 0x53454348

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FC2 검색", NotificationManager.IMPORTANCE_LOW).apply {
                description = "백그라운드 FC2 검색 진행 상태"
                setShowBadge(false)
            },
        )
    }

    fun notification(context: Context, query: String): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FC2 검색 중")
            .setContentText(query)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    fun foregroundInfo(context: Context, query: String): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_ID,
            notification(context, query),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    const val notificationId: Int = NOTIFICATION_ID
}

internal enum class SearchRunResult { COMPLETED, FAILED, STALE }

internal object SearchRunner {
    suspend fun run(request: SearchRequest, network: Network? = null): SearchRunResult {
        val dao = AppGraph.searchDatabase.searchDao()
        var session = dao.prepareSession(request)
        val client = BackgroundSearchClient(AppGraph.httpClient, AppGraph.sourceClient, network)
        val startedAtNanos = System.nanoTime()
        var page = session.nextPage.coerceAtLeast(1)
        var totalPages = session.totalPages.coerceAtLeast(0)

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                SearchRuntimePolicy.ensureWithinRuntime(startedAtNanos, System.nanoTime())
                val parsed = client.searchPage(request.baseUrl, request.query, page)
                SearchRuntimePolicy.validateTotalPages(parsed.totalPages)
                totalPages = maxOf(totalPages, parsed.totalPages, page)
                val stored = dao.storePage(
                    token = request.token,
                    page = page,
                    totalPages = totalPages,
                    posts = parsed.posts,
                    updatedAt = System.currentTimeMillis(),
                )
                if (!stored) return SearchRunResult.STALE
                if (page >= totalPages) {
                    dao.complete(request.token, System.currentTimeMillis())
                    return SearchRunResult.COMPLETED
                }
                page += 1
                session = dao.currentSession() ?: return SearchRunResult.STALE
                if (session.token != request.token || session.status != SearchStatus.RUNNING) {
                    return SearchRunResult.STALE
                }
                page = maxOf(page, session.nextPage)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = if (isTransientNetworkError(error)) {
                "네트워크 연결이 불안정해 검색을 종료했습니다. 다시 시도해 주세요."
            } else {
                error.message?.take(160) ?: error::class.java.simpleName
            }
            dao.fail(request.token, message, System.currentTimeMillis())
            return SearchRunResult.FAILED
        }
    }
}

internal class BackgroundSearchClient(
    http: OkHttpClient,
    private val parser: AvseeClient,
    network: Network? = null,
    private val retrySleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val http = http.newBuilder()
        .retryOnConnectionFailure(false)
        .apply {
            network?.let { assignedNetwork ->
                socketFactory(assignedNetwork.socketFactory)
                dns(Dns { hostname -> assignedNetwork.getAllByName(hostname).toList() })
            }
        }
        .build()

    suspend fun searchPage(baseUrl: String, query: String, page: Int): SearchPage = withContext(Dispatchers.IO) {
        val pageUrl = parser.buildSearchUrl(baseUrl, query, page)
        val firstUrl = parser.buildSearchUrl(baseUrl, query, 1)
        val request = Request.Builder().url(pageUrl)
            .get()
            .header("User-Agent", AvseeClient.USER_AGENT)
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7")
            .apply { if (page > 1) header("Referer", firstUrl) }
            .build()
        val html = retryTransientGet(sleep = retrySleep) {
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP_${response.code}" }
                response.body.string()
            }
        }
        parser.parseSearchPage(html, pageUrl)
    }
}

internal class SearchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        SearchNotifications.foregroundInfo(applicationContext, inputData.getString(SearchRequest.KEY_QUERY).orEmpty())

    override suspend fun doWork(): Result {
        val request = SearchRequest(
            token = inputData.getString(SearchRequest.KEY_TOKEN) ?: return Result.failure(),
            query = inputData.getString(SearchRequest.KEY_QUERY) ?: return Result.failure(),
            baseUrl = inputData.getString(SearchRequest.KEY_BASE_URL) ?: return Result.failure(),
        )
        try {
            setForeground(SearchNotifications.foregroundInfo(applicationContext, request.query))
        } catch (_: IllegalStateException) {
            return Result.retry()
        }
        return when (SearchRunner.run(request)) {
            SearchRunResult.COMPLETED, SearchRunResult.STALE -> Result.success()
            SearchRunResult.FAILED -> Result.failure()
        }
    }
}

internal class SearchExecutionGate {
    private var generation = 0L
    var token: String? = null
        private set

    fun begin(token: String): Long {
        generation += 1L
        this.token = token
        return generation
    }

    fun invalidate() {
        generation += 1L
        token = null
    }

    fun isCurrent(token: String, generation: Long): Boolean =
        this.token == token && this.generation == generation
}

class SearchJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    private val executionGate = SearchExecutionGate()

    @SuppressLint("NewApi")
    override fun onStartJob(params: JobParameters): Boolean {
        val request = SearchRequest.from(params.extras) ?: return false
        setNotification(
            params,
            SearchNotifications.notificationId,
            SearchNotifications.notification(applicationContext, request.query),
            JobService.JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        startOrReplace(params, request)
        return true
    }

    @SuppressLint("NewApi")
    override fun onNetworkChanged(params: JobParameters) {
        val request = SearchRequest.from(params.extras) ?: return
        if (executionGate.token != request.token) return
        startOrReplace(params, request)
    }

    @SuppressLint("NewApi")
    private fun startOrReplace(params: JobParameters, request: SearchRequest) {
        currentJob?.cancel()
        val generation = executionGate.begin(request.token)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            SearchRunner.run(request, params.network)
            withContext(Dispatchers.Main.immediate) {
                if (executionGate.isCurrent(request.token, generation)) {
                    currentJob = null
                    executionGate.invalidate()
                    jobFinished(params, false)
                }
            }
        }
        currentJob = task
        task.start()
    }

    @SuppressLint("NewApi")
    override fun onStopJob(params: JobParameters): Boolean {
        val stoppedToken = params.extras.getString(SearchRequest.KEY_TOKEN)
        if (executionGate.token == stoppedToken) {
            currentJob?.cancel()
            currentJob = null
            executionGate.invalidate()
        }
        return params.stopReason !in setOf(
            JobParameters.STOP_REASON_USER,
            JobParameters.STOP_REASON_CANCELLED_BY_APP,
        )
    }

    override fun onDestroy() {
        currentJob?.cancel()
        currentJob = null
        executionGate.invalidate()
        scope.cancel()
        super.onDestroy()
    }
}
