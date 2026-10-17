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
 * Built-in VegaMovies Provider.
 * Official Bollywood, Hollywood Hindi Dubbed, Netflix/Prime/Hotstar Web-DL movies and series.
 * Features automatic resolution of NexDrive, VegaDrive, V-Cloud, and FastDL GoogleUserContent streams.
 */
class VegaMoviesProvider : ZaswixPlugin {

    override val name: String = "VegaMovies"
    override val providerName: String = "VegaMovies"
    override val version: String = "1.0.0"
    private val baseUrl: String = "https://new1.vegamovies.lease"

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
            Log.w("VegaMovies", "OkHttp failed for $fullUrl: ${t.message}, trying Jsoup")
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
            val doc = fetchDoc(baseUrl)
            val allCards = doc.select(".poster-card, article.post").mapNotNull { card ->
                parsePosterCard(card)
            }

            if (allCards.isEmpty()) return@withContext HomePagePayload()

            val heroItems = allCards.take(8)
            val latestItems = allCards.take(16)
            val dualAudioItems = allCards.filter { it.title.contains("Dual", ignoreCase = true) || it.title.contains("Hindi", ignoreCase = true) }
            val seriesItems = allCards.filter { it.type == "Series" || it.title.contains("Season", ignoreCase = true) }

            val rows = mutableListOf<ScrapedCategoryRow>()
            if (latestItems.isNotEmpty()) {
                rows.add(ScrapedCategoryRow(categoryTitle = "Latest Releases", items = latestItems))
            }
            if (dualAudioItems.isNotEmpty()) {
                rows.add(ScrapedCategoryRow(categoryTitle = "Hindi Dubbed & Dual Audio", items = dualAudioItems))
            }
            if (seriesItems.isNotEmpty()) {
                rows.add(ScrapedCategoryRow(categoryTitle = "Web Series & TV Shows", items = seriesItems))
            }
            if (allCards.size > 16) {
                rows.add(ScrapedCategoryRow(categoryTitle = "Trending Blockbusters", items = allCards.drop(16).take(16)))
            }

            HomePagePayload(heroBannerItems = heroItems, categoryRows = rows)
        } catch (t: Throwable) {
            Log.e("VegaMovies", "getHomePage failed: ${t.message}", t)
            HomePagePayload()
        }
    }

    private fun parsePosterCard(card: org.jsoup.nodes.Element): ScrapedMediaItem? {
        val linkEl = card.selectFirst("a[href]") ?: card.parent()?.takeIf { it.tagName() == "a" }
        val href = card.selectFirst("meta[itemprop=url]")?.attr("content")
            ?: linkEl?.attr("href")?.trim().orEmpty()
        if (href.isEmpty() || !href.startsWith("http")) return null

        val title = card.selectFirst("p.poster-title, .entry-title")?.text()?.trim()
            ?: card.selectFirst("img")?.attr("alt")?.trim() ?: return null

        val poster = card.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }?.trim().orEmpty()

        val quality = card.selectFirst(".poster-quality, .quality")?.text()?.trim() ?: "1080p HD"
        val isSeries = title.contains("Season", ignoreCase = true) || href.contains("season", ignoreCase = true)

        val cleanTitle = title.replace(Regex("""Download\s*\[.*?\]\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""Download\s*""", RegexOption.IGNORE_CASE), "")
            .trim()

        return ScrapedMediaItem(
            id = "${providerName}_${href.ifBlank { title }}",
            title = cleanTitle,
            url = href,
            posterUrl = poster,
            backdropUrl = poster,
            synopsis = cleanTitle,
            rating = "8.2",
            quality = quality,
            year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value ?: "2026",
            type = if (isSeries) "Series" else "Movie",
            providerName = providerName
        )
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val doc = fetchDoc("$baseUrl/?s=$encoded")
            doc.select(".poster-card, article.post").mapNotNull { card ->
                val linkEl = card.selectFirst("a[href]") ?: card.parent()?.takeIf { it.tagName() == "a" }
                val href = card.selectFirst("meta[itemprop=url]")?.attr("content")
                    ?: linkEl?.attr("href")?.trim().orEmpty()
                if (href.isEmpty()) return@mapNotNull null

                val title = card.selectFirst("p.poster-title, .entry-title")?.text()?.trim()
                    ?: card.selectFirst("img")?.attr("alt")?.trim() ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifEmpty { img.attr("src") }
                }?.trim().orEmpty()

                MediaItem(
                    title = title,
                    poster = poster,
                    url = href,
                    type = if (title.contains("Season", ignoreCase = true)) "series" else "movie"
                )
            }
        } catch (t: Throwable) {
            Log.e("VegaMovies", "search failed: ${t.message}", t)
            emptyList()
        }
    }

    override suspend fun getDetails(url: String): MediaDetails = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDoc(url)
            val title = doc.selectFirst("h1.entry-title, .post-title, meta[property='og:title']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.replace("Download", "")?.trim().orEmpty()

            val synopsis = doc.selectFirst(".entry-content p")?.text()?.trim().orEmpty()
            val servers = mutableListOf<ServerOption>()
            val episodes = mutableListOf<EpisodeItem>()

            // 1. Find NexDrive and direct download/stream links in the page
            val links = doc.select("a[href*='nexdrive'], a[href*='fastdl'], a[href*='vcloud'], a[href*='vegadrive']")
            links.forEachIndexed { idx, a ->
                val rawLink = a.attr("href").trim()
                val buttonText = a.text().trim().ifEmpty { "Server #${idx + 1}" }

                val parentText = (a.parent()?.previousElementSibling()?.text().orEmpty() + " " +
                        a.parent()?.text().orEmpty() + " " + buttonText).lowercase()
                val detectedQuality = when {
                    parentText.contains("2160p") || parentText.contains("4k") -> "4K 2160p"
                    parentText.contains("1080p") -> "1080p Full HD"
                    parentText.contains("720p") -> "720p HD"
                    parentText.contains("480p") -> "480p SD"
                    else -> "HD Direct"
                }

                if (rawLink.contains("fastdl.beer") || rawLink.contains("fastdl")) {
                    // FastDL direct stream resolver
                    val fastdlDoc = runCatching { fetchDoc(rawLink) }.getOrNull()
                    val directVideoUrl = fastdlDoc?.selectFirst("a#vd")?.attr("href").orEmpty()
                    if (directVideoUrl.isNotEmpty()) {
                        servers.add(
                            ServerOption(
                                id = "fastdl_$idx",
                                serverName = "⚡ G-Direct • $detectedQuality",
                                embedUrl = directVideoUrl,
                                quality = detectedQuality,
                                referer = rawLink
                            )
                        )
                    } else {
                        servers.add(
                            ServerOption(
                                id = "fastdl_raw_$idx",
                                serverName = "$buttonText ($detectedQuality)",
                                embedUrl = rawLink,
                                quality = detectedQuality,
                                referer = url
                            )
                        )
                    }
                } else if (rawLink.contains("nexdrive")) {
                    // Resolve NexDrive episode hub
                    val hubDoc = runCatching { fetchDoc(rawLink) }.getOrNull()
                    if (hubDoc != null) {
                        // Check if hub contains episodes
                        val epElements = hubDoc.select("h4:matches((?i)episode), p:matches((?i)episode)")
                        if (epElements.isNotEmpty() && episodes.isEmpty()) {
                            epElements.forEachIndexed { epIdx, epEl ->
                                val epNum = Regex("""\d+""").find(epEl.text())?.value?.toIntOrNull() ?: (epIdx + 1)
                                val epA = epEl.nextElementSibling()?.selectFirst("a[href]")
                                    ?: epEl.parent()?.selectFirst("a[href]")
                                val epHref = epA?.attr("href")?.trim().orEmpty()
                                if (epHref.isNotEmpty()) {
                                    episodes.add(
                                        EpisodeItem(
                                            season = 1,
                                            episode = epNum,
                                            title = "Episode $epNum ($detectedQuality)",
                                            url = epHref
                                        )
                                    )
                                }
                            }
                        }

                        // Extract G-Direct FastDL, V-Cloud, V-Drive buttons inside hub
                        hubDoc.select("a[href*='fastdl'], a[href*='vcloud'], a[href*='vegadrive'], a[href*='filebee']").forEachIndexed { hIdx, hubA ->
                            val hubHref = hubA.attr("href").trim()
                            val hubLabel = hubA.text().trim()

                            if (hubHref.contains("fastdl")) {
                                val fdDoc = runCatching { fetchDoc(hubHref) }.getOrNull()
                                val vd = fdDoc?.selectFirst("a#vd")?.attr("href").orEmpty()
                                if (vd.isNotEmpty()) {
                                    servers.add(
                                        ServerOption(
                                            id = "hub_vd_${idx}_$hIdx",
                                            serverName = "⚡ G-Direct • $detectedQuality ${if (hubLabel.isNotBlank()) "($hubLabel)" else ""}",
                                            embedUrl = vd,
                                            quality = detectedQuality,
                                            referer = hubHref
                                        )
                                    )
                                }
                            }

                            servers.add(
                                ServerOption(
                                    id = "hub_${idx}_$hIdx",
                                    serverName = "${hubLabel.ifEmpty { "Server #${hIdx + 1}" }} • $detectedQuality",
                                    embedUrl = hubHref,
                                    quality = detectedQuality,
                                    referer = rawLink
                                )
                            )
                        }
                    }
                } else {
                    servers.add(
                        ServerOption(
                            id = "srv_$idx",
                            serverName = "$buttonText ($detectedQuality)",
                            embedUrl = rawLink,
                            quality = detectedQuality,
                            referer = url
                        )
                    )
                }
            }

            MediaDetails(
                title = title.ifEmpty { "VegaMovies Media" },
                synopsis = synopsis,
                rating = "8.2",
                servers = servers.distinctBy { it.embedUrl },
                episodes = episodes,
                genres = listOf("Bollywood", "Hollywood", "Hindi Dubbed", "Web Series")
            )
        } catch (t: Throwable) {
            Log.e("VegaMovies", "getDetails failed: ${t.message}", t)
            MediaDetails(title = "", synopsis = "", rating = "")
        }
    }
}
