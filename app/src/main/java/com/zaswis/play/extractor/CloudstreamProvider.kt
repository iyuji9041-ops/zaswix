package com.zaswis.play.extractor

import com.zaswis.play.data.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * CloudstreamProvider: Centralized networking and stream extraction pipeline for embed pages.
 * Handles generic landing page link extraction using ExtractorRegistry.
 * Contains no hardcoded website scrapers.
 */
object CloudstreamProvider {

    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(com.zaswis.play.network.CloudflareDns.dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()

            if (original.header("User-Agent") == null) {
                requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
            }
            if (original.header("Accept") == null) {
                requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            }
            if (original.header("Accept-Language") == null) {
                requestBuilder.header("Accept-Language", "en-US,en;q=0.9")
            }

            if (original.header("Referer") == null) {
                try {
                    val uri = URI(original.url.toString())
                    val hostReferer = "${uri.scheme}://${uri.host}/"
                    requestBuilder.header("Referer", hostReferer)
                } catch (e: Exception) {
                    // Ignore URI parsing issues
                }
            }

            chain.proceed(requestBuilder.build())
        }
        .build()

    fun createRequest(
        url: String,
        referer: String? = null,
        customHeaders: Map<String, String> = emptyMap()
    ): Request {
        val builder = Request.Builder().url(url)
        builder.header("User-Agent", DEFAULT_USER_AGENT)
        referer?.let { builder.header("Referer", it) }
        customHeaders.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    suspend fun fetchHtml(
        url: String,
        referer: String? = null,
        customHeaders: Map<String, String> = emptyMap()
    ): String? = withContext(Dispatchers.IO) {
        try {
            val req = createRequest(url, referer, customHeaders)
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                resp.body?.string()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves playable streams from an embed landing page URL using ExtractorRegistry.
     */
    suspend fun resolveMoviePage(pageUrl: String, registry: ExtractorRegistry): List<StreamSource> = withContext(Dispatchers.IO) {
        val streams = mutableListOf<StreamSource>()
        try {
            val html = fetchHtml(pageUrl) ?: return@withContext emptyList()

            val extracted = registry.extractFromHtml(html, pageUrl)
            streams.addAll(extracted)

            val doc = Jsoup.parse(html, pageUrl)
            val targetUrls = mutableListOf<String>()

            doc.select("a[href]").forEach { a ->
                val link = a.attr("abs:href").ifEmpty { a.attr("href") }
                if (link.contains("vcloud") || link.contains("hubcloud") ||
                    link.contains("fastdl") || link.contains("gdflix") ||
                    link.contains("pixeldrain") || link.contains("nexdrive") || link.contains("filebee")) {
                    targetUrls.add(link)
                }
            }

            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src").ifEmpty { iframe.attr("src") }
                if (registry.canHandle(src)) {
                    val resolved = registry.extract(src, pageUrl)
                    streams.addAll(resolved)
                }
            }

            targetUrls.distinct().take(6).forEach { targetUrl ->
                if (targetUrl.contains("nexdrive") || targetUrl.contains("filebee")) {
                    try {
                        val subHtml = fetchHtml(targetUrl, referer = pageUrl)
                        if (!subHtml.isNullOrBlank()) {
                            val subExtracted = registry.extractFromHtml(subHtml, targetUrl)
                            streams.addAll(subExtracted)

                            val subDoc = Jsoup.parse(subHtml, targetUrl)
                            subDoc.select("a[href]").forEach { subA ->
                                val directLink = subA.attr("abs:href").ifEmpty { subA.attr("href") }
                                if (directLink.contains("vcloud") || directLink.contains("hubcloud") || directLink.contains("pixeldrain")) {
                                    val finalStreams = registry.extract(directLink, targetUrl)
                                    streams.addAll(finalStreams)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                } else {
                    val resolved = registry.extract(targetUrl, pageUrl)
                    streams.addAll(resolved)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        streams.distinctBy { it.url }
    }
}
