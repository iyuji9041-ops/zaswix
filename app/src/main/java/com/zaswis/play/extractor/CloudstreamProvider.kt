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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * CloudstreamProvider: Scrapes movies and series from Cloudstream providers (VegaMovies, Bollyflix, MultiMovies).
 * Uses live dynamic domains from urls.json and resolves intermediate landing pages (nexdrive, vcloud, hubcloud, fastdl).
 */
object CloudstreamProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Live search from VegaMovies (Cloudstream CSX VegaMoviesProvider logic)
     */
    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MediaItem>()
        try {
            val vegaBase = CloudstreamUrls.getUrl("vegamovies")
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$vegaBase/search.php?q=$encodedQuery&page=1"

            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()

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
     */
    suspend fun resolveMoviePage(pageUrl: String, registry: ExtractorRegistry): List<StreamSource> = withContext(Dispatchers.IO) {
        val streams = mutableListOf<StreamSource>()
        try {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string().orEmpty()

            // 1. Extract directly via registry from this HTML
            val extracted = registry.extractFromHtml(html, pageUrl)
            streams.addAll(extracted)

            // 2. Discover intermediate landing pages (nexdrive.beer, vcloud, hubcloud, fastdl, pixeldrain)
            val linkRegex = Regex("""href=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            val targetUrls = mutableListOf<String>()
            linkRegex.findAll(html).forEach { m ->
                val link = m.groupValues[1]
                if (link.contains("vcloud") || link.contains("hubcloud") ||
                    link.contains("fastdl") || link.contains("gdflix") ||
                    link.contains("pixeldrain") || link.contains("nexdrive") || link.contains("filebee")) {
                    targetUrls.add(link)
                }
            }

            // Follow discovered target links
            targetUrls.take(6).forEach { targetUrl ->
                if (targetUrl.contains("nexdrive") || targetUrl.contains("filebee")) {
                    // Fetch landing page and find vcloud/hubcloud
                    try {
                        val subReq = Request.Builder().url(targetUrl).header("User-Agent", DEFAULT_USER_AGENT).build()
                        val subResp = client.newCall(subReq).execute()
                        if (subResp.isSuccessful) {
                            val subHtml = subResp.body?.string().orEmpty()
                            val subExtracted = registry.extractFromHtml(subHtml, targetUrl)
                            streams.addAll(subExtracted)

                            linkRegex.findAll(subHtml).forEach { subMatch ->
                                val directLink = subMatch.groupValues[1]
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
