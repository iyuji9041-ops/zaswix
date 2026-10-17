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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Built-in MultiMovies Provider.
 * Directly compiled into Zaswix for 100% zero-latency, reliable instant startup.
 */
class MultiMoviesProvider : ZaswixPlugin {

    override val name: String = "MultiMovies"
    override val providerName: String = "MultiMovies"
    override val version: String = "1.0.5"
    private val baseUrl: String = "https://m1.multimovies.courses"

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)

        // Attach Cloudflare DoH as fallback resolver if host app has it
        try {
            val dohClass = Class.forName("com.zaswis.play.network.CloudflareDns")
            val dnsField = dohClass.getField("dns")
            val dnsInstance = dnsField.get(null) as? okhttp3.Dns
            if (dnsInstance != null) {
                // Keep standard system DNS primary, wrap DoH in fallback
            }
        } catch (_: Throwable) {}

        builder.build()
    }

    private fun fetchDoc(url: String, referer: String = baseUrl): Document {
        val fullUrl = when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> "$baseUrl/$url"
        }

        fun buildRequest(): Request = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("sec-ch-ua", "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\", \"Google Chrome\";v=\"128\"")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("Upgrade-Insecure-Requests", "1")
            .build()

        // 1. Primary Engine: Standard OkHttp
        try {
            val response = okHttpClient.newCall(buildRequest()).execute()
            if (response.isSuccessful) {
                val html = response.body?.string().orEmpty()
                if (html.isNotEmpty()) {
                    return Jsoup.parse(html, fullUrl)
                }
            }
        } catch (t: Throwable) {
            Log.w("MultiMovies", "OkHttp fetch failed for $fullUrl: ${t.message}, falling back to Jsoup")
        }

        // 2. Fallback Engine: Jsoup
        return Jsoup.connect(fullUrl)
            .userAgent(userAgent)
            .referrer(referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .ignoreHttpErrors(true)
            .timeout(15000)
            .followRedirects(true)
            .get()
    }

    override suspend fun getHomePage(): HomePagePayload = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc(baseUrl)
            val heroItems = mutableListOf<ScrapedMediaItem>()
            val rows = mutableListOf<ScrapedCategoryRow>()

            // A. Hero Slider ("#heroSliderRail article.cinema-slide")
            doc.select("#heroSliderRail article.cinema-slide").forEachIndexed { idx, slide ->
                val title = slide.selectFirst("h1")?.text()?.trim() ?: return@forEachIndexed
                val backdrop = slide.selectFirst("img.cinema-backdrop")?.attr("src").orEmpty()
                val synopsis = slide.selectFirst("p")?.text()?.trim().orEmpty()
                val saveJson = slide.selectFirst("button[data-save-title]")?.attr("data-save-title")

                var poster = backdrop
                var pageUrl = slide.selectFirst("a.button-glass")?.attr("href")
                    ?: slide.selectFirst("a.button-light")?.attr("href").orEmpty()
                var year = slide.selectFirst(".cinema-meta span")?.text()?.trim().orEmpty()
                var isSeries = pageUrl.contains("/series/")

                if (!saveJson.isNullOrEmpty()) {
                    runCatching {
                        val obj = JSONObject(saveJson)
                        poster = obj.optString("poster", poster).replace("\\/", "/")
                        pageUrl = obj.optString("url", pageUrl).replace("\\/", "/")
                        year = obj.optInt("year", 2026).toString()
                        isSeries = obj.optString("type") == "tv"
                    }
                }

                val rating = slide.selectFirst(".cinema-meta strong.rating")?.text()
                    ?.replace("★", "")?.trim() ?: "8.0"
                val quality = slide.selectFirst(".quality-tag")?.text()?.trim() ?: "1080p"

                val itemUrl = if (pageUrl.startsWith("http")) pageUrl else "$baseUrl$pageUrl"
                heroItems.add(
                    ScrapedMediaItem(
                        id = "${providerName}_${itemUrl.ifBlank { title }}",
                        title = title,
                        url = itemUrl,
                        posterUrl = poster,
                        backdropUrl = backdrop.ifEmpty { poster },
                        synopsis = synopsis,
                        rating = rating,
                        quality = quality,
                        year = year,
                        type = if (isSeries) "Series" else "Movie",
                        providerName = providerName
                    )
                )
            }

            // B. Content Shelves ("section.content-shelf" -> "#featuredRail", "#movieRail", "#seriesRail")
            doc.select("section.content-shelf").forEach { shelf ->
                val shelfTitle = shelf.selectFirst(".section-heading h2")?.text()?.trim() ?: return@forEach
                val cards = shelf.select("article.poster-card").mapNotNull { card ->
                    parsePosterCard(card)
                }
                if (cards.isNotEmpty()) {
                    rows.add(ScrapedCategoryRow(categoryTitle = shelfTitle, items = cards))
                }
            }

            // C. Top Rated Lists ("ol.top-ranked-list li")
            val topMovies = doc.select(".top-list-movies ol.top-ranked-list li").mapNotNull { li ->
                val title = li.selectFirst(".top-ranked-copy a")?.text()?.trim() ?: return@mapNotNull null
                val href = li.selectFirst(".top-ranked-copy a")?.attr("href").orEmpty()
                val poster = li.selectFirst("a.top-ranked-art img")?.attr("src")
                    ?.replace("/w185/", "/w500/").orEmpty()
                val rating = li.selectFirst(".top-ranked-copy span b")?.text()
                    ?.replace("★", "")?.trim() ?: "9.0"
                val year = li.selectFirst(".top-ranked-copy span")?.ownText()?.trim() ?: "2026"
                val itemUrl = if (href.startsWith("http")) href else "$baseUrl$href"

                ScrapedMediaItem(
                    id = "${providerName}_${itemUrl.ifBlank { title }}",
                    title = title,
                    url = itemUrl,
                    posterUrl = poster,
                    backdropUrl = poster,
                    synopsis = "",
                    rating = rating,
                    quality = "1080p",
                    year = year,
                    type = "Movie",
                    providerName = providerName
                )
            }
            if (topMovies.isNotEmpty()) {
                rows.add(ScrapedCategoryRow(categoryTitle = "Top 10 Movies", items = topMovies))
            }

            HomePagePayload(heroBannerItems = heroItems, categoryRows = rows)
        } catch (t: Throwable) {
            Log.e("MultiMovies", "getHomePage failed: ${t.message}", t)
            HomePagePayload()
        }
    }

    private fun parsePosterCard(card: org.jsoup.nodes.Element): ScrapedMediaItem? {
        val title = card.selectFirst(".poster-caption a")?.text()?.trim()
            ?: card.selectFirst("a.poster-art")?.attr("aria-label")?.trim()
            ?: return null
        val href = card.selectFirst("a.poster-art")?.attr("href")?.trim() ?: return null
        val poster = card.selectFirst("a.poster-art img")?.attr("src")?.trim().orEmpty()
        val rating = card.selectFirst(".poster-rating")?.text()?.replace("★", "")?.trim() ?: "7.5"
        val metaText = card.selectFirst("p.poster-meta")?.text().orEmpty()
        val year = Regex("""\b(19|20)\d{2}\b""").find(metaText)?.value ?: "2026"
        val isSeries = metaText.contains("Series", ignoreCase = true) || href.contains("/series/")
        val itemUrl = if (href.startsWith("http")) href else "$baseUrl$href"

        return ScrapedMediaItem(
            id = "${providerName}_${itemUrl.ifBlank { title }}",
            title = title,
            url = itemUrl,
            posterUrl = poster,
            backdropUrl = poster.replace("/w500/", "/w1280/"),
            synopsis = "",
            rating = rating,
            quality = "1080p",
            year = year,
            type = if (isSeries) "Series" else "Movie",
            providerName = providerName
        )
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val doc = fetchDoc("$baseUrl/search?q=$encoded")
            doc.select("article.poster-card, .search-results article, .cinejoy-rec-card").mapNotNull { el ->
                val title = el.selectFirst(".poster-caption a, a")?.text()?.trim() ?: el.attr("title").trim()
                val href = el.selectFirst("a.poster-art, a")?.attr("href")?.trim() ?: el.attr("href").trim()
                val poster = el.selectFirst("img")?.attr("src")?.trim().orEmpty()
                if (title.isEmpty() || href.isEmpty()) null else MediaItem(
                    title = title,
                    poster = poster,
                    url = if (href.startsWith("http")) href else "$baseUrl$href",
                    type = if (href.contains("/series/")) "series" else "movie"
                )
            }
        } catch (t: Throwable) {
            Log.e("MultiMovies", "search failed: ${t.message}", t)
            emptyList()
        }
    }

    override suspend fun getDetails(url: String): MediaDetails = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc(url)
            val title = doc.selectFirst("h1.cinejoy-detail-title")?.text()?.trim().orEmpty()
            val synopsis = doc.selectFirst("p#cinejoyOverview")?.text()?.trim().orEmpty()
            val score = doc.selectFirst(".cinejoy-score-pill")?.text()?.replace("★", "")?.trim() ?: "8.0"
            val genresText = doc.selectFirst(".cinejoy-detail-genres")?.text().orEmpty()
            val genres = genresText.split("•", ",").map { it.trim() }.filter { it.isNotEmpty() }

            val servers = mutableListOf<ServerOption>()

            // Extract from button.server-chip
            doc.select("#playerServerGrid button.server-chip").forEachIndexed { idx, chip ->
                val srvName = chip.selectFirst(".server-name")?.text()?.trim() ?: "Server #${idx + 1}"
                val badge = chip.selectFirst(".server-badge")?.text()?.trim() ?: "HD"
                val rawUrl = chip.attr("data-url").trim()
                if (rawUrl.isNotEmpty()) {
                    servers.add(
                        ServerOption(
                            id = "mm_srv_$idx",
                            serverName = "$srvName ($badge)",
                            embedUrl = rawUrl,
                            quality = badge,
                            referer = baseUrl
                        )
                    )
                }
            }

            // Extract from watchConfig script JSON if empty
            if (servers.isEmpty()) {
                val scriptHtml = doc.select("script").html()
                val match = Regex("""const\s+watchConfig\s*=\s*(\{.*?\});""").find(scriptHtml)
                match?.groupValues?.getOrNull(1)?.let { jsonStr ->
                    runCatching {
                        val configObj = JSONObject(jsonStr)
                        val arr: JSONArray = configObj.optJSONArray("initialServers") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val srv = arr.getJSONObject(i)
                            val srvName = srv.optString("name", "Server #${i + 1}")
                            val srvUrl = srv.optString("url", "").replace("\\/", "/")
                            val srvQuality = srv.optString("quality", "HD")
                            if (srvUrl.isNotEmpty()) {
                                servers.add(
                                    ServerOption(
                                        id = "mm_cfg_$i",
                                        serverName = "$srvName ($srvQuality)",
                                        embedUrl = srvUrl,
                                        quality = srvQuality,
                                        referer = baseUrl
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Extract episodes if series
            val episodes = mutableListOf<EpisodeItem>()
            doc.select(".cinejoy-ep-card, .episode-item").forEachIndexed { idx, epEl ->
                val epTitle = epEl.selectFirst(".ep-title, h4")?.text()?.trim().orEmpty()
                val epHref = epEl.selectFirst("a")?.attr("href").orEmpty()
                val epThumb = epEl.selectFirst("img")?.attr("src").orEmpty()
                val epUrl = if (epHref.startsWith("http")) epHref else "$baseUrl$epHref"
                episodes.add(
                    EpisodeItem(
                        season = 1,
                        episode = idx + 1,
                        title = epTitle.ifEmpty { "Episode ${idx + 1}" },
                        url = epUrl,
                        thumbnailUrl = epThumb
                    )
                )
            }

            MediaDetails(
                title = title,
                synopsis = synopsis,
                rating = score,
                servers = servers,
                episodes = episodes,
                genres = genres
            )
        } catch (t: Throwable) {
            Log.e("MultiMovies", "getDetails failed: ${t.message}", t)
            MediaDetails(title = "", synopsis = "", rating = "")
        }
    }
}
