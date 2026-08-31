package com.shaterguy.fc2weeklyranker

import android.app.Application
import androidx.room.Room
import com.shaterguy.fc2weeklyranker.data.AppDatabase
import com.shaterguy.fc2weeklyranker.data.SettingsStore
import com.shaterguy.fc2weeklyranker.network.AvseeClient
import com.shaterguy.fc2weeklyranker.network.FallbackDns
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
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

        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
        val cloudflareDns = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(ipv4(1, 1, 1, 1), ipv4(1, 0, 0, 1))
            .post(true)
            .build()
        val googleDns = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(ipv4(8, 8, 8, 8), ipv4(8, 8, 4, 4))
            .post(true)
            .build()
        val resilientDns = FallbackDns(listOf(Dns.SYSTEM, cloudflareDns, googleDns))

        httpClient = bootstrapClient.newBuilder()
            .dns(resilientDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
        sourceClient = AvseeClient(httpClient)
        repository = AppRepository(app, database, settings, sourceClient)
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
}
