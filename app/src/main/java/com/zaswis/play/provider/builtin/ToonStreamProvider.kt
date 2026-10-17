package com.zaswis.play.provider.builtin

import android.util.Log
import com.zaswis.play.extractor.EpisodeItem
import com.zaswis.play.extractor.HomePagePayload
import com.zaswis.play.extractor.ScrapedCategoryRow
import com.zaswis.play.extractor.ScrapedMediaItem
import com.zaswis.play.extractor.ServerOption
import com.zaswis.play.plugin.MediaDetails
import com.zaswis.play.plugin.MediaItem
import com.zaswis.play.plugin.ZaswixPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Built-in ToonStream Provider.
 * High-speed Anime and Animated Cartoons scraper with 10+ multi-server streaming options.
 */
class ToonStreamProvider : ZaswixPlugin {

    override val name: String = "ToonStream"
    override val providerName: String = "ToonStream"
    override val version: String = "1.0.0"
    private val baseUrl: String = "https://toonstream.us"

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun fetchDoc(url: String, referer: String = baseUrl): Document {
        val fullUrl = when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> "$baseUrl/$url"
        }

        try {
            val req = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val body = res.body?.string().orEmpty()
                if (body.isNotEmpty()) {
                    return Jsoup.parse(body, fullUrl)
                }
            }
        } catch (t: Throwable) {
            Log.w("ToonStream", "OkHttp failed for $fullUrl: ${t.message}, trying Jsoup")
        }

        return Jsoup.connect(fullUrl)
            .userAgent(userAgent)
            .referrer(referer)
            .ignoreHttpErrors(true)
            .timeout(15000)
            .get()
    }

    override suspend fun getHomePage(): HomePagePayload = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc("$baseUrl/home")
            val heroBanner = mutableListOf<ScrapedMediaItem>()
            val categoryRows = mutableListOf<ScrapedCategoryRow>()

            // Parse Sections on Toonstream Homepage
            doc.select("section.wdgt-home, section.widget").forEach { section ->
                val headerTitle = section.selectFirst(".section-header h2, header.section-header")?.text()
                    ?.replace("View more", "")?.replace("🗑️", "")?.trim().orEmpty()

                if (headerTitle.isEmpty() || headerTitle.contains("History", ignoreCase = true)) return@forEach

                val items = section.select("article.post").mapNotNull { article ->
                    val title = article.selectFirst(".entry-title, h2, h3")?.text()?.trim()
                        ?: article.selectFirst("img")?.attr("alt")?.trim() ?: return@mapNotNull null
                    val link = article.selectFirst("a.lnk-blk, a")?.attr("href")?.trim() ?: return@mapNotNull null
                    val poster = article.selectFirst("img")?.attr("src")?.trim().orEmpty()
                    val rating = article.selectFirst(".entry-meta .vote")?.text()
                        ?.replace("TMDB", "")?.trim() ?: "8.5"
                    val seasonEp = article.selectFirst(".post-ql")?.text()?.trim().orEmpty()
                    val epText = article.selectFirst(".year")?.text()?.trim().orEmpty()
                    val fullUrl = if (link.startsWith("http")) link else "$baseUrl$link"

                    ScrapedMediaItem(
                        id = "${providerName}_${fullUrl.ifBlank { title }}",
                        title = title,
                        url = fullUrl,
                        posterUrl = poster,
                        backdropUrl = poster,
                        synopsis = "$seasonEp $epText".trim(),
                        rating = rating,
                        quality = "1080p FHD",
                        year = "2026",
                        type = if (link.contains("/series/") || link.contains("/episode/")) "Series" else "Movie",
                        providerName = providerName
                    )
                }

                if (items.isNotEmpty()) {
                    if (heroBanner.isEmpty()) {
                        heroBanner.addAll(items.take(8))
                    }
                    categoryRows.add(ScrapedCategoryRow(categoryTitle = headerTitle, items = items))
                }
            }

            HomePagePayload(heroBannerItems = heroBanner, categoryRows = categoryRows)
        } catch (t: Throwable) {
            Log.e("ToonStream", "getHomePage failed: ${t.message}", t)
            HomePagePayload()
        }
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val doc = fetchDoc("$baseUrl/?s=$encoded")
            doc.select("article.post, .result-item").mapNotNull { article ->
                val title = article.selectFirst(".entry-title, h2, h3")?.text()?.trim()
                    ?: article.selectFirst("img")?.attr("alt")?.trim() ?: return@mapNotNull null
                val link = article.selectFirst("a.lnk-blk, a")?.attr("href")?.trim() ?: return@mapNotNull null
                val poster = article.selectFirst("img")?.attr("src")?.trim().orEmpty()
                val fullUrl = if (link.startsWith("http")) link else "$baseUrl$link"

                MediaItem(
                    title = title,
                    poster = poster,
                    url = fullUrl,
                    type = if (link.contains("/series/") || link.contains("/episode/")) "series" else "movie"
                )
            }
        } catch (t: Throwable) {
            Log.e("ToonStream", "search failed: ${t.message}", t)
            emptyList()
        }
    }

    override suspend fun getDetails(url: String): MediaDetails = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc(url)
            val article = doc.selectFirst("article.post.single, article.post")
            val title = article?.selectFirst("h1.entry-title")?.text()?.replace("\\s+".toRegex(), " ")?.trim().orEmpty()
            val synopsis = article?.selectFirst(".entry-content p, .description")?.text()?.trim().orEmpty()
            val rating = article?.selectFirst(".vote-cn .vote .num, .entry-meta .vote")?.text()
                ?.replace("TMDB", "")?.trim() ?: "8.5"
            val genres = article?.select(".entry-meta span.genres a, .genres a")?.map { it.text().trim() }.orEmpty()

            // 1. Extract Server Names & Iframes (Ruby, SD, HD, FHD, Cloudy, Vidmoly, etc.)
            val servers = mutableListOf<ServerOption>()
            val serverNameMap = mutableMapOf<String, String>()
            doc.select(".aa-tbs-video li, .video-options li").forEach { li ->
                val targetId = li.selectFirst("a.btn")?.attr("href")?.removePrefix("#").orEmpty()
                val srvName = li.selectFirst("span.server")?.text()?.trim().orEmpty()
                if (targetId.isNotEmpty() && srvName.isNotEmpty()) {
                    serverNameMap[targetId] = srvName
                }
            }

            doc.select("#aa-options > div.video, .video-options-content div.video").forEachIndexed { idx, videoDiv ->
                val divId = videoDiv.id()
                val iframe = videoDiv.selectFirst("iframe") ?: return@forEachIndexed
                val iframeUrl = iframe.attr("data-src").ifEmpty { iframe.attr("src") }.trim()

                if (iframeUrl.isNotEmpty() && iframeUrl != "about:blank") {
                    val srvName = serverNameMap[divId] ?: "Server ${idx + 1}"
                    servers.add(
                        ServerOption(
                            id = divId.ifEmpty { "ts_srv_$idx" },
                            serverName = srvName,
                            embedUrl = iframeUrl,
                            quality = "Multi Audio HD",
                            referer = url
                        )
                    )
                }
            }

            // Fallback iframes directly in page
            if (servers.isEmpty()) {
                doc.select("iframe").forEachIndexed { idx, iframe ->
                    val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }.trim()
                    if (src.isNotEmpty() && src.startsWith("http") && !src.contains("googletag") && !src.contains("ad")) {
                        servers.add(
                            ServerOption(
                                id = "ts_iframe_$idx",
                                serverName = "Server #${idx + 1}",
                                embedUrl = src,
                                quality = "HD",
                                referer = url
                            )
                        )
                    }
                }
            }

            // 2. Extract Episodes list
            val episodes = doc.select("ul#episode_by_temp > li > article.post.episodes, .episodes-list article").mapIndexedNotNull { idx, epArt ->
                val epLink = epArt.selectFirst("a.lnk-blk, a")?.attr("href")?.trim().orEmpty()
                val thumb = epArt.selectFirst("img")?.attr("src")?.trim().orEmpty()
                val numEpi = epArt.selectFirst("span.num-epi")?.text()?.trim().orEmpty()
                val epTitle = epArt.selectFirst("h5.entry-title1, .entry-title")?.text()?.trim().orEmpty()
                val seasonNum = numEpi.substringBefore("x").toIntOrNull() ?: 1
                val epNum = numEpi.substringAfter("x").toIntOrNull() ?: (idx + 1)
                val fullEpLink = if (epLink.startsWith("http")) epLink else "$baseUrl$epLink"

                if (epLink.isEmpty()) null else EpisodeItem(
                    season = seasonNum,
                    episode = epNum,
                    title = epTitle.ifEmpty { "Episode $numEpi" },
                    url = fullEpLink,
                    thumbnailUrl = thumb
                )
            }

            MediaDetails(
                title = title.ifEmpty { "ToonStream Media" },
                synopsis = synopsis,
                rating = rating,
                servers = servers,
                episodes = episodes,
                genres = if (genres.isNotEmpty()) genres else listOf("Anime", "Animation", "Multi Audio")
            )
        } catch (t: Throwable) {
            Log.e("ToonStream", "getDetails failed: ${t.message}", t)
            MediaDetails(title = "", synopsis = "", rating = "")
        }
    }
}
