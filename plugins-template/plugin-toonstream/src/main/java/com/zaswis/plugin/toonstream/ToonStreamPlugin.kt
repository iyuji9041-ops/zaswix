package com.zaswis.plugin.toonstream

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
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Standalone Headless Plugin for ToonStream (https://toonstream.us)
 * Compiled into a standalone APK and loaded dynamically at runtime via DexClassLoader.
 * Contains zero UI code.
 */
class ToonStreamPlugin : ZaswixPlugin {

    override val providerName: String = "ToonStream"
    override val version: String = "1.2.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val baseUrl = "https://toonstream.us"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private suspend fun fetchDoc(url: String, referer: String = baseUrl): Document? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .build()
            val html = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (html.isNotBlank()) Jsoup.parse(html, url) else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Scrapes the ToonStream homepage swiper sections
     */
    override suspend fun getHomePage(): HomePagePayload = withContext(Dispatchers.IO) {
        val doc = fetchDoc("$baseUrl/home") ?: return@withContext HomePagePayload()
        val rows = mutableListOf<ScrapedCategoryRow>()

        doc.select("section.watch_history_widget").forEach { section ->
            val title = section.selectFirst("h3.section-title")?.text()?.trim().orEmpty()
            if (title.equals("Watch History", ignoreCase = true)) return@forEach

            val articles = section.select(".swiper-slide article.post")
            val items = articles.mapIndexedNotNull { idx, article ->
                val itemTitle = article.selectFirst("h2.entry-title")?.text()?.trim().orEmpty()
                val link = article.selectFirst("a.lnk-blk")?.attr("abs:href").orEmpty()
                val poster = article.selectFirst(".post-thumbnail img")?.attr("data-src")?.ifEmpty {
                    article.selectFirst(".post-thumbnail img")?.attr("src")
                }.orEmpty()
                val rating = article.selectFirst(".entry-meta .vote")?.text()?.replace("TMDB", "")?.trim().orEmpty()
                val epBadge = article.selectFirst("span.year")?.text()?.trim().orEmpty()
                val seasonBadge = article.selectFirst("span.post-ql")?.text()?.trim().orEmpty()

                if (itemTitle.isEmpty() || link.isEmpty()) null else ScrapedMediaItem(
                    id = "ts_${title}_$idx",
                    title = itemTitle,
                    url = link,
                    posterUrl = poster,
                    backdropUrl = poster,
                    rating = if (rating == "N/A") "" else rating,
                    quality = listOf(seasonBadge, epBadge).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "HD" },
                    providerName = providerName
                )
            }
            if (title.isNotEmpty() && items.isNotEmpty()) {
                rows.add(ScrapedCategoryRow(title, items))
            }
        }

        val heroItems = rows.firstOrNull()?.items?.take(6).orEmpty()
        HomePagePayload(heroItems, rows)
    }

    /**
     * Searches ToonStream with query
     */
    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = fetchDoc("$baseUrl/?s=$encoded") ?: return@withContext emptyList()
        val results = mutableListOf<MediaItem>()

        doc.select("article.post").forEach { article ->
            val title = article.selectFirst("h2.entry-title")?.text()?.trim().orEmpty()
            val link = article.selectFirst("a.lnk-blk")?.attr("abs:href").orEmpty()
            val poster = article.selectFirst(".post-thumbnail img")?.attr("data-src")?.ifEmpty {
                article.selectFirst(".post-thumbnail img")?.attr("src")
            }.orEmpty()
            val type = if (link.contains("/series/")) "series" else "movie"

            if (title.isNotEmpty() && link.isNotEmpty()) {
                results.add(MediaItem(title = title, poster = poster, url = link, type = type))
            }
        }
        results
    }

    /**
     * Scrapes video details, multi-audio server links, and episode listings
     */
    override suspend fun getDetails(url: String): MediaDetails = withContext(Dispatchers.IO) {
        val doc = fetchDoc(url) ?: return@withContext MediaDetails(title = "", synopsis = "", rating = "")

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim().orEmpty()
        val rating = doc.selectFirst(".vote-circle")?.text()?.trim().orEmpty()
        val genres = doc.select(".genres a").map { it.text().trim() }

        val servers = mutableListOf<ServerOption>()
        doc.select("ul#playeroptionsul > li").forEachIndexed { idx, opt ->
            val post = opt.attr("data-post")
            val nume = opt.attr("data-nume")
            val serverName = opt.selectFirst(".title")?.text()?.trim() ?: "Server ${idx + 1}"

            if (post.isNotEmpty() && nume.isNotEmpty()) {
                try {
                    val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
                    val formBody = okhttp3.FormBody.Builder()
                        .add("action", "doo_player_ajax")
                        .add("post", post)
                        .add("nume", nume)
                        .add("type", opt.attr("data-type"))
                        .build()

                    val req = Request.Builder()
                        .url(ajaxUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", url)
                        .post(formBody)
                        .build()

                    val resBody = client.newCall(req).execute().use { it.body?.string().orEmpty() }
                    val embedUrl = JSONObject(resBody).optString("embed_url")
                    if (embedUrl.isNotBlank()) {
                        servers.add(
                            ServerOption(
                                id = "ts_srv_$idx",
                                serverName = serverName,
                                embedUrl = embedUrl,
                                quality = "HD",
                                referer = url
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }

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
