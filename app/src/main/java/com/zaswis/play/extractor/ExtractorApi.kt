package com.zaswis.play.extractor

import com.zaswis.play.extractor.model.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Base abstract class for all Video Host Extractors (Cloudstream pattern)
 */
abstract class ExtractorApi {

    abstract val name: String
    abstract val mainUrl: String
    open val requiresReferer: Boolean = false

    protected val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    protected val defaultUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    open fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(mainUrl.lowercase())
    }

    abstract suspend fun getUrl(url: String, referer: String? = null): List<ExtractorLink>

    protected suspend fun fetchHtml(
        url: String,
        referer: String? = null,
        customHeaders: Map<String, String> = emptyMap()
    ): String? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", defaultUserAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")

            referer?.let { builder.header("Referer", it) }
            customHeaders.forEach { (k, v) -> builder.header(k, v) }

            val response = client.newCall(builder.build()).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
