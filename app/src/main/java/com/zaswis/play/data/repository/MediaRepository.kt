package com.zaswis.play.data.repository

import com.zaswis.play.data.model.Episode
import com.zaswis.play.data.model.MediaCategory
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.model.MediaType
import com.zaswis.play.data.model.StreamSource
import com.zaswis.play.extractor.CloudstreamProvider
import com.zaswis.play.extractor.ExtractorRegistry
import com.zaswis.play.extractor.HomePagePayload
import com.zaswis.play.extractor.ScrapedCategoryRow
import com.zaswis.play.extractor.ScrapedMediaItem
import com.zaswis.play.extractor.ZaswixMultiScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class MediaRepository {

    val extractorRegistry = ExtractorRegistry()

    suspend fun resolveLiveStreams(embedUrl: String): List<StreamSource> {
        return extractorRegistry.extract(embedUrl)
    }

    suspend fun extractFromHtml(html: String, baseUrl: String): List<StreamSource> {
        return extractorRegistry.extractFromHtml(html, baseUrl)
    }

    suspend fun autoResolveMediaStreams(mediaItem: MediaItem): List<StreamSource> = withContext(Dispatchers.IO) {
        val extractedSources = mutableListOf<StreamSource>()

        // 1. If media has direct embed URLs, resolve them concurrently
        if (mediaItem.embedUrls.isNotEmpty()) {
            val resolved = extractorRegistry.extractAll(mediaItem.embedUrls)
            extractedSources.addAll(resolved)
        }

        // 2. If media has pageUrl, resolve live with Cloudstream Provider scraper
        if (mediaItem.pageUrl.isNotEmpty()) {
            try {
                if (extractorRegistry.canHandle(mediaItem.pageUrl)) {
                    val directResolved = extractorRegistry.extract(mediaItem.pageUrl)
                    extractedSources.addAll(directResolved)
                } else {
                    val resolvedFromPage = CloudstreamProvider.resolveMoviePage(mediaItem.pageUrl, extractorRegistry)
                    extractedSources.addAll(resolvedFromPage)
                }
            } catch (e: Exception) {
                // Ignore network errors
            }
        }

        // 3. Merge extracted live streams with verified stream sources (never dummy streams)
        val finalSources = (extractedSources + mediaItem.streamSources).filter { it.url.isNotBlank() }

        finalSources.distinctBy { it.url }
    }

    suspend fun searchLiveVegaMovies(query: String): List<MediaItem> {
        return try {
            CloudstreamProvider.search(query)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * StateFlow exposing live heroBannerItems combining top featured hero slides
     */
    private val _heroBannerItems = MutableStateFlow<List<MediaItem>>(getDefaultHeroBannerItems())
    val heroBannerItems: StateFlow<List<MediaItem>> = _heroBannerItems.asStateFlow()

    fun getHeroBannerItems(): List<MediaItem> = _heroBannerItems.value

    /**
     * Featured hero fallback for single-hero banner consumers
     */
    fun getFeaturedHero(pluginId: String = "all"): MediaItem {
        val heroes = _heroBannerItems.value.ifEmpty { getDefaultHeroBannerItems() }
        return when (pluginId.lowercase()) {
            "bollyflix" -> heroes.find { it.providerName.contains("Bolly", true) } ?: heroes[0]
            "toonstream" -> heroes.find { it.providerName.contains("Toon", true) } ?: heroes.getOrElse(3) { heroes[0] }
            "vegamovies" -> heroes.find { it.providerName.contains("Vega", true) } ?: heroes.getOrElse(1) { heroes[0] }
            "multimovies" -> heroes.find { it.providerName.contains("MultiMovies", true) } ?: heroes[0]
            "multinime" -> heroes.find { it.providerName.contains("MultiNime", true) } ?: heroes.getOrElse(2) { heroes[0] }
            else -> heroes[0]
        }
    }

    /**
     * Concurrent scraper runner using async/awaitAll across all working sites
     */
    suspend fun fetchScrapedHomePagePayload(pluginId: String = "all"): HomePagePayload = withContext(Dispatchers.IO) {
        coroutineScope {
            when (pluginId.lowercase()) {
                "multimovies" -> ZaswixMultiScraper.scrapeMultiMovies()
                "vegamovies" -> ZaswixMultiScraper.scrapeVegaMovies()
                "multinime" -> ZaswixMultiScraper.scrapeMultinime()
                "toonstream" -> ZaswixMultiScraper.scrapeToonStream()
                "bollyflix" -> ZaswixMultiScraper.scrapeBollyFlix()
                else -> {
                    val mmDeferred = async { ZaswixMultiScraper.scrapeMultiMovies() }
                    val vmDeferred = async { ZaswixMultiScraper.scrapeVegaMovies() }
                    val mnDeferred = async { ZaswixMultiScraper.scrapeMultinime() }
                    val tsDeferred = async { ZaswixMultiScraper.scrapeToonStream() }
                    val bfDeferred = async { ZaswixMultiScraper.scrapeBollyFlix() }

                    val mm = try { mmDeferred.await() } catch (e: Exception) { HomePagePayload(emptyList(), emptyList()) }
                    val vm = try { vmDeferred.await() } catch (e: Exception) { HomePagePayload(emptyList(), emptyList()) }
                    val mn = try { mnDeferred.await() } catch (e: Exception) { HomePagePayload(emptyList(), emptyList()) }
                    val ts = try { tsDeferred.await() } catch (e: Exception) { HomePagePayload(emptyList(), emptyList()) }
                    val bf = try { bfDeferred.await() } catch (e: Exception) { HomePagePayload(emptyList(), emptyList()) }

                    val combinedHero = (mm.heroBannerItems + vm.heroBannerItems + mn.heroBannerItems + ts.heroBannerItems + bf.heroBannerItems)
                        .distinctBy { it.title }
                        .take(10)

                    val combinedRows = (mm.categoryRows + vm.categoryRows + mn.categoryRows + ts.categoryRows + bf.categoryRows)
                        .filter { it.items.isNotEmpty() }

                    HomePagePayload(combinedHero, combinedRows)
                }
            }
        }
    }

    /**
     * Homepage categories: Emits instant curated rows then live scraped rows from all working sites
     */
    fun getHomeCategories(pluginId: String = "all"): Flow<List<MediaCategory>> = flow {
        // 1. Instant display with rich categories so user never sees a blank screen
        val curated = getCuratedCategories(pluginId)
        emit(curated)

        // 2. Concurrently scrape live sources across all 5 sites
        try {
            val payload = fetchScrapedHomePagePayload(pluginId)

            if (payload.heroBannerItems.isNotEmpty()) {
                _heroBannerItems.value = payload.heroBannerItems.map { it.toMediaItem() }
            }

            if (payload.categoryRows.isNotEmpty()) {
                val liveCategories = payload.categoryRows.map { it.toMediaCategory() }
                emit(liveCategories)
            }
        } catch (e: Exception) {
            // Keep curated categories on network failure
        }
    }

    private fun ScrapedMediaItem.toMediaItem(): MediaItem {
        val mediaType = when (type.lowercase()) {
            "series", "show", "tv", "web series" -> MediaType.SERIES
            "anime" -> MediaType.ANIME
            else -> MediaType.MOVIE
        }
        return MediaItem(
            id = id,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl.ifEmpty { posterUrl },
            overview = synopsis.ifEmpty { title },
            rating = rating.ifEmpty { "8.5" },
            year = year.ifEmpty { "2024" },
            type = mediaType,
            genres = listOfNotNull(type.takeIf { it.isNotBlank() }, quality.takeIf { it.isNotBlank() }),
            durationOrSeasons = quality.ifEmpty { "HD" },
            providerName = providerName,
            pageUrl = url
        )
    }

    private fun ScrapedCategoryRow.toMediaCategory(): MediaCategory {
        return MediaCategory(
            title = categoryTitle,
            items = items.map { it.toMediaItem() }
        )
    }

    private fun getCuratedCategories(pluginId: String = "all"): List<MediaCategory> {
        val allCategories = listOf(
            MediaCategory(
                title = "Trending Now",
                items = getTrendingNowItems()
            ),
            MediaCategory(
                title = "Top 10 Today",
                items = getTop10TodayItems()
            ),
            MediaCategory(
                title = "Sci-Fi & Cyber",
                items = getSciFiAndCyberItems()
            ),
            MediaCategory(
                title = "Action Thrillers",
                items = getActionThrillersItems()
            ),
            MediaCategory(
                title = "Mystery Series",
                items = getMysterySeriesItems()
            ),
            MediaCategory(
                title = "Newly Added",
                items = getNewlyAddedItems()
            )
        )

        return if (pluginId.isNotBlank() && pluginId != "bollyflix" && pluginId != "all") {
            val targetProvider = when (pluginId.lowercase()) {
                "vegamovies" -> "VegaMovies"
                "toonstream" -> "ToonStream"
                "multinime" -> "MultiNime"
                "multimovies" -> "MultiMovies"
                "netmirror" -> "NetMirror"
                "plex" -> "Plex Stream"
                else -> ""
            }
            if (targetProvider.isNotEmpty()) {
                allCategories.map { category ->
                    val providerItems = category.items.filter { it.providerName.contains(targetProvider, ignoreCase = true) }
                    val otherItems = category.items.filterNot { it.providerName.contains(targetProvider, ignoreCase = true) }
                    category.copy(items = providerItems + otherItems)
                }
            } else {
                allCategories
            }
        } else {
            allCategories
        }
    }

    fun getAllSearchItems(): Flow<List<MediaItem>> = flow {
        val all = (
            getTrendingNowItems() +
            getTop10TodayItems() +
            getSciFiAndCyberItems() +
            getActionThrillersItems() +
            getMysterySeriesItems() +
            getNewlyAddedItems() +
            _heroBannerItems.value
        ).distinctBy { it.id }
        emit(all)
    }

    /**
     * Default curated hero banner items
     */
    fun getDefaultHeroBannerItems(): List<MediaItem> {
        return listOf(
            MediaItem(
                id = "hero_dune2",
                title = "Dune: Part Two (2024)",
                posterUrl = "https://image.tmdb.org/t/p/w780/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/original/xOMo8BRK7PfcJv9JCnx7s520DRq.jpg",
                overview = "Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family, facing a choice between love and the fate of the universe.",
                rating = "8.8",
                year = "2024",
                type = MediaType.MOVIE,
                genres = listOf("Sci-Fi", "Adventure", "Action"),
                durationOrSeasons = "2h 46m",
                pageUrl = "https://vegamovies.gallery/download-dune-part-two-2024-hindi-english-480p-720p-1080p/",
                providerName = "VegaMovies"
            ),
            MediaItem(
                id = "hero_deadpool",
                title = "Deadpool & Wolverine (2024)",
                posterUrl = "https://image.tmdb.org/t/p/w780/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/original/9l1eZiJHmhr5jYmsV42QNVXTmu2.jpg",
                overview = "A listless Wade Wilson toils in civilian life with his days as Deadpool behind him. But when an existential threat arises, he must team up with an even more reluctant Wolverine.",
                rating = "8.9",
                year = "2024",
                type = MediaType.MOVIE,
                genres = listOf("Action", "Comedy", "Sci-Fi", "Superhero"),
                durationOrSeasons = "2h 08m",
                pageUrl = "https://vegamovies.gallery/download-deadpool-wolverine-2024-hindi-org-dd5-1-english-480p-720p-1080p-2160p-4k-sdr/",
                providerName = "VegaMovies"
            ),
            MediaItem(
                id = "hero_drishyam",
                title = "Drishyam: The Conclusion (2026)",
                posterUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/10/drishyam-the-conclusion-2026-hindi-movie.jpg",
                backdropUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/10/drishyam-the-conclusion-2026-hindi-movie.jpg",
                overview = "Vijay Salgaonkar and his family face the ultimate test as the investigation reopens with shocking new evidence and revelations that test the limits of family loyalty.",
                rating = "8.9",
                year = "2026",
                type = MediaType.MOVIE,
                genres = listOf("Crime", "Mystery", "Thriller", "Hindi"),
                durationOrSeasons = "2h 38m",
                pageUrl = "https://bollyflix.photo/download-drishyam-the-conclusion-2026-hindi-movie/",
                providerName = "BollyFlix"
            ),
            MediaItem(
                id = "hero_sololeveling",
                title = "Solo Leveling",
                posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx151807-it355ZgzquUd.png",
                backdropUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/banner/151807-3i864hQpDqC6.jpg",
                overview = "In a world where hunters battle deadly monsters to protect mankind, the weakest hunter Sung Jinwoo discovers a mysterious quest system that grants him the ability to level up infinitely.",
                rating = "8.8",
                year = "2024",
                type = MediaType.ANIME,
                genres = listOf("Anime", "Action", "Fantasy", "Supernatural"),
                durationOrSeasons = "Season 1 • 12 Episodes",
                episodes = createSampleEpisodes("Solo Leveling", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx151807-it355ZgzquUd.png", 12, soloLevelingEpisodeLinks),
                pageUrl = "https://nexdrive.beer/genxfm784776346841/",
                providerName = "ToonStream"
            ),
            MediaItem(
                id = "hero_interstellar",
                title = "Interstellar",
                posterUrl = "https://image.tmdb.org/t/p/w780/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/original/xJHokMbljvjADYdit5fK5VQsXEG.jpg",
                overview = "When Earth becomes uninhabitable in the future, a farmer and ex-NASA pilot, Joseph Cooper, is tasked to pilot a spacecraft to find a new planet for human civilization.",
                rating = "8.7",
                year = "2014",
                type = MediaType.MOVIE,
                genres = listOf("Sci-Fi", "Drama", "Adventure"),
                durationOrSeasons = "2h 49m",
                pageUrl = "https://vegamovies.gallery/download-interstellar-2014-hindi-english-480p-720p-1080p/",
                providerName = "MultiMovies"
            ),
            MediaItem(
                id = "hero_theboys",
                title = "The Boys Season 4",
                posterUrl = "https://image.tmdb.org/t/p/w780/27A45499GfS3e5Gk4tC2zV6k7rF.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/original/stKGOmPaq72n29izNmFi0SuSJHi.jpg",
                overview = "The world is on the brink. Victoria Neuman is closer than ever to the Oval Office and under the muscly thumb of Homelander, who is consolidating his power.",
                rating = "8.7",
                year = "2024",
                type = MediaType.SERIES,
                genres = listOf("Superhero", "Action", "Dark Comedy"),
                durationOrSeasons = "4 Seasons • 32 Episodes",
                episodes = createSampleEpisodes("The Boys S4", "https://image.tmdb.org/t/p/w780/27A45499GfS3e5Gk4tC2zV6k7rF.jpg", 8),
                pageUrl = "https://vegamovies.gallery/download-the-boys-season-4-hindi-english-480p-720p-1080p/",
                providerName = "NetMirror"
            )
        )
    }

    // -----------------------------------------------------------------------------------------
    // Row 1: Trending Now (12 Items)
    // -----------------------------------------------------------------------------------------
    private fun getTrendingNowItems(): List<MediaItem> = listOf(
        MediaItem(
            id = "tn_1",
            title = "Dune: Part Two (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/xOMo8BRK7PfcJv9JCnx7s520DRq.jpg",
            overview = "Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family.",
            rating = "8.8",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Sci-Fi", "Adventure", "Action"),
            durationOrSeasons = "2h 46m",
            pageUrl = "https://vegamovies.gallery/download-dune-part-two-2024-hindi-english-480p-720p-1080p/",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "tn_2",
            title = "Deadpool & Wolverine (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/9l1eZiJHmhr5jYmsV42QNVXTmu2.jpg",
            overview = "Deadpool and a reluctant Wolverine team up to combat an existential threat to the Marvel Cinematic Universe.",
            rating = "8.9",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Comedy", "Sci-Fi", "Superhero"),
            durationOrSeasons = "2h 08m",
            pageUrl = "https://vegamovies.gallery/download-deadpool-wolverine-2024-hindi-org-dd5-1-english-480p-720p-1080p-2160p-4k-sdr/",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "tn_3",
            title = "Drishyam: The Conclusion (2026)",
            posterUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/10/drishyam-the-conclusion-2026-hindi-movie.jpg",
            backdropUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/10/drishyam-the-conclusion-2026-hindi-movie.jpg",
            overview = "Vijay Salgaonkar and his family face the ultimate test as the investigation reopens with shocking new evidence.",
            rating = "8.9",
            year = "2026",
            type = MediaType.MOVIE,
            genres = listOf("Crime", "Mystery", "Thriller", "Hindi"),
            durationOrSeasons = "2h 38m",
            pageUrl = "https://bollyflix.photo/download-drishyam-the-conclusion-2026-hindi-movie/",
            providerName = "BollyFlix"
        ),
        MediaItem(
            id = "tn_4",
            title = "Solo Leveling",
            posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx151807-it355ZgzquUd.png",
            backdropUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/banner/151807-3i864hQpDqC6.jpg",
            overview = "In a world of deadly monsters, the weakest hunter discovers a quest system that allows him to level up indefinitely.",
            rating = "8.8",
            year = "2024",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Action", "Fantasy"),
            durationOrSeasons = "Season 1 • 12 Episodes",
            episodes = createSampleEpisodes("Solo Leveling", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx151807-it355ZgzquUd.png", 12, soloLevelingEpisodeLinks),
            pageUrl = "https://nexdrive.beer/genxfm784776346841/",
            providerName = "ToonStream"
        ),
        MediaItem(
            id = "tn_5",
            title = "The Boys Season 4",
            posterUrl = "https://image.tmdb.org/t/p/w500/27A45499GfS3e5Gk4tC2zV6k7rF.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/stKGOmPaq72n29izNmFi0SuSJHi.jpg",
            overview = "Victoria Neuman is closer than ever to the Oval Office and under the muscly thumb of Homelander.",
            rating = "8.7",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Superhero", "Action", "Dark Comedy"),
            durationOrSeasons = "4 Seasons • 32 Episodes",
            episodes = createSampleEpisodes("The Boys S4", "https://image.tmdb.org/t/p/w500/27A45499GfS3e5Gk4tC2zV6k7rF.jpg", 8),
            pageUrl = "https://vegamovies.gallery/download-the-boys-season-4-hindi-english-480p-720p-1080p/",
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "tn_6",
            title = "Oppenheimer (2023)",
            posterUrl = "https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/fm6K9vY9wvTpSzW5rlvpmLQ1LQA.jpg",
            overview = "The story of American scientist J. Robert Oppenheimer and his role in the development of the atomic bomb.",
            rating = "8.9",
            year = "2023",
            type = MediaType.MOVIE,
            genres = listOf("Drama", "History", "Biography"),
            durationOrSeasons = "3h 00m",
            pageUrl = "https://vegamovies.gallery/download-oppenheimer-2023-hindi-english-480p-720p-1080p/",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "tn_7",
            title = "Reacher: Season 4 (2026)",
            posterUrl = "https://image.tmdb.org/t/p/w500/5t1gXdmCqaHPcTwH8roaqV0cmPz.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/sc1ab1qqUvxV24AvUf21j9k6M2M.jpg",
            overview = "Jack Reacher is drawn into a deadly conspiracy involving high-ranking military officials and an international syndicate.",
            rating = "8.8",
            year = "2026",
            type = MediaType.SERIES,
            genres = listOf("Action", "Crime", "Thriller"),
            durationOrSeasons = "Season 4 • 8 Episodes",
            episodes = createSampleEpisodes("Reacher S4", "https://image.tmdb.org/t/p/w500/5t1gXdmCqaHPcTwH8roaqV0cmPz.jpg", 8),
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "tn_8",
            title = "Spider-Man: Brand New Day (2026)",
            posterUrl = "https://image.tmdb.org/t/p/w500/4MsQLF4n2yBZbyFsUxDyn4oBd5B.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/14QbnygCuTO0vl7CAFmPf1fgZfV.jpg",
            overview = "Peter Parker rebuilds his life in New York City with new allies and a sinister looming shadow over Manhattan.",
            rating = "8.9",
            year = "2026",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Adventure", "Dual Audio"),
            durationOrSeasons = "2h 25m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "tn_9",
            title = "The Batman (2022)",
            posterUrl = "https://image.tmdb.org/t/p/w500/74xTEgt7R36Fpooo50r9T25onhq.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/b0PlSFdDwbyK0cf5RxwDpaOJQvQ.jpg",
            overview = "When a sadistic serial killer begins murdering key political figures in Gotham, Batman must investigate the city's corruption.",
            rating = "8.4",
            year = "2022",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Crime", "Mystery"),
            durationOrSeasons = "2h 56m",
            providerName = "MultiMovies"
        ),
        MediaItem(
            id = "tn_10",
            title = "Gladiator II (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/2cxhvwyEwRlysAmRH4iodkvo0z5.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/euYIwmqkmz95mnXvufEmbL69nmr.jpg",
            overview = "Lucius enters the Colosseum after his home is conquered by the tyrannical emperors who now lead Rome with an iron fist.",
            rating = "8.5",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Adventure", "Drama"),
            durationOrSeasons = "2h 28m",
            providerName = "MultiMovies"
        ),
        MediaItem(
            id = "tn_11",
            title = "Alien: Romulus (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/b33nnKl1GSFbao4l3fZDDqsMx0F.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/9SSEUrSqhljBMzRe4aBTh17rUaC.jpg",
            overview = "While scavenging the deep ends of a derelict space station, a group of young colonizers face the universe's deadliest lifeform.",
            rating = "8.3",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Horror", "Sci-Fi", "Thriller"),
            durationOrSeasons = "1h 59m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "tn_12",
            title = "Furiosa: A Mad Max Saga (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/iADOJ8Zymht2JPMoy3R7xceZprc.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/wNAhuOZ3Zf84jCI5TeTVxDUMWEZ.jpg",
            overview = "The origin story of renegade warrior Furiosa before her encounter and team-up with Mad Max.",
            rating = "8.2",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Adventure", "Sci-Fi"),
            durationOrSeasons = "2h 28m",
            providerName = "BollyFlix"
        )
    )

    // -----------------------------------------------------------------------------------------
    // Row 2: Top 10 Today (12 Items)
    // -----------------------------------------------------------------------------------------
    private fun getTop10TodayItems(): List<MediaItem> = listOf(
        MediaItem(
            id = "top_1",
            title = "1. Interstellar (2014)",
            posterUrl = "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/xJHokMbljvjADYdit5fK5VQsXEG.jpg",
            overview = "A team of explorers travel through a wormhole in space in an attempt to ensure humanity's survival.",
            rating = "9.0",
            year = "2014",
            type = MediaType.MOVIE,
            genres = listOf("Sci-Fi", "Drama", "Adventure"),
            durationOrSeasons = "2h 49m",
            pageUrl = "https://vegamovies.gallery/download-interstellar-2014-hindi-english-480p-720p-1080p/",
            providerName = "Plex Stream"
        ),
        MediaItem(
            id = "top_2",
            title = "2. The Dark Knight (2008)",
            posterUrl = "https://image.tmdb.org/t/p/w500/qJ2tW6WMUDux911r6m7haRef0WH.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/hkBaDkMWbLaf8B1rsqRmrCYTszw.jpg",
            overview = "When the menace known as the Joker wreaks havoc and chaos on the people of Gotham, Batman must accept one of the greatest psychological and physical tests.",
            rating = "9.0",
            year = "2008",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Crime", "Drama"),
            durationOrSeasons = "2h 32m",
            providerName = "Plex Stream"
        ),
        MediaItem(
            id = "top_3",
            title = "3. Inception (2010)",
            posterUrl = "https://image.tmdb.org/t/p/w500/edv5CZvWj09upOsy2Y6IwDhK8bt.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/s3TBrRGB1iav7gFOCNx3H31MoES.jpg",
            overview = "A thief who steals corporate secrets through the use of dream-sharing technology is given the inverse task of planting an idea.",
            rating = "8.8",
            year = "2010",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Sci-Fi", "Adventure"),
            durationOrSeasons = "2h 28m",
            providerName = "MultiMovies"
        ),
        MediaItem(
            id = "top_4",
            title = "4. Cyberpunk: Edgerunners",
            posterUrl = "https://image.tmdb.org/t/p/w500/7jswLnsZ44l4tFYsWoxvRzEkr5t.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/2wT5B6Lz3n8y0n0g6Hq1P0j6Z4c.jpg",
            overview = "A street kid trying to survive in a technology and body modification-obsessed city of the future becomes an edgerunner.",
            rating = "8.9",
            year = "2022",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Sci-Fi", "Action"),
            durationOrSeasons = "Season 1 • 10 Episodes",
            episodes = createSampleEpisodes("Cyberpunk", "https://image.tmdb.org/t/p/w500/7jswLnsZ44l4tFYsWoxvRzEkr5t.jpg", 10),
            providerName = "ToonStream"
        ),
        MediaItem(
            id = "top_5",
            title = "5. Shōgun (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/7O4iVfOMQmdCSxhOg1WNzG1AgYT.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/pA0Vz6nO8Ld0tqM0rTq4gQ7wU4b.jpg",
            overview = "When a mysterious European ship is found marooned in a nearby fishing village, Lord Yoshii Toranaga discovers secrets that could tip the scales of power.",
            rating = "8.9",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Drama", "History", "War"),
            durationOrSeasons = "Season 1 • 10 Episodes",
            episodes = createSampleEpisodes("Shogun", "https://image.tmdb.org/t/p/w500/7O4iVfOMQmdCSxhOg1WNzG1AgYT.jpg", 10),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "top_6",
            title = "6. House of the Dragon S2",
            posterUrl = "https://image.tmdb.org/t/p/w500/7QMsOTMUswlwxJP0rTTZfmz2tX2.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/etj5CuMuam3hGQ3QIadEKFW00n6.jpg",
            overview = "The Targaryen civil war begins as Rhaenyra and Aegon II vie for the Iron Throne in a bloody conflict of dragons.",
            rating = "8.7",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Action", "Drama", "Fantasy"),
            durationOrSeasons = "2 Seasons • 18 Episodes",
            episodes = createSampleEpisodes("House of Dragon S2", "https://image.tmdb.org/t/p/w500/7QMsOTMUswlwxJP0rTTZfmz2tX2.jpg", 8),
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "top_7",
            title = "7. Breaking Bad",
            posterUrl = "https://image.tmdb.org/t/p/w500/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/9faGSFi5jam6pDWGNd0p8J24gvm.jpg",
            overview = "A chemistry teacher diagnosed with terminal lung cancer teams up with a former student to manufacture crystal meth.",
            rating = "9.5",
            year = "2008",
            type = MediaType.SERIES,
            genres = listOf("Crime", "Drama", "Thriller"),
            durationOrSeasons = "5 Seasons • 62 Episodes",
            episodes = createSampleEpisodes("Breaking Bad", "https://image.tmdb.org/t/p/w500/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg", 10),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "top_8",
            title = "8. Arcane: Season 2 (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/fqldf2t8ztc9aiwn397rGG2y34z.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
            overview = "Amid the stark discord of twin cities Piltover and Zaun, two sisters fight on rival sides of a war between magic technologies and convictions.",
            rating = "9.1",
            year = "2024",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Action", "Sci-Fi"),
            durationOrSeasons = "2 Seasons • 18 Episodes",
            episodes = createSampleEpisodes("Arcane S2", "https://image.tmdb.org/t/p/w500/fqldf2t8ztc9aiwn397rGG2y34z.jpg", 9),
            providerName = "ToonStream"
        ),
        MediaItem(
            id = "top_9",
            title = "9. Severance",
            posterUrl = "https://image.tmdb.org/t/p/w500/775u0nUvA75n643iPq7f3pB86vK.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/o0FbdC3C910f1R8fN615zHqf2vR.jpg",
            overview = "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.",
            rating = "8.8",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Sci-Fi", "Mystery", "Thriller"),
            durationOrSeasons = "2 Seasons • 19 Episodes",
            episodes = createSampleEpisodes("Severance", "https://image.tmdb.org/t/p/w500/775u0nUvA75n643iPq7f3pB86vK.jpg", 9),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "top_10",
            title = "10. The Penguin (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/vOWF5q5k4bT0z3r8iU3gZ2A1B7b.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/2meX1nMdScFOoV4370rqHWFDxZf.jpg",
            overview = "Following the events of The Batman, Oz Cobb seeks to seize the reins of the criminal underworld in Gotham City.",
            rating = "8.8",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Crime", "Drama"),
            durationOrSeasons = "Season 1 • 8 Episodes",
            episodes = createSampleEpisodes("The Penguin", "https://image.tmdb.org/t/p/w500/vOWF5q5k4bT0z3r8iU3gZ2A1B7b.jpg", 8),
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "top_11",
            title = "11. Fallout (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/ansFbW5TEHiN5X578rT6y9dYm8q.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/fZtU9P61m02aRzL7bYkFzQyW5yL.jpg",
            overview = "In a future, post-apocalyptic Los Angeles brought about by nuclear decimation, citizens must live in underground bunkers to protect themselves from radiation.",
            rating = "8.7",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Action", "Adventure", "Sci-Fi"),
            durationOrSeasons = "Season 1 • 8 Episodes",
            episodes = createSampleEpisodes("Fallout", "https://image.tmdb.org/t/p/w500/ansFbW5TEHiN5X578rT6y9dYm8q.jpg", 8),
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "top_12",
            title = "12. Dune: Prophecy (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/b1Uptq7d0jM9vY81j6R4Z9bH1p0.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/xOMo8BRK7PfcJv9JCnx7s520DRq.jpg",
            overview = "Set 10,000 years before the ascension of Paul Atreides, two Harkonnen sisters combat forces that threaten the future of humankind.",
            rating = "8.5",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Sci-Fi", "Drama", "Action"),
            durationOrSeasons = "Season 1 • 6 Episodes",
            episodes = createSampleEpisodes("Dune Prophecy", "https://image.tmdb.org/t/p/w500/b1Uptq7d0jM9vY81j6R4Z9bH1p0.jpg", 6),
            providerName = "MultiMovies"
        )
    )

    // -----------------------------------------------------------------------------------------
    // Row 3: Sci-Fi & Cyber (12 Items)
    // -----------------------------------------------------------------------------------------
    private fun getSciFiAndCyberItems(): List<MediaItem> = listOf(
        MediaItem(
            id = "scifi_1",
            title = "Blade Runner 2049 (2017)",
            posterUrl = "https://image.tmdb.org/t/p/w500/gajva2L0rPYkEWjzgFlBXCAVBE5.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/ilRyazdMJwN05exqhwK4tMKBYZs.jpg",
            overview = "A young blade runner's discovery of a long-buried secret leads him to track down former blade runner Rick Deckard.",
            rating = "8.6",
            year = "2017",
            type = MediaType.MOVIE,
            genres = listOf("Sci-Fi", "Drama", "Cyberpunk"),
            durationOrSeasons = "2h 44m",
            providerName = "MultiMovies"
        ),
        MediaItem(
            id = "scifi_2",
            title = "Cyberpunk: Edgerunners",
            posterUrl = "https://image.tmdb.org/t/p/w500/7jswLnsZ44l4tFYsWoxvRzEkr5t.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/2wT5B6Lz3n8y0n0g6Hq1P0j6Z4c.jpg",
            overview = "In a dystopian metropolis filled with body modifications, a street kid fights to stay alive as a high-tech outlaw.",
            rating = "8.9",
            year = "2022",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Cyberpunk", "Action"),
            durationOrSeasons = "Season 1 • 10 Episodes",
            episodes = createSampleEpisodes("Cyberpunk", "https://image.tmdb.org/t/p/w500/7jswLnsZ44l4tFYsWoxvRzEkr5t.jpg", 10),
            providerName = "ToonStream"
        ),
        MediaItem(
            id = "scifi_3",
            title = "The Matrix Resurrections (2021)",
            posterUrl = "https://image.tmdb.org/t/p/w500/8c4a8kE7PizaGQQvMiKmmXqA7Qq.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/hv7o3Y0q8bG6vM1K3v8r7hQ6B7Y.jpg",
            overview = "Return to a world of two realities: one, everyday life; the other, what lies behind it. To find out if his reality is a construct, Neo must choose to follow the white rabbit once more.",
            rating = "8.1",
            year = "2021",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Sci-Fi", "Cyberpunk"),
            durationOrSeasons = "2h 28m",
            providerName = "MultiMovies"
        ),
        MediaItem(
            id = "scifi_4",
            title = "DARK MACHINE THE ANIMATION",
            posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx179876-Wg62CAzp5WQ1.jpg",
            backdropUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx179876-Wg62CAzp5WQ1.jpg",
            overview = "In a dark dystopian future, warriors pilot sentient mecha combat armor to defend the last bastion of humanity.",
            rating = "9.1",
            year = "2025",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Mecha", "Sci-Fi"),
            durationOrSeasons = "Season 1 • 12 Episodes",
            episodes = createSampleEpisodes("DARK MACHINE", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx179876-Wg62CAzp5WQ1.jpg", 12),
            providerName = "MultiNime"
        ),
        MediaItem(
            id = "scifi_5",
            title = "Interstellar (2014)",
            posterUrl = "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/xJHokMbljvjADYdit5fK5VQsXEG.jpg",
            overview = "A team of explorers undertakes the most important mission in human history: traveling beyond this galaxy.",
            rating = "8.7",
            year = "2014",
            type = MediaType.MOVIE,
            genres = listOf("Sci-Fi", "Drama", "Adventure"),
            durationOrSeasons = "2h 49m",
            providerName = "Plex Stream"
        ),
        MediaItem(
            id = "scifi_6",
            title = "Dune: Part Two (2024)",
            posterUrl = "https://image.tmdb.org/t/p/original/7p0QLfjFx1iJzJ6iB82mp7E9qIH.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/xOMo8BRK7PfcJv9JCnx7s520DRq.jpg",
            overview = "Paul Atreides chooses between the love of his life and the fate of the known universe.",
            rating = "8.8",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Sci-Fi", "Action", "Dual Audio"),
            durationOrSeasons = "2h 46m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "scifi_7",
            title = "The Creator (2023)",
            posterUrl = "https://image.tmdb.org/t/p/w500/vBZ0qvaRxqEhZwg6A9E54nv9w7f.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/t5zCBSB5xMDKcDqe91qahCOUYVV.jpg",
            overview = "Amid a future war between humans and artificial intelligence, an ex-special forces agent is recruited to hunt down the architect of advanced AI.",
            rating = "8.2",
            year = "2023",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Adventure", "Sci-Fi"),
            durationOrSeasons = "2h 13m",
            providerName = "MultiMovies"
        ),
        MediaItem(
            id = "scifi_8",
            title = "Tenet (2020)",
            posterUrl = "https://image.tmdb.org/t/p/w500/k68nPLbIST6NP96vIQ39nxQIaoa.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/wzJRB4MKi3yK138bEYvAqP695Jn.jpg",
            overview = "Armed with only one word, Tenet, and fighting for the survival of the world, a Protagonist journeys through a twilight world of international espionage.",
            rating = "8.4",
            year = "2020",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Sci-Fi", "Thriller"),
            durationOrSeasons = "2h 30m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "scifi_9",
            title = "Westworld",
            posterUrl = "https://image.tmdb.org/t/p/w500/8MfgyFHf7XEhu2bq3t4k0yD5o1m.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/27A45499GfS3e5Gk4tC2zV6k7rF.jpg",
            overview = "A dark odyssey about the dawn of artificial consciousness and the evolution of sin in a futuristic amusement park.",
            rating = "8.6",
            year = "2020",
            type = MediaType.SERIES,
            genres = listOf("Sci-Fi", "Drama", "Mystery"),
            durationOrSeasons = "4 Seasons • 36 Episodes",
            episodes = createSampleEpisodes("Westworld", "https://image.tmdb.org/t/p/w500/8MfgyFHf7XEhu2bq3t4k0yD5o1m.jpg", 10),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "scifi_10",
            title = "Foundation Season 2",
            posterUrl = "https://image.tmdb.org/t/p/w500/512y2Vn553eWkrwmH5kP280gMee.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/sc1ab1qqUvxV24AvUf21j9k6M2M.jpg",
            overview = "A complex saga of humans scattered on planets throughout the galaxy all living under the rule of the Galactic Empire.",
            rating = "8.5",
            year = "2023",
            type = MediaType.SERIES,
            genres = listOf("Sci-Fi", "Drama"),
            durationOrSeasons = "2 Seasons • 20 Episodes",
            episodes = createSampleEpisodes("Foundation", "https://image.tmdb.org/t/p/w500/512y2Vn553eWkrwmH5kP280gMee.jpg", 10),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "scifi_11",
            title = "Altered Carbon",
            posterUrl = "https://image.tmdb.org/t/p/w500/5k7rUo06f36Hn2m6H2YyB0f5h4H.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/9l1eZiJHmhr5jYmsV42QNVXTmu2.jpg",
            overview = "Set in a future where human consciousness is digitized and transferred from body to body, a solitary soldier must solve a murder.",
            rating = "8.4",
            year = "2018",
            type = MediaType.SERIES,
            genres = listOf("Sci-Fi", "Cyberpunk", "Action"),
            durationOrSeasons = "2 Seasons • 18 Episodes",
            episodes = createSampleEpisodes("Altered Carbon", "https://image.tmdb.org/t/p/w500/5k7rUo06f36Hn2m6H2YyB0f5h4H.jpg", 8),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "scifi_12",
            title = "Ghost in the Shell: SAC_2045",
            posterUrl = "https://image.tmdb.org/t/p/w500/9J0Vq7Y9Q3h4s5B8j5tY6m8t0kL.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/s3TBrRGB1iav7gFOCNx3H31MoES.jpg",
            overview = "In 2045, mercenary cyborg Motoko Kusanagi and Section 9 face an emergence of Post-Humans with incredible intelligence and physical powers.",
            rating = "8.3",
            year = "2022",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Cyberpunk", "Mecha"),
            durationOrSeasons = "2 Seasons • 24 Episodes",
            episodes = createSampleEpisodes("SAC 2045", "https://image.tmdb.org/t/p/w500/9J0Vq7Y9Q3h4s5B8j5tY6m8t0kL.jpg", 12),
            providerName = "ToonStream"
        )
    )

    // -----------------------------------------------------------------------------------------
    // Row 4: Action Thrillers (12 Items)
    // -----------------------------------------------------------------------------------------
    private fun getActionThrillersItems(): List<MediaItem> = listOf(
        MediaItem(
            id = "act_1",
            title = "John Wick: Chapter 4 (2023)",
            posterUrl = "https://image.tmdb.org/t/p/w500/vZloFAK7NKnMGKEslUsZgiGRioE.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/h8gHn0OzRogL05h9DImRncG2gnv.jpg",
            overview = "John Wick uncovers a path to defeating The High Table, but before he can earn his freedom, Wick must face a new enemy with powerful alliances across the globe.",
            rating = "8.8",
            year = "2023",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Crime", "Thriller"),
            durationOrSeasons = "2h 49m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "act_2",
            title = "Mission: Impossible - Dead Reckoning (2023)",
            posterUrl = "https://image.tmdb.org/t/p/w500/NNxYkU70HPurnNCSiCjYAmacwm.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/628Dep6AxEtDxjZoGP78TsOxYbK.jpg",
            overview = "Ethan Hunt and his IMF team embark on their most dangerous mission yet: to track down a terrifying weapon that threatens all of humanity.",
            rating = "8.6",
            year = "2023",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Adventure", "Thriller"),
            durationOrSeasons = "2h 43m",
            providerName = "MultiMovies"
        ),
        MediaItem(
            id = "act_3",
            title = "Reacher : Season 4 (2026)",
            posterUrl = "https://image.tmdb.org/t/p/w1280/5t1gXdmCqaHPcTwH8roaqV0cmPz.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/sc1ab1qqUvxV24AvUf21j9k6M2M.jpg",
            overview = "Reacher investigates a string of murders that strike close to his former unit, relying on sheer intellect and brutal physical dominance.",
            rating = "8.8",
            year = "2026",
            type = MediaType.SERIES,
            genres = listOf("Action", "Crime", "Thriller"),
            durationOrSeasons = "Season 4 • 8 Episodes",
            episodes = createSampleEpisodes("Reacher S4", "https://image.tmdb.org/t/p/w1280/5t1gXdmCqaHPcTwH8roaqV0cmPz.jpg", 8),
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "act_4",
            title = "Extraction 2 (2023)",
            posterUrl = "https://image.tmdb.org/t/p/w500/7gKI9hpEMcZUwYeCuVmACQC9Y4z.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/fhquSWWvfW44Y3952x1kM1fKkM1.jpg",
            overview = "Back from the brink of death, highly skilled commando Tyler Rake takes on another dangerous mission: saving the imprisoned family of a ruthless gangster.",
            rating = "8.5",
            year = "2023",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Thriller"),
            durationOrSeasons = "2h 02m",
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "act_5",
            title = "Top Gun: Maverick (2022)",
            posterUrl = "https://image.tmdb.org/t/p/w500/62HCnUTziyWcpDaBO2i1DX17ljH.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/AaV1YIdWKnjAIAOe8UUKBFm327v.jpg",
            overview = "After thirty years, Maverick is still pushing the envelope as a top naval aviator, but must confront ghosts of his past when he leads TOP GUN's elite graduates.",
            rating = "8.7",
            year = "2022",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Drama"),
            durationOrSeasons = "2h 10m",
            providerName = "MultiMovies"
        ),
        MediaItem(
            id = "act_6",
            title = "Nobody (2021)",
            posterUrl = "https://image.tmdb.org/t/p/w500/oBgWY00bEFeZ9N25wWVyuQddbBc.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/6zbKgwgaaCyyBXE4syCrSYxnUmw.jpg",
            overview = "A docile family man slowly reveals his true character after his house is burgled by two petty thieves, which triggers his former life of lethality.",
            rating = "8.3",
            year = "2021",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Crime", "Thriller"),
            durationOrSeasons = "1h 32m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "act_7",
            title = "The Gray Man (2022)",
            posterUrl = "https://image.tmdb.org/t/p/w500/8cXbitsS6mWr5NoMZV46ye8Ngsq.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/27A45499GfS3e5Gk4tC2zV6k7rF.jpg",
            overview = "When the CIA's most skilled operative accidentally uncovers dark agency secrets, he becomes a primary target and is hunted around the world by sociopathic rogue agents.",
            rating = "8.1",
            year = "2022",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Thriller"),
            durationOrSeasons = "2h 02m",
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "act_8",
            title = "The Night Agent",
            posterUrl = "https://image.tmdb.org/t/p/w500/590N0l498t3n4Y9Z9e0y1v8qM0r.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/fZtU9P61m02aRzL7bYkFzQyW5yL.jpg",
            overview = "While monitoring an emergency line, an FBI agent answers a call that plunges him into a deadly conspiracy involving a mole at the White House.",
            rating = "8.3",
            year = "2023",
            type = MediaType.SERIES,
            genres = listOf("Action", "Drama", "Mystery"),
            durationOrSeasons = "Season 1 • 10 Episodes",
            episodes = createSampleEpisodes("Night Agent", "https://image.tmdb.org/t/p/w500/590N0l498t3n4Y9Z9e0y1v8qM0r.jpg", 10),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "act_9",
            title = "Bullet Train (2022)",
            posterUrl = "https://image.tmdb.org/t/p/w500/tVxDe01Zy3kZqaR4ezGsDxJWBnM.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/y5Z0WesTjvn59jP4yo459G12z3u.jpg",
            overview = "Five assassins aboard a fast-moving bullet train find out their missions have something in common.",
            rating = "8.2",
            year = "2022",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Comedy", "Thriller"),
            durationOrSeasons = "2h 07m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "act_10",
            title = "The Beekeeper (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/A7EByudX07tcLkXZcuSp2mQ2Ahc.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/4woSOUD0equorxvUm8j58Fs4Ac8.jpg",
            overview = "One man's brutal campaign for vengeance takes on national stakes after he is revealed to be a former operative of a powerful clandestine organization.",
            rating = "8.3",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Thriller"),
            durationOrSeasons = "1h 45m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "act_11",
            title = "Jason Bourne (2016)",
            posterUrl = "https://image.tmdb.org/t/p/w500/lFSSVJ2F52whEzW362P7v9eK0m8.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/7RyHsO4yDXtBv1zUU3mTpHeQ0d5.jpg",
            overview = "The CIA's most dangerous former operative is drawn out of hiding to uncover more explosive truths about his past.",
            rating = "8.0",
            year = "2016",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Thriller"),
            durationOrSeasons = "2h 03m",
            providerName = "Plex Stream"
        ),
        MediaItem(
            id = "act_12",
            title = "Sicario: Day of the Soldado (2018)",
            posterUrl = "https://image.tmdb.org/t/p/w500/msqWSQkR40OFQqqB9w30V5s0lGk.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/9faGSFi5jam6pDWGNd0p8J24gvm.jpg",
            overview = "The drug war on the U.S.-Mexico border has escalated as the cartels have begun trafficking terrorists across the US border.",
            rating = "8.2",
            year = "2018",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Crime", "Drama"),
            durationOrSeasons = "2h 02m",
            providerName = "MultiMovies"
        )
    )

    // -----------------------------------------------------------------------------------------
    // Row 5: Mystery Series (12 Items)
    // -----------------------------------------------------------------------------------------
    private fun getMysterySeriesItems(): List<MediaItem> = listOf(
        MediaItem(
            id = "myst_1",
            title = "Dark",
            posterUrl = "https://image.tmdb.org/t/p/w500/apbrbWs8M9lyOpJYU5WXrpFbk1Z.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/3lBDg3i6nn5R2NKFCJlNymK94z.jpg",
            overview = "A missing child sets four families on a frantic hunt for answers as they unearth a mind-bending mystery that spans three generations.",
            rating = "9.2",
            year = "2017",
            type = MediaType.SERIES,
            genres = listOf("Mystery", "Sci-Fi", "Drama"),
            durationOrSeasons = "3 Seasons • 26 Episodes",
            episodes = createSampleEpisodes("Dark", "https://image.tmdb.org/t/p/w500/apbrbWs8M9lyOpJYU5WXrpFbk1Z.jpg", 10),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "myst_2",
            title = "True Detective: Night Country (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/cuV2O5crXZpAEcqJ2GI96Y9WVO4.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/2meX1nMdScFOoV4370rqHWFDxZf.jpg",
            overview = "When the long winter night falls in Ennis, Alaska, the eight men who operate the Tsalal Arctic Research Station vanish without a trace.",
            rating = "8.8",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Crime", "Drama", "Mystery"),
            durationOrSeasons = "Season 4 • 6 Episodes",
            episodes = createSampleEpisodes("True Detective S4", "https://image.tmdb.org/t/p/w500/cuV2O5crXZpAEcqJ2GI96Y9WVO4.jpg", 6),
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "myst_3",
            title = "Severance",
            posterUrl = "https://image.tmdb.org/t/p/w500/775u0nUvA75n643iPq7f3pB86vK.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/o0FbdC3C910f1R8fN615zHqf2vR.jpg",
            overview = "Employees at Lumon Industries undergo a surgical procedure to separate their non-work memories from their work memories.",
            rating = "8.8",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Mystery", "Sci-Fi", "Drama"),
            durationOrSeasons = "2 Seasons • 19 Episodes",
            episodes = createSampleEpisodes("Severance", "https://image.tmdb.org/t/p/w500/775u0nUvA75n643iPq7f3pB86vK.jpg", 9),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "myst_4",
            title = "Stranger Things 5",
            posterUrl = "https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPfGn8AIqMGskD.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/56v2KjUmLeLo5sB59T693CDi6aq.jpg",
            overview = "The final battle in Hawkins begins as the Upside Down breaches our world and the town prepares for one last stand.",
            rating = "8.8",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Sci-Fi", "Horror", "Mystery"),
            durationOrSeasons = "5 Seasons • 38 Episodes",
            episodes = createSampleEpisodes("Stranger Things", "https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPfGn8AIqMGskD.jpg", 8),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "myst_5",
            title = "Silo (2023)",
            posterUrl = "https://image.tmdb.org/t/p/w500/1N1222k3r5F1i3s0o1c5L1B5y1.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/14QbnygCuTO0vl7CAFmPf1fgZfV.jpg",
            overview = "Men and women live in a giant subterranean silo with regulations they believe are meant to protect them.",
            rating = "8.6",
            year = "2023",
            type = MediaType.SERIES,
            genres = listOf("Drama", "Sci-Fi", "Mystery"),
            durationOrSeasons = "2 Seasons • 20 Episodes",
            episodes = createSampleEpisodes("Silo", "https://image.tmdb.org/t/p/w500/1N1222k3r5F1i3s0o1c5L1B5y1.jpg", 10),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "myst_6",
            title = "Marshals (Season 1 & 2)",
            posterUrl = "https://image.tmdb.org/t/p/w500/nInfaveN1iFpRIcMGNKENoVVUT2.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/sc1ab1qqUvxV24AvUf21j9k6M2M.jpg",
            overview = "Federal marshals track down fugitives while uncovering complex political conspiracies spanning multiple states.",
            rating = "8.6",
            year = "2025",
            type = MediaType.SERIES,
            genres = listOf("Action", "Drama", "Mystery"),
            durationOrSeasons = "2 Seasons • 16 Episodes",
            episodes = createSampleEpisodes("Marshals S2", "https://image.tmdb.org/t/p/w500/nInfaveN1iFpRIcMGNKENoVVUT2.jpg", 8),
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "myst_7",
            title = "Mindhunter",
            posterUrl = "https://image.tmdb.org/t/p/w500/fbKE87mojpIETWepSbD5Qt741d7.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/9SSEUrSqhljBMzRe4aBTh17rUaC.jpg",
            overview = "In the late 1970s, two FBI agents expand criminal science by delving into the psychology of murder and uneasy interviews with serial killers.",
            rating = "8.9",
            year = "2017",
            type = MediaType.SERIES,
            genres = listOf("Crime", "Drama", "Mystery"),
            durationOrSeasons = "2 Seasons • 19 Episodes",
            episodes = createSampleEpisodes("Mindhunter", "https://image.tmdb.org/t/p/w500/fbKE87mojpIETWepSbD5Qt741d7.jpg", 10),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "myst_8",
            title = "1899",
            posterUrl = "https://image.tmdb.org/t/p/w500/8P1nvKAonbDMQvH4yL5o711m9wE.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/wNAhuOZ3Zf84jCI5TeTVxDUMWEZ.jpg",
            overview = "Multinational immigrants traveling from the old continent to the new encounter a nightmare riddle aboard a second ship drifted on the open sea.",
            rating = "8.3",
            year = "2022",
            type = MediaType.SERIES,
            genres = listOf("Drama", "Mystery", "Sci-Fi"),
            durationOrSeasons = "Season 1 • 8 Episodes",
            episodes = createSampleEpisodes("1899", "https://image.tmdb.org/t/p/w500/8P1nvKAonbDMQvH4yL5o711m9wE.jpg", 8),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "myst_9",
            title = "From (Season 3) (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/5k7rUo06f36Hn2m6H2YyB0f5h4H.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/b0PlSFdDwbyK0cf5RxwDpaOJQvQ.jpg",
            overview = "Unravel the mystery of a nightmarish town in middle America that traps everyone who enters, while the unwilling residents struggle to survive terrifying nocturnal creatures.",
            rating = "8.7",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Drama", "Horror", "Mystery"),
            durationOrSeasons = "3 Seasons • 30 Episodes",
            episodes = createSampleEpisodes("From S3", "https://image.tmdb.org/t/p/w500/5k7rUo06f36Hn2m6H2YyB0f5h4H.jpg", 10),
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "myst_10",
            title = "Black Mirror",
            posterUrl = "https://image.tmdb.org/t/p/w500/5UaMirnkEAUjTs74v3ckHRKG5Y.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/s3TBrRGB1iav7gFOCNx3H31MoES.jpg",
            overview = "An anthology series exploring a twisted, high-tech multiverse where humanity's greatest innovations and darkest instincts collide.",
            rating = "8.8",
            year = "2023",
            type = MediaType.SERIES,
            genres = listOf("Drama", "Sci-Fi", "Mystery"),
            durationOrSeasons = "6 Seasons • 27 Episodes",
            episodes = createSampleEpisodes("Black Mirror", "https://image.tmdb.org/t/p/w500/5UaMirnkEAUjTs74v3ckHRKG5Y.jpg", 6),
            providerName = "NetMirror"
        ),
        MediaItem(
            id = "myst_11",
            title = "Sherlock",
            posterUrl = "https://image.tmdb.org/t/p/w500/7WTsnDMmstA0Z0R8sK8T1tI6C8E.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/hkBaDkMWbLaf8B1rsqRmrCYTszw.jpg",
            overview = "A modern update finds the famous sleuth and his doctor partner solving crime in 21st century London.",
            rating = "9.1",
            year = "2010",
            type = MediaType.SERIES,
            genres = listOf("Crime", "Drama", "Mystery"),
            durationOrSeasons = "4 Seasons • 15 Episodes",
            episodes = createSampleEpisodes("Sherlock", "https://image.tmdb.org/t/p/w500/7WTsnDMmstA0Z0R8sK8T1tI6C8E.jpg", 4),
            providerName = "Plex Stream"
        ),
        MediaItem(
            id = "myst_12",
            title = "Yellowjackets",
            posterUrl = "https://image.tmdb.org/t/p/w500/lFSSVJ2F52whEzW362P7v9eK0m8.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/euYIwmqkmz95mnXvufEmbL69nmr.jpg",
            overview = "A wildly talented high school girl soccer team becomes the unlucky survivors of a plane crash deep in the remote northern wilderness.",
            rating = "8.4",
            year = "2023",
            type = MediaType.SERIES,
            genres = listOf("Drama", "Horror", "Mystery"),
            durationOrSeasons = "2 Seasons • 19 Episodes",
            episodes = createSampleEpisodes("Yellowjackets", "https://image.tmdb.org/t/p/w500/lFSSVJ2F52whEzW362P7v9eK0m8.jpg", 9),
            providerName = "MultiMovies"
        )
    )

    // -----------------------------------------------------------------------------------------
    // Row 6: Newly Added (12 Items)
    // -----------------------------------------------------------------------------------------
    private fun getNewlyAddedItems(): List<MediaItem> = listOf(
        MediaItem(
            id = "new_1",
            title = "The Paradise (2026)",
            posterUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/09/the-paradise-2026-dual-audio-hindi-tamil-movie.jpg",
            backdropUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/09/the-paradise-2026-dual-audio-hindi-tamil-movie.jpg",
            overview = "Dual Audio {Hindi-Tamil} Movie 480p | 720p | 1080p HDTC.",
            rating = "8.2",
            year = "2026",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Drama", "Dual Audio"),
            durationOrSeasons = "2h 15m",
            providerName = "BollyFlix"
        ),
        MediaItem(
            id = "new_2",
            title = "Mahakavya Shri Ramayan Katha (2026)",
            posterUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/09/mahakavya-shri-ramayan-katha-shri-ram-ki-2026-hindi-movie.jpg",
            backdropUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/09/mahakavya-shri-ramayan-katha-shri-ram-ki-2026-hindi-movie.jpg",
            overview = "Hindi Movie 480p | 720p | 1080p HDTC with divine music.",
            rating = "9.1",
            year = "2026",
            type = MediaType.MOVIE,
            genres = listOf("Devotional", "Mythology", "Hindi"),
            durationOrSeasons = "2h 45m",
            providerName = "BollyFlix"
        ),
        MediaItem(
            id = "new_3",
            title = "Hope – Hopeu (2026)",
            posterUrl = "https://image.tmdb.org/t/p/w500/xcU2DzlAUJeFRkY67WZqaoGFSkL.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/7RyHsO4yDXtBv1zUU3mTpHeQ0d5.jpg",
            overview = "Korean sci-fi thriller about rural town inhabitants discovering an extraterrestrial presence.",
            rating = "8.4",
            year = "2026",
            type = MediaType.MOVIE,
            genres = listOf("Thriller", "Mystery", "Korean"),
            durationOrSeasons = "1h 58m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "new_4",
            title = "Chitose Is in the Ramune Bottle",
            posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx198727-BL0JTpg0G8tq.jpg",
            backdropUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx198727-BL0JTpg0G8tq.jpg",
            overview = "Saku Chitose is the top popular student in high school navigating friends, rumors, and adolescent drama.",
            rating = "8.5",
            year = "2025",
            type = MediaType.ANIME,
            genres = listOf("Anime", "School", "Drama"),
            durationOrSeasons = "2nd Cour • 12 Episodes",
            episodes = createSampleEpisodes("Chitose-kun", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx198727-BL0JTpg0G8tq.jpg", 12),
            providerName = "MultiNime"
        ),
        MediaItem(
            id = "new_5",
            title = "Red River (2025)",
            posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx207809-cpS7CAyjN7iP.jpg",
            backdropUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx207809-cpS7CAyjN7iP.jpg",
            overview = "Historical romance and fantasy anime set in the ancient Hittite Empire.",
            rating = "8.7",
            year = "2025",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Romance", "Historical"),
            durationOrSeasons = "Season 1 • 12 Episodes",
            episodes = createSampleEpisodes("Red River", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx207809-cpS7CAyjN7iP.jpg", 12),
            providerName = "MultiNime"
        ),
        MediaItem(
            id = "new_6",
            title = "The Laid-Off Cheat-Granting Mage",
            posterUrl = "https://image.tmdb.org/t/p/w780/jFGqk1PzNzDarkoMDkKYJUtJY0f.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/w780/jFGqk1PzNzDarkoMDkKYJUtJY0f.jpg",
            overview = "Season 1 • Fresh Episodes added. High definition fantasy anime with zero buffering.",
            rating = "8.6",
            year = "2024",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Fantasy", "Action"),
            durationOrSeasons = "Season 1 • 12 Episodes",
            episodes = createSampleEpisodes("The Laid-Off Mage", "https://image.tmdb.org/t/p/w780/jFGqk1PzNzDarkoMDkKYJUtJY0f.jpg", 12),
            providerName = "ToonStream"
        ),
        MediaItem(
            id = "new_7",
            title = "Doraemon: Earth Symphony",
            posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/501.jpg",
            backdropUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/501.jpg",
            overview = "Doraemon, Nobita and friends use the power of music to save planet Earth from harmony-destroying beings.",
            rating = "8.4",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Cartoon", "Kids", "Music", "Comedy"),
            durationOrSeasons = "1h 55m",
            providerName = "ToonStream"
        ),
        MediaItem(
            id = "new_8",
            title = "Twisters (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/b33nnKl1GSFbao4l3fZDDqsMx0F.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/9SSEUrSqhljBMzRe4aBTh17rUaC.jpg",
            overview = "An update to the 1996 film 'Twister', which centered on a pair of storm chasers who risk their lives in an attempt to test an experimental weather alert system.",
            rating = "8.3",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Adventure", "Thriller"),
            durationOrSeasons = "2h 02m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "new_9",
            title = "A Quiet Place: Day One (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/yrpPYKijwd9GQ9um709sqXNK0a0.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/2wT5B6Lz3n8y0n0g6Hq1P0j6Z4c.jpg",
            overview = "Experience the day the world went quiet in this terrifying continuation of the Quiet Place universe.",
            rating = "8.2",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Horror", "Sci-Fi", "Thriller"),
            durationOrSeasons = "1h 39m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "new_10",
            title = "Kingdom of the Planet of the Apes (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/gKkl37BQuKTanygYQG1pyYgLVgf.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/wzJRB4MKi3yK138bEYvAqP695Jn.jpg",
            overview = "Several generations in the future following Caesar's reign, a young ape undertakes a harrowing journey that will lead him to question all that he has been taught about the past.",
            rating = "8.4",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Adventure", "Sci-Fi"),
            durationOrSeasons = "2h 25m",
            providerName = "MultiMovies"
        ),
        MediaItem(
            id = "new_11",
            title = "Bad Boys: Ride or Die (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/nP6RliHjxsz4irTKsxe8FRhKZYl.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/AaV1YIdWKnjAIAOe8UUKBFm327v.jpg",
            overview = "After their late former Captain is framed, Lowrey and Burnett try to clear his name, only to end up on the run themselves.",
            rating = "8.2",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Comedy", "Crime"),
            durationOrSeasons = "1h 55m",
            providerName = "VegaMovies"
        ),
        MediaItem(
            id = "new_12",
            title = "Godzilla x Kong: The New Empire (2024)",
            posterUrl = "https://image.tmdb.org/t/p/w500/bQ2ywAy0z7Zz1H7sN47e3tM1t0q.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/t5zCBSB5xMDKcDqe91qahCOUYVV.jpg",
            overview = "Following their explosive showdown, Godzilla and Kong must reunite against a colossal undiscovered threat hidden within our world.",
            rating = "8.3",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Sci-Fi", "Adventure"),
            durationOrSeasons = "1h 55m",
            providerName = "BollyFlix"
        )
    )

    val soloLevelingEpisodeLinks = listOf(
        "https://vcloud.beer/qviclulvvpqaq0i", // Ep 1
        "https://vcloud.beer/vjbsjlc_y_isvhe", // Ep 2
        "https://vcloud.beer/ewbbsa5ablyva5k", // Ep 3
        "https://vcloud.beer/ko0zs8zom1u_ya4", // Ep 4
        "https://vcloud.beer/qrzmqbgrwssbcns", // Ep 5
        "https://vcloud.beer/ty6uz0dy5y2tmoo", // Ep 6
        "https://vcloud.beer/gb598mth8xahtyh", // Ep 7
        "https://vcloud.beer/y68ykvdy28yyhhd", // Ep 8
        "https://vcloud.beer/ouxexcuqqe_i7vb", // Ep 9
        "https://vcloud.beer/o3u_iewj-fi3wa1", // Ep 10
        "https://vcloud.beer/piqtqjjrkq2m_mt", // Ep 11
        "https://vcloud.beer/vvvv9znp9kx_vtt"  // Ep 12
    )

    private fun createSampleEpisodes(
        titlePrefix: String,
        thumbUrl: String,
        count: Int,
        episodeLinks: List<String> = emptyList()
    ): List<Episode> {
        return (1..count).map { epNum ->
            val link = episodeLinks.getOrNull(epNum - 1).orEmpty()
            Episode(
                episodeNumber = epNum,
                title = "Episode $epNum: $titlePrefix",
                thumbnail = thumbUrl,
                duration = "24m",
                streamSources = emptyList(),
                embedUrls = if (link.isNotEmpty()) listOf(link) else emptyList(),
                pageUrl = link
            )
        }
    }
}
