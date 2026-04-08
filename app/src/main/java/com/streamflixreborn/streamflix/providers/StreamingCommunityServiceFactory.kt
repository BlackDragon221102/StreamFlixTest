package com.streamflixreborn.streamflix.providers

import android.os.Looper
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

internal interface StreamingCommunityServiceFactoryContract {
    fun build(
        baseUrl: String,
        language: String,
        domainProvider: () -> String,
        onDomainChanged: (String) -> Unit,
        lang: String,
    ): StreamingCommunityService

    fun buildUnsafe(
        baseUrl: String,
        language: String,
        lang: String,
    ): StreamingCommunityService

    fun resolveFinalBaseUrl(
        startBaseUrl: String,
        language: String,
    ): String

    fun fetchDocumentWithRedirectsAndSslFallback(
        url: String,
        referer: String,
        language: String,
    ): Document
}

internal object StreamingCommunityServiceFactory : StreamingCommunityServiceFactoryContract {
    const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun build(
        baseUrl: String,
        language: String,
        domainProvider: () -> String,
        onDomainChanged: (String) -> Unit,
        lang: String,
    ): StreamingCommunityService {
        val client = NetworkClient.default.newBuilder()
            .addInterceptor(RateLimitInterceptor())
            .addInterceptor(RefererInterceptor(baseUrl))
            .addInterceptor(UserAgentInterceptor(USER_AGENT) { language })
            .addInterceptor(RedirectInterceptor(domainProvider, onDomainChanged))
            .build()

        return Retrofit.Builder()
            .baseUrl("$baseUrl$lang/")
            .addConverterFactory(JsoupConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(StreamingCommunityService::class.java)
    }

    override fun buildUnsafe(
        baseUrl: String,
        language: String,
        lang: String,
    ): StreamingCommunityService {
        val client = NetworkClient.trustAll.newBuilder()
            .addInterceptor(RateLimitInterceptor())
            .addInterceptor(RefererInterceptor(baseUrl))
            .addInterceptor(UserAgentInterceptor(USER_AGENT) { language })
            .build()

        return Retrofit.Builder()
            .baseUrl("$baseUrl$lang/")
            .addConverterFactory(JsoupConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(StreamingCommunityService::class.java)
    }

    override fun resolveFinalBaseUrl(
        startBaseUrl: String,
        language: String,
    ): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return startBaseUrl

        return try {
            val client = NetworkClient.default.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor(RefererInterceptor(startBaseUrl))
                .addInterceptor(UserAgentInterceptor(USER_AGENT) { language })
                .build()

            val request = okhttp3.Request.Builder()
                .url(startBaseUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val finalUri = response.request.url
                "${finalUri.scheme}://${finalUri.host}/"
            }
        } catch (_: Exception) {
            startBaseUrl
        }
    }

    override fun fetchDocumentWithRedirectsAndSslFallback(
        url: String,
        referer: String,
        language: String,
    ): Document {
        val client = NetworkClient.default.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(RefererInterceptor(referer))
            .addInterceptor(UserAgentInterceptor(USER_AGENT) { language })
            .build()

        return try {
            client.newCall(
                okhttp3.Request.Builder()
                    .url(url)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .get()
                    .build()
            ).execute().use { response ->
                Jsoup.parse(response.body?.string() ?: "")
            }
        } catch (_: Exception) {
            Jsoup.parse("")
        }
    }

    private class UserAgentInterceptor(
        private val userAgent: String,
        private val languageProvider: () -> String,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val language = languageProvider()
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept-Language", if (language == "en") "en-US,en;q=0.9" else "it-IT,it;q=0.9")
                .header("Cookie", "language=$language")
                .build()
            return chain.proceed(request)
        }
    }

    private class RefererInterceptor(
        private val referer: String,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response =
            chain.proceed(chain.request().newBuilder().header("Referer", referer).build())
    }

    private class RedirectInterceptor(
        private val domainProvider: () -> String,
        private val onDomainChanged: (String) -> Unit,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            var response = chain.proceed(request)
            val visited = mutableSetOf<String>()
            val currentDomain = domainProvider()

            while (response.isRedirect) {
                val location = response.header("Location") ?: break
                val newUrl =
                    if (location.startsWith("http")) location
                    else request.url.resolve(location)?.toString() ?: break

                if (!visited.add(newUrl)) break

                val host = newUrl.substringAfter("https://").substringBefore("/")
                if (host.isNotEmpty() && host != currentDomain && !host.contains("streamingcommunityz.green")) {
                    onDomainChanged(host)
                }

                response.close()
                request = request.newBuilder().url(newUrl).build()
                response = chain.proceed(request)
            }

            return response
        }
    }

    private class RateLimitInterceptor(
        private val minIntervalMs: Long = 350L,
    ) : Interceptor {
        companion object {
            private val lock = Any()
            private var lastRequestAt: Long = 0L
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val waitMs = minIntervalMs - (now - lastRequestAt)
                if (waitMs > 0) {
                    Thread.sleep(waitMs)
                }
                lastRequestAt = System.currentTimeMillis()
            }
            return chain.proceed(chain.request())
        }
    }
}
