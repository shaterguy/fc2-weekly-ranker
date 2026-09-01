package com.shaterguy.fc2weeklyranker

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.room.Room
import com.shaterguy.fc2weeklyranker.data.AppDatabase
import com.shaterguy.fc2weeklyranker.data.SettingsStore
import com.shaterguy.fc2weeklyranker.network.AvseeClient
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RankerApplication : Application() {
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                recoveryScope.launch { runCatching { AppGraph.repository.recoverDownloads() } }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

object AppGraph {
    lateinit var database: AppDatabase
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var httpClient: OkHttpClient
        private set
    lateinit var sourceClient: AvseeClient
        private set
    lateinit var repository: AppRepository
        private set

    fun initialize(app: Application) {
        if (::database.isInitialized) return
        database = Room.databaseBuilder(app, AppDatabase::class.java, "ranker.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        settings = SettingsStore(app)
        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
        sourceClient = AvseeClient(httpClient)
        repository = AppRepository(app, database, settings, sourceClient)
    }
}
