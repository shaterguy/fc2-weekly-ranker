package com.shaterguy.fc2weeklyranker

import android.app.Application
import androidx.room.Room
import com.shaterguy.fc2weeklyranker.data.AppDatabase
import com.shaterguy.fc2weeklyranker.data.SettingsStore
import com.shaterguy.fc2weeklyranker.network.AvseeClient
import com.shaterguy.fc2weeklyranker.network.RetryingDns
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RankerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
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
            .build()
        settings = SettingsStore(app)
        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
        sourceClient = AvseeClient(httpClient.newBuilder().dns(RetryingDns()).build())
        repository = AppRepository(app, database, settings, sourceClient)
    }
}
