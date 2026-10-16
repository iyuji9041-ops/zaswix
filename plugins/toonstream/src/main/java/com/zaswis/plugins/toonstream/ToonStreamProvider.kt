package com.zaswis.plugins.toonstream

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
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Standalone ToonStream Provider Plugin.
 * Implements ZaswixPlugin using Jsoup to scrape toonstream.us.
 * This class is instantiated at runtime via DexClassLoader:
 * Class.forName("com.zaswis.plugins.toonstream.ToonStreamProvider").newInstance() as ZaswixPlugin
 */
class ToonStreamProvider : ZaswixPlugin {

    override val providerName: String = "ToonStream"
    override val version: String = "1.0.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val baseUrl = "https://toonstream.us"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private suspend fun fetchHtml(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) res.body?.string() else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun getHomePage(): HomePagePayload = withContext(Dispatchers.IO) {
        val html = fetchHtml(baseUrl) ?: return@withContext HomePagePayload(emptyList(), emptyList())
        val doc = Jsoup.parse(html, baseUrl)

        val heroBanner = mutableListOf<ScrapedMediaItem>()
        val categoryRows = mutableListOf<ScrapedCategoryRow>()

        // 1. Featured / Top Banner
        doc.select(".featured-slider .item, .swiper-slide, article.post").take(5).forEachIndexed { idx, element ->
            val title = element.selectFirst(".entry-title, h2, h3")?.text()?.trim().orEmpty()
            val link = element.selectFirst("a")?.attr("abs:href").orEmpty()
            val poster = element.selectFirst("img")?.attr("src").orEmpty()

            if (title.isNotEmpty() && link.isNotEmpty()) {
                heroBanner.add(
                    ScrapedMediaItem(
                        id = "ts_hero_$idx",
                        title = title,
                        posterUrl = poster,
                        backdropUrl = poster,
                        synopsis = "Watch $title in Hindi Dub & Multi Audio on ToonStream",
                        rating = "8.8",
                        type = "Anime",
                        quality = "1080p FHD",
                        providerName = providerName,
                        url = link
                    )
                )
            }
        }

        // 2. Category Sections / Rows
        val popularItems = mutableListOf<ScrapedMediaItem>()
        doc.select("article.post, .post-thumbnail").take(15).forEachIndexed { idx, post ->
            val link = post.selectFirst("a")?.attr("abs:href").orEmpty()
            val title = post.selectFirst(".entry-title, h2, h3")?.text()?.trim()
                ?: post.selectFirst("img")?.attr("alt").orEmpty()
            val poster = post.selectFirst("img")?.attr("src").orEmpty()

            if (title.isNotEmpty() && link.isNotEmpty()) {
                popularItems.add(
                    ScrapedMediaItem(
                        id = "ts_pop_$idx",
                        title = title,
                        posterUrl = poster,
                        backdropUrl = poster,
                        synopsis = title,
                        rating = "8.6",
                        type = "Anime",
                        quality = "HD",
                        providerName = providerName,
                        url = link
                    )
                )
            }
        }

        if (popularItems.isNotEmpty()) {
            categoryRows.add(
                ScrapedCategoryRow(
                    categoryTitle = "Trending Anime & Toons",
                    categorySubtitle = "Latest episodes auto-streamed from ToonStream",
                    items = popularItems
                )
            )
        }

        HomePagePayload(
            heroBannerItems = heroBanner.ifEmpty { popularItems.take(5) },
            categoryRows = categoryRows
        )
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val searchUrl = "$baseUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val html = fetchHtml(searchUrl) ?: return@withContext emptyList()
        val doc = Jsoup.parse(html, searchUrl)

        val results = mutableListOf<MediaItem>()
        doc.select("article.post, .result-item").forEach { item ->
            val link = item.selectFirst("a")?.attr("abs:href").orEmpty()
            val title = item.selectFirst(".entry-title, h2, h3")?.text()?.trim()
                ?: item.selectFirst("img")?.attr("alt").orEmpty()
            val poster = item.selectFirst("img")?.attr("src").orEmpty()

            if (title.isNotEmpty() && link.isNotEmpty()) {
                results.add(
                    MediaItem(
                        title = title,
                        poster = poster,
                        url = link,
                        type = "anime"
                    )
                )
            }
        }
        results
    }

    override suspend fun getDetails(url: String): MediaDetails = withContext(Dispatchers.IO) {
        val html = fetchHtml(url) ?: return@withContext MediaDetails("Unavailable", "", "")
        val doc = Jsoup.parse(html, url)

        val article = doc.selectFirst("article.post.single")
        val title = article?.selectFirst("h1.entry-title")?.text()?.replace("\\s+".toRegex(), " ")?.trim().orEmpty()
        val synopsis = article?.selectFirst(".description")?.text()?.trim().orEmpty()
        val rating = article?.selectFirst(".vote-cn .vote .num")?.text()?.trim().orEmpty()
        val genres = article?.select(".entry-meta span.genres a")?.map { it.text().trim() }.orEmpty()

        // Extract Server Names and Iframes
        val servers = mutableListOf<ServerOption>()
        val serverNameMap = mutableMapOf<String, String>()
        doc.select(".aa-tbs-video li").forEach { li ->
            val targetId = li.selectFirst("a.btn")?.attr("href")?.removePrefix("#").orEmpty()
            val srvName = li.selectFirst("span.server")?.text()?.trim().orEmpty()
            if (targetId.isNotEmpty() && srvName.isNotEmpty()) {
                serverNameMap[targetId] = srvName
            }
        }

        doc.select("#aa-options > div.video").forEachIndexed { idx, videoDiv ->
            val divId = videoDiv.id()
            val iframe = videoDiv.selectFirst("iframe") ?: return@forEachIndexed
            val iframeUrl = iframe.attr("data-src").ifEmpty { iframe.attr("src") }.trim()

            if (iframeUrl.isNotEmpty() && iframeUrl != "about:blank") {
                val srvName = serverNameMap[divId] ?: "Server ${idx + 1}"
                servers.add(
                    ServerOption(
                        id = divId,
                        serverName = srvName,
                        embedUrl = iframeUrl,
                        quality = "Multi Audio HD",
                        referer = url
                    )
                )
            }
        }

        // Extract Episodes
        val episodes = doc.select("ul#episode_by_temp > li > article.post.episodes").mapIndexedNotNull { idx, epArt ->
            val epLink = epArt.selectFirst("a.lnk-blk")?.attr("abs:href").orEmpty()
            val thumb = epArt.selectFirst(".post-thumbnail2 img")?.attr("src").orEmpty()
            val numEpi = epArt.selectFirst("span.num-epi")?.text()?.trim().orEmpty()
            val epTitle = epArt.selectFirst("h5.entry-title1")?.text()?.trim().orEmpty()
            val seasonNum = numEpi.substringBefore("x").toIntOrNull() ?: 1
            val epNum = numEpi.substringAfter("x").toIntOrNull() ?: (idx + 1)

            if (epLink.isEmpty()) null else EpisodeItem(
                season = seasonNum,
                episode = epNum,
                title = epTitle.ifEmpty { "Episode $numEpi" },
                url = epLink,
                thumbnailUrl = thumb
            )
        }

        MediaDetails(
            title = title,
            synopsis = synopsis,
            rating = rating,
            servers = servers,
            episodes = episodes,
            genres = genres
        )
    }
}
