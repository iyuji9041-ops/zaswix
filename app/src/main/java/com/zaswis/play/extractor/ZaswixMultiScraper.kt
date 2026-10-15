package com.zaswis.play.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

// Unified Models jo tumhare HomeScreen aur HeroBanner ko feed karenge
data class ScrapedMediaItem(
    val id: String,
    val title: String,
    val url: String,
    val posterUrl: String,
    val backdropUrl: String = posterUrl,
    val synopsis: String = "",
    val rating: String = "",
    val quality: String = "HD",
    val year: String = "",
    val type: String = "Movie", // Movie, Series, Anime
    val providerName: String
)

data class ScrapedCategoryRow(
    val categoryTitle: String,
    val items: List<ScrapedMediaItem>
)

data class HomePagePayload(
    val heroBannerItems: List<ScrapedMediaItem>,
    val categoryRows: List<ScrapedCategoryRow>
)

object ZaswixMultiScraper {

    // OkHttpClient with Browser User-Agent taaki sites 403 block na karein
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    // Helper: URL se HTML fetch karke Jsoup Document banana
    private suspend fun fetchDocument(url: String): Document? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val html = response.body?.string() ?: return@withContext null
                Jsoup.parse(html, url) // baseUri pass karne se abs:href kaam karta hai
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Helper: Lazy-loaded images (VegaMovies data-src) aur normal src dono ko handle karne ke liye
    private fun Element.extractImageUrl(baseUrl: String): String {
        val raw = this.attr("data-src").ifEmpty {
            this.attr("data-lazy-src").ifEmpty {
                this.attr("src")
            }
        }
        return when {
            raw.startsWith("data:image") -> this.attr("data-src") // Base64 placeholder skip karo
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "${baseUrl.trimEnd('/')}$raw"
            raw.isNotEmpty() -> "${baseUrl.trimEnd('/')}/$raw"
            else -> ""
        }.replace("//jFGqk", "/jFGqk") // Fix ToonStream double slash typo in TMDB URLs
    }

    // ========================================================================
    // 1. MULTIMOVIES SCRAPER (https://multimovies.garden) - Full Netflix Style
    // ========================================================================
    suspend fun scrapeMultiMovies(baseUrl: String = "https://multimovies.garden"): HomePagePayload {
        val doc = fetchDocument(baseUrl) ?: return HomePagePayload(emptyList(), emptyList())
        return parseMultiMoviesHtml(doc, baseUrl)
    }

    fun parseMultiMoviesHtml(doc: Document, baseUrl: String = "https://multimovies.garden"): HomePagePayload {
        val heroList = mutableListOf<ScrapedMediaItem>()
        val rows = mutableListOf<ScrapedCategoryRow>()

        // A. Hero Slider: #heroSliderRail > article.cinema-slide
        doc.select("#heroSliderRail > article.cinema-slide").forEachIndexed { index, slide ->
            val title = slide.selectFirst(".cinema-hero-copy h1")?.text()?.trim().orEmpty()
            val backdrop = slide.selectFirst("img.cinema-backdrop")?.extractImageUrl(baseUrl).orEmpty()
            val synopsis = slide.selectFirst(".cinema-hero-copy > p")?.text()?.trim().orEmpty()
            val rating = slide.selectFirst(".cinema-meta .rating")?.text()?.replace("★", "")?.trim().orEmpty()
            val quality = slide.selectFirst(".cinema-meta .quality-tag")?.text()?.trim()?.ifEmpty { "1080p" } ?: "1080p"
            val linkEl = slide.selectFirst(".hero-cta a.button-glass") ?: slide.selectFirst(".hero-cta a.button-light")
            val link = linkEl?.attr("abs:href").orEmpty()

            if (title.isNotEmpty() && link.isNotEmpty()) {
                heroList.add(
                    ScrapedMediaItem(
                        id = "mm_hero_$index",
                        title = title,
                        url = link,
                        posterUrl = backdrop.replace("/original/", "/w500/"),
                        backdropUrl = backdrop,
                        synopsis = synopsis,
                        rating = rating,
                        quality = quality,
                        providerName = "MultiMovies"
                    )
                )
            }
        }

        // B. Horizontal Poster Shelves: #featuredRail, #movieRail, #seriesRail
        doc.select("section.content-shelf").forEach { shelf ->
            val rowTitle = shelf.selectFirst(".section-heading h2")?.text()?.trim().orEmpty()
            val cards = shelf.select(".poster-rail > article.poster-card")
            if (rowTitle.isNotEmpty() && cards.isNotEmpty()) {
                val items = cards.mapIndexedNotNull { idx, card ->
                    val title = card.selectFirst(".poster-caption a")?.text()?.trim().orEmpty()
                    val link = card.selectFirst("a.poster-art")?.attr("abs:href").orEmpty()
                    val poster = card.selectFirst("a.poster-art img")?.extractImageUrl(baseUrl).orEmpty()
                    val rating = card.selectFirst(".poster-rating")?.text()?.replace("★", "")?.trim().orEmpty()
                    val metaText = card.selectFirst(".poster-meta")?.text().orEmpty()
                    val year = metaText.substringBefore("·").trim()
                    val type = metaText.substringAfter("·", "Movie").trim()

                    if (title.isEmpty() || link.isEmpty()) null else ScrapedMediaItem(
                        id = "mm_${rowTitle}_$idx",
                        title = title,
                        url = link,
                        posterUrl = poster,
                        rating = rating,
                        year = year,
                        type = type,
                        providerName = "MultiMovies"
                    )
                }
                if (items.isNotEmpty()) rows.add(ScrapedCategoryRow(rowTitle, items))
            }
        }

        // C. Latest Episodes Rail: .episode-rail > a.episode-preview
        val episodeCards = doc.select(".episode-rail > a.episode-preview")
        if (episodeCards.isNotEmpty()) {
            val epItems = episodeCards.mapIndexedNotNull { idx, ep ->
                val showTitle = ep.selectFirst("h3")?.text()?.trim().orEmpty()
                val epName = ep.selectFirst("p")?.text()?.trim().orEmpty()
                val badge = ep.selectFirst("div > span")?.text()?.trim().orEmpty()
                val img = ep.selectFirst("img")?.extractImageUrl(baseUrl).orEmpty()
                val link = ep.attr("abs:href")

                if (showTitle.isEmpty()) null else ScrapedMediaItem(
                    id = "mm_ep_$idx",
                    title = "$showTitle ($badge)",
                    synopsis = epName,
                    url = link,
                    posterUrl = img,
                    backdropUrl = img,
                    quality = badge,
                    type = "Episode",
                    providerName = "MultiMovies"
                )
            }
            if (epItems.isNotEmpty()) rows.add(ScrapedCategoryRow("Latest Episodes", epItems))
        }

        // D. Top 10 Movies & Top 10 Shows: .top-list-panel
        doc.select(".top-list-panel").forEach { panel ->
            val heading = panel.selectFirst(".section-heading h2")?.text()?.trim().orEmpty()
            val listItems = panel.select("ol.top-ranked-list > li")
            val rankedItems = listItems.mapIndexedNotNull { idx, li ->
                val title = li.selectFirst(".top-ranked-copy a")?.text()?.trim().orEmpty()
                val link = li.selectFirst(".top-ranked-copy a")?.attr("abs:href").orEmpty()
                val poster = li.selectFirst("a.top-ranked-art img")?.extractImageUrl(baseUrl)
                    ?.replace("/w185/", "/w500/").orEmpty()
                val rating = li.selectFirst(".top-ranked-copy b")?.text()?.replace("★", "")?.trim().orEmpty()

                if (title.isEmpty()) null else ScrapedMediaItem(
                    id = "mm_top_${heading}_$idx",
                    title = "#${idx + 1} $title",
                    url = link,
                    posterUrl = poster,
                    rating = rating,
                    providerName = "MultiMovies"
                )
            }
            if (rankedItems.isNotEmpty()) rows.add(ScrapedCategoryRow(heading, rankedItems))
        }

        return HomePagePayload(heroList, rows)
    }

    // ========================================================================
    // 2. VEGAMOVIES SCRAPER (https://vegamovies.gallery) - Handles data-src
    // ========================================================================
    suspend fun scrapeVegaMovies(baseUrl: String = "https://vegamovies.gallery"): HomePagePayload {
        val doc = fetchDocument(baseUrl) ?: return HomePagePayload(emptyList(), emptyList())
        return parseVegaMoviesHtml(doc, baseUrl)
    }

    fun parseVegaMoviesHtml(doc: Document, baseUrl: String = "https://vegamovies.gallery"): HomePagePayload {
        val heroList = mutableListOf<ScrapedMediaItem>()

        // A. Hero Slider: .hsl-track > .hsl-slide
        doc.select(".hsl-track > .hsl-slide").forEachIndexed { idx, slide ->
            val title = slide.selectFirst(".hsl-title a")?.text()?.trim().orEmpty()
            val link = slide.selectFirst("a.hsl-btn")?.attr("abs:href")
                ?: slide.selectFirst(".hsl-title a")?.attr("abs:href").orEmpty()
            // VegaMovies रखता है असली फोटो data-src में
            val backdrop = slide.selectFirst(".hsl-bg img")?.extractImageUrl(baseUrl).orEmpty()
            val rating = slide.selectFirst(".hsl-idx-imdb")?.text()?.replace("⭐", "")?.trim().orEmpty()
            val quality = slide.selectFirst(".hsl-idx-quality")?.text()?.trim().orEmpty()
            val desc = slide.selectFirst(".hsl-desc p")?.text()?.trim().orEmpty()

            if (title.isNotEmpty() && link.isNotEmpty()) {
                heroList.add(
                    ScrapedMediaItem(
                        id = "vega_hero_$idx",
                        title = title,
                        url = link,
                        posterUrl = backdrop,
                        backdropUrl = backdrop,
                        synopsis = desc,
                        rating = rating,
                        quality = quality,
                        providerName = "VegaMovies"
                    )
                )
            }
        }

        // B. Main Movies Grid: #moviesGridMain > a
        val allCards = doc.select("#moviesGridMain > a").mapIndexedNotNull { idx, aTag ->
            val link = aTag.attr("abs:href")
            val rawTitle = aTag.selectFirst("p.poster-title")?.text()?.trim().orEmpty()
            val cleanTitle = rawTitle.removePrefix("Download ").substringBefore(" (").substringBefore(" {").trim()
            val poster = aTag.selectFirst(".poster-image img")?.extractImageUrl(baseUrl).orEmpty()
            val quality = aTag.selectFirst(".poster-quality")?.text()?.trim().orEmpty()
            val rating = aTag.selectFirst(".imdb-score")?.text()?.replace("⭐", "")?.substringBefore("/")?.trim().orEmpty()

            if (cleanTitle.isEmpty() || link.isEmpty()) null else ScrapedMediaItem(
                id = "vega_card_$idx",
                title = cleanTitle,
                synopsis = rawTitle,
                url = link,
                posterUrl = poster,
                rating = rating,
                quality = quality,
                providerName = "VegaMovies"
            )
        }

        // 20 items ko Netflix style rows mein split karna taaki homepage bhara hua dikhe
        val rows = mutableListOf<ScrapedCategoryRow>()
        if (allCards.isNotEmpty()) {
            rows.add(ScrapedCategoryRow("Latest Releases", allCards.take(10)))
            if (allCards.size > 10) {
                rows.add(ScrapedCategoryRow("Dual Audio & Series", allCards.drop(10)))
            }
            val topRated = allCards.filter { (it.rating.toDoubleOrNull() ?: 0.0) >= 7.5 }
            if (topRated.isNotEmpty()) {
                rows.add(ScrapedCategoryRow("Top Rated on Vega", topRated))
            }
        }

        return HomePagePayload(heroList, rows)
    }

    // ========================================================================
    // 3. MULTINIME SCRAPER (https://multinime.site) - Extracts CSS Background
    // ========================================================================
    suspend fun scrapeMultinime(baseUrl: String = "https://multinime.site"): HomePagePayload {
        val doc = fetchDocument(baseUrl) ?: return HomePagePayload(emptyList(), emptyList())
        return parseMultinimeHtml(doc, baseUrl)
    }

    fun parseMultinimeHtml(doc: Document, baseUrl: String = "https://multinime.site"): HomePagePayload {
        val heroList = mutableListOf<ScrapedMediaItem>()
        val bgRegex = Pattern.compile("""url\(['"]?(https?://[^'")]+)['"]?\)""")

        // A. Spotlight Hero Slider: #heroRail > .spotlight-card.hero-cell
        doc.select("#heroRail > .spotlight-card.hero-cell").forEachIndexed { idx, cell ->
            // Duplicate clone slides ko skip karne ke liye aria-hidden check
            if (cell.attr("aria-hidden") == "true") return@forEachIndexed

            val styleAttr = cell.attr("style")
            val matcher = bgRegex.matcher(styleAttr)
            val backdrop = if (matcher.find()) matcher.group(1).orEmpty() else ""

            val watchLink = cell.selectFirst("a.btn-primary")?.attr("abs:href").orEmpty()
            val rawTitle = cell.selectFirst("h1.spotlight-title")?.text()?.trim().orEmpty()
            // Fallback: Agar h1 khali hai (jaise slide #8 mein), toh slug se title nikalo
            val title = rawTitle.ifEmpty {
                watchLink.substringAfter("slug=").substringBefore("&")
                    .split("-").dropLast(1).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            }
            val synopsis = cell.selectFirst("p.spotlight-synopsis")?.text()?.trim().orEmpty()
            val rating = cell.selectFirst(".score-star")?.text()?.trim().orEmpty()

            if (title.isNotEmpty() && watchLink.isNotEmpty()) {
                heroList.add(
                    ScrapedMediaItem(
                        id = "mn_hero_$idx",
                        title = title,
                        url = watchLink,
                        posterUrl = backdrop,
                        backdropUrl = backdrop,
                        synopsis = synopsis,
                        rating = rating,
                        type = "Anime",
                        providerName = "Multinime"
                    )
                )
            }
        }

        // B. All 6 Anime Rows (#latest, #airing, #top10, #trending, #most-watched, #updated)
        val rows = mutableListOf<ScrapedCategoryRow>()
        doc.select("section.row-section").forEach { section ->
            val sectionTitle = section.selectFirst("h2.section-title")?.text()?.trim().orEmpty()
            val cards = section.select("a.anime-card")

            val items = cards.mapIndexedNotNull { idx, card ->
                val link = card.attr("abs:href")
                val imgEl = card.selectFirst("img.anime-poster")
                val poster = imgEl?.extractImageUrl(baseUrl).orEmpty()
                val nameText = card.selectFirst(".anime-name")?.text()?.trim().orEmpty()
                val altText = imgEl?.attr("alt")?.trim().orEmpty()
                val slugFallback = link.substringAfter("slug=").substringBefore("&")
                    .split("-").dropLast(1).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

                val finalTitle = nameText.ifEmpty { altText.ifEmpty { slugFallback } }
                val badgeEp = card.selectFirst(".badge-ep")?.text()?.trim().orEmpty()

                if (finalTitle.isEmpty() || link.isEmpty()) null else ScrapedMediaItem(
                    id = "mn_${section.id()}_$idx",
                    title = finalTitle,
                    url = link,
                    posterUrl = poster,
                    quality = badgeEp.ifEmpty { "SUB" },
                    type = "Anime",
                    providerName = "Multinime"
                )
            }
            if (sectionTitle.isNotEmpty() && items.isNotEmpty()) {
                rows.add(ScrapedCategoryRow(sectionTitle, items))
            }
        }

        return HomePagePayload(heroList, rows)
    }

    // ========================================================================
    // 4. TOONSTREAM SCRAPER (https://toonstream.us/home) - 7 Swiper Sections
    // ========================================================================
    suspend fun scrapeToonStream(baseUrl: String = "https://toonstream.us"): HomePagePayload {
        val doc = fetchDocument("$baseUrl/home") ?: return HomePagePayload(emptyList(), emptyList())
        return parseToonStreamHtml(doc, baseUrl)
    }

    fun parseToonStreamHtml(doc: Document, baseUrl: String = "https://toonstream.us"): HomePagePayload {
        val rows = mutableListOf<ScrapedCategoryRow>()

        doc.select("section.watch_history_widget").forEach { section ->
            val title = section.selectFirst("h3.section-title")?.text()?.trim().orEmpty()
            if (title.equals("Watch History", ignoreCase = true)) return@forEach // LocalStorage empty widget skip karo

            val articles = section.select(".swiper-slide article.post")
            val items = articles.mapIndexedNotNull { idx, article ->
                val itemTitle = article.selectFirst("h2.entry-title")?.text()?.trim().orEmpty()
                val link = article.selectFirst("a.lnk-blk")?.attr("abs:href").orEmpty()
                val poster = article.selectFirst(".post-thumbnail img")?.extractImageUrl(baseUrl).orEmpty()
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
                    providerName = "ToonStream"
                )
            }
            if (title.isNotEmpty() && items.isNotEmpty()) {
                rows.add(ScrapedCategoryRow(title, items))
            }
        }

        val heroItems = rows.firstOrNull()?.items?.take(6).orEmpty()
        return HomePagePayload(heroItems, rows)
    }

    // ========================================================================
    // 5. BOLLYFLIX SCRAPER (https://bollyflix.photo) - WordPress Post Cards
    // ========================================================================
    suspend fun scrapeBollyFlix(baseUrl: String = "https://bollyflix.photo"): HomePagePayload {
        val doc = fetchDocument(baseUrl) ?: return HomePagePayload(emptyList(), emptyList())
        return parseBollyFlixHtml(doc, baseUrl)
    }

    fun parseBollyFlixHtml(doc: Document, baseUrl: String = "https://bollyflix.photo"): HomePagePayload {
        val allPosts = doc.select(".post-cards > article.latestPost").mapIndexedNotNull { idx, article ->
            val aTag = article.selectFirst("h2.title a") ?: article.selectFirst("a.post-image")
            val rawTitle = aTag?.text()?.trim().orEmpty()
            val cleanTitle = rawTitle.removePrefix("Download ").substringBefore(" (").substringBefore(" Dual Audio").trim()
            val link = aTag?.attr("abs:href").orEmpty()
            val poster = article.selectFirst(".featured-thumbnail img")?.extractImageUrl(baseUrl).orEmpty()
            val quality = when {
                rawTitle.contains("2160p", true) -> "4K"
                rawTitle.contains("1080p", true) -> "1080p"
                rawTitle.contains("BluRay", true) -> "BluRay"
                else -> "WEB-DL"
            }

            if (cleanTitle.isEmpty() || link.isEmpty()) null else ScrapedMediaItem(
                id = "bf_$idx",
                title = cleanTitle,
                synopsis = rawTitle,
                url = link,
                posterUrl = poster,
                backdropUrl = poster,
                quality = quality,
                providerName = "BollyFlix"
            )
        }

        val heroItems = allPosts.take(5)
        val rows = mutableListOf<ScrapedCategoryRow>()
        if (allPosts.isNotEmpty()) {
            rows.add(ScrapedCategoryRow("Trending on BollyFlix", allPosts.take(8)))
            val seriesList = allPosts.filter { it.synopsis.contains("Series", true) || it.synopsis.contains("Season", true) }
            if (seriesList.isNotEmpty()) rows.add(ScrapedCategoryRow("Web Series & K-Dramas", seriesList))
            val moviesList = allPosts.filter { !it.synopsis.contains("Series", true) }
            if (moviesList.isNotEmpty()) rows.add(ScrapedCategoryRow("Latest Movies", moviesList))
        }

        return HomePagePayload(heroItems, rows)
    }
}
