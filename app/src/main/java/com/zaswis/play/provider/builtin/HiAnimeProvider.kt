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
 * Built-in HiAnime Provider.
 * Global anime streaming scraper for Trending, Top Airing, and Subbed/Dubbed Japanese Anime.
 */
class HiAnimeProvider : ZaswixPlugin {

    override val name: String = "HiAnime"
    override val providerName: String = "HiAnime"
    override val version: String = "1.0.0"
    private val baseUrl: String = "https://hianime.ro"

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
            Log.w("HiAnime", "OkHttp failed for $fullUrl: ${t.message}, trying Jsoup")
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

            // 1. Trending slider items
            val trendingItems = doc.select(".deslide-item, .swiper-slide, #slider .item").mapNotNull { item ->
                val title = item.selectFirst(".desi-head-title a, h2, h3")?.text()?.trim() ?: return@mapNotNull null
                val link = item.selectFirst(".desi-head-title a, a")?.attr("href")?.trim() ?: return@mapNotNull null
                val poster = item.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifEmpty { img.attr("src") }
                }?.trim().orEmpty()
                val desc = item.selectFirst(".desi-description")?.text()?.trim().orEmpty()
                val fullUrl = if (link.startsWith("http")) link else "$baseUrl$link"

                ScrapedMediaItem(
                    id = "${providerName}_${fullUrl.ifBlank { title }}",
                    title = title,
                    url = fullUrl,
                    posterUrl = poster,
                    backdropUrl = poster,
                    synopsis = desc,
                    rating = "9.0",
                    quality = "1080p HD",
                    year = "2026",
                    type = "Anime",
                    providerName = providerName
                )
            }
            if (trendingItems.isNotEmpty()) {
                heroBanner.addAll(trendingItems.distinctBy { it.url }.take(8))
            }

            // 2. Sections (Trending, Most Popular, Latest Completed, etc.)
            doc.select("section.block_area, .block_area").forEach { section ->
                val heading = section.selectFirst(".cat-heading, .heading, h2")?.text()?.trim().orEmpty()
                if (heading.isEmpty()) return@forEach

                val cards = section.select(".film_list-wrap .flw-item, .film-poster").mapNotNull { card ->
                    val title = card.selectFirst(".film-name a, .dynamic-name, h3 a")?.text()?.trim()
                        ?: card.selectFirst("img")?.attr("alt")?.trim() ?: return@mapNotNull null
                    val link = card.selectFirst(".film-name a, a")?.attr("href")?.trim() ?: return@mapNotNull null
                    val poster = card.selectFirst("img")?.let { img ->
                        img.attr("data-src").ifEmpty { img.attr("src") }
                    }?.trim().orEmpty()
                    val fullUrl = if (link.startsWith("http")) link else "$baseUrl$link"

                    ScrapedMediaItem(
                        id = "${providerName}_${fullUrl.ifBlank { title }}",
                        title = title,
                        url = fullUrl,
                        posterUrl = poster,
                        backdropUrl = poster,
                        synopsis = title,
                        rating = "8.8",
                        quality = "HD",
                        year = "2026",
                        type = "Anime",
                        providerName = providerName
                    )
                }.distinctBy { it.url }

                if (cards.isNotEmpty()) {
                    categoryRows.add(ScrapedCategoryRow(categoryTitle = heading, items = cards))
                    if (heroBanner.isEmpty()) {
                        heroBanner.addAll(cards.take(6))
                    }
                }
            }

            HomePagePayload(heroBannerItems = heroBanner, categoryRows = categoryRows)
        } catch (t: Throwable) {
            Log.e("HiAnime", "getHomePage failed: ${t.message}", t)
            HomePagePayload()
        }
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val doc = fetchDoc("$baseUrl/search?keyword=$encoded")
            doc.select(".film_list-wrap .flw-item, .film-poster").mapNotNull { card ->
                val title = card.selectFirst(".film-name a, .dynamic-name, h3 a")?.text()?.trim()
                    ?: card.selectFirst("img")?.attr("alt")?.trim() ?: return@mapNotNull null
                val link = card.selectFirst(".film-name a, a")?.attr("href")?.trim() ?: return@mapNotNull null
                val poster = card.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifEmpty { img.attr("src") }
                }?.trim().orEmpty()
                val fullUrl = if (link.startsWith("http")) link else "$baseUrl$link"

                MediaItem(
                    title = title,
                    poster = poster,
                    url = fullUrl,
                    type = "anime"
                )
            }
        } catch (t: Throwable) {
            Log.e("HiAnime", "search failed: ${t.message}", t)
            emptyList()
        }
    }

    override suspend fun getDetails(url: String): MediaDetails = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc(url)
            val title = doc.selectFirst("h1.film-name, h1")?.text()?.trim().orEmpty()
            val synopsis = doc.selectFirst(".film-description .text, .item-list .text")?.text()?.trim().orEmpty()
            val servers = mutableListOf<ServerOption>()
            val episodes = mutableListOf<EpisodeItem>()

            // Extract embed iframes
            doc.select("iframe").forEachIndexed { idx, iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.trim()
                if (src.isNotEmpty() && src.startsWith("http") && !src.contains("googletag") && !src.contains("ad")) {
                    servers.add(
                        ServerOption(
                            id = "hianime_srv_$idx",
                            serverName = "HiAnime Stream #${idx + 1}",
                            embedUrl = src,
                            quality = "1080p HD",
                            referer = url
                        )
                    )
                }
            }

            // Extract episode buttons
            doc.select(".episodes-ul a.ep-item, .ss-list a").forEachIndexed { idx, a ->
                val epHref = a.attr("href").trim()
                val epTitle = a.attr("title").ifEmpty { a.text() }.trim()
                val fullEpHref = if (epHref.startsWith("http")) epHref else "$baseUrl$epHref"
                episodes.add(
                    EpisodeItem(
                        season = 1,
                        episode = idx + 1,
                        title = epTitle.ifEmpty { "Episode ${idx + 1}" },
                        url = fullEpHref,
                        thumbnailUrl = ""
                    )
                )
            }

            MediaDetails(
                title = title.ifEmpty { "HiAnime" },
                synopsis = synopsis,
                rating = "9.0",
                servers = servers,
                episodes = episodes,
                genres = listOf("Anime", "Action", "Japanese Animation", "Sub & Dub")
            )
        } catch (t: Throwable) {
            Log.e("HiAnime", "getDetails failed: ${t.message}", t)
            MediaDetails(title = "", synopsis = "", rating = "")
        }
    }
}
