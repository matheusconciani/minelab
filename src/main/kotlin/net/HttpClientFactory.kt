package net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared, singleton OkHttpClient to be reused across all network calls (auth, skin downloads).
 */
object HttpClientFactory {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
