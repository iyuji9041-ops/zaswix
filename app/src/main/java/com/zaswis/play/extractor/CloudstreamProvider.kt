package com.zaswis.play.extractor

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.model.MediaType
import com.zaswis.play.data.model.StreamSource
import com.zaswis.play.extractor.utils.CloudstreamUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * CloudstreamProvider: Scrapes movies and series from Cloudstream providers (VegaMovies, Bollyflix, MultiMovies).
 * Centralizes a production-grade OkHttpClient configured with custom User-Agent, dynamic Referer,
 * redirects, and timeouts for all scraping, stream extraction, and media playback.
 */
object CloudstreamProvider {

    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
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

            // Automatic Referer derivation if not explicitly supplied
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

    private val gson = Gson()

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

    suspend fun fetchDocument(
        url: String,
        referer: String? = null,
        customHeaders: Map<String, String> = emptyMap()
    ): Document? = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(url, referer, customHeaders) ?: return@withContext null
            Jsoup.parse(html, url)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Live search from VegaMovies (Cloudstream CSX VegaMoviesProvider logic)
     */
    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MediaItem>()
        try {
            val vegaBase = CloudstreamUrls.getUrl("vegamovies")
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$vegaBase/search.php?q=$encodedQuery&page=1"

            val req = createRequest(searchUrl, referer = "$vegaBase/")
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                val json = gson.fromJson(body, JsonObject::class.java)
                val hits = json.getAsJsonArray("hits")
                hits?.forEach { hitElem ->
                    val doc = hitElem.asJsonObject.getAsJsonObject("document")
                    val title = doc.get("post_title")?.asString?.replace("Download ", "")?.replace("&amp;", "&") ?: ""
                    val permalink = doc.get("permalink")?.asString ?: ""
                    val thumb = doc.get("post_thumbnail")?.asString ?: ""
                    val pageUrl = if (permalink.startsWith("http")) permalink else "$vegaBase$permalink"

                    results.add(
                        MediaItem(
                            id = "vega_${doc.get("id")?.asString ?: permalink.hashCode().toString()}",
                            title = title,
                            posterUrl = thumb,
                            backdropUrl = thumb,
                            overview = title,
                            rating = "8.8",
                            year = "2024",
                            type = MediaType.MOVIE,
                            pageUrl = pageUrl,
                            providerName = "VegaMovies (Live)"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        results
    }

    /**
     * Resolves playable streams from a movie page URL using Cloudstream extraction pipeline
     * Utilizing Jsoup for HTML parsing and centralized OkHttpClient
     */
    suspend fun resolveMoviePage(pageUrl: String, registry: ExtractorRegistry): List<StreamSource> = withContext(Dispatchers.IO) {
        val streams = mutableListOf<StreamSource>()
        try {
            val html = fetchHtml(pageUrl) ?: return@withContext emptyList()

            // 1. Extract directly via registry from this HTML
            val extracted = registry.extractFromHtml(html, pageUrl)
            streams.addAll(extracted)

            // 2. Discover intermediate landing pages using Jsoup parsing
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

            // Also check iframe embeds
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src").ifEmpty { iframe.attr("src") }
                if (registry.canHandle(src)) {
                    val resolved = registry.extract(src, pageUrl)
                    streams.addAll(resolved)
                }
            }

            // Follow discovered target links
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
