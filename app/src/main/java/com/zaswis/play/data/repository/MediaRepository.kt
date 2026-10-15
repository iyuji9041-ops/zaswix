package com.zaswis.play.data.repository

import com.zaswis.play.data.model.Episode
import com.zaswis.play.data.model.MediaCategory
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.model.MediaType
import com.zaswis.play.data.model.StreamSource
import com.zaswis.play.extractor.CloudstreamProvider
import com.zaswis.play.extractor.ExtractorRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

    private val workingStreams = emptyList<StreamSource>()

    suspend fun searchLiveVegaMovies(query: String): List<MediaItem> {
        return try {
            CloudstreamProvider.search(query)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getFeaturedHero(pluginId: String = "bollyflix"): MediaItem {
        return when (pluginId) {
            "bollyflix" -> MediaItem(
                id = "bf_hero",
                title = "Drishyam: The Conclusion (2026)",
                posterUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/10/drishyam-the-conclusion-2026-hindi-movie.jpg",
                backdropUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/10/drishyam-the-conclusion-2026-hindi-movie.jpg",
                overview = "Vijay Salgaonkar and his family face the ultimate test as the investigation reopens with shocking new evidence and revelations that test the limits of family loyalty.",
                rating = "8.9",
                year = "2026",
                type = MediaType.MOVIE,
                genres = listOf("Crime", "Thriller", "Mystery", "Hindi Movie"),
                durationOrSeasons = "2h 38m",
                pageUrl = "https://bollyflix.photo/download-drishyam-the-conclusion-2026-hindi-movie/",
                streamSources = workingStreams,
                providerName = "BollyFlix"
            )
            "toonstream" -> MediaItem(
                id = "ts_hero",
                title = "The Laid-Off Cheat-Granting Mage",
                posterUrl = "https://image.tmdb.org/t/p/w780/jFGqk1PzNzDarkoMDkKYJUtJY0f.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/w780/jFGqk1PzNzDarkoMDkKYJUtJY0f.jpg",
                overview = "A talented support mage gets dismissed by his adventuring party only to discover that his unique cheat buffs make him the most powerful solo adventurer alive.",
                rating = "8.6",
                year = "2024",
                type = MediaType.ANIME,
                genres = listOf("Anime", "Fantasy", "Action", "Adventure"),
                durationOrSeasons = "Season 1 • 12 Episodes",
                episodes = createSampleEpisodes("The Laid-Off Cheat-Granting Mage", "https://image.tmdb.org/t/p/w780/jFGqk1PzNzDarkoMDkKYJUtJY0f.jpg", 12),
                streamSources = workingStreams,
                providerName = "ToonStream"
            )
            "vegamovies" -> MediaItem(
                id = "vm_hero",
                title = "Deadpool & Wolverine (2024)",
                posterUrl = "https://vegamovies.gallery/wp-content/uploads/2024/10/Deadpool-Wolverine-2024-Hindi-ORG.jpg",
                backdropUrl = "https://vegamovies.gallery/wp-content/uploads/2024/10/Deadpool-Wolverine-2024-Hindi-ORG.jpg",
                overview = "Dual Audio (Hindi ORG DD5.1 + English ORG DD5.1) 480p [590MB] | 720p [1.3GB] | 1080p [2.6GB] & 2160p 4K SDR",
                rating = "8.9",
                year = "2024",
                type = MediaType.MOVIE,
                genres = listOf("Action", "Sci-Fi", "Superhero", "Dual Audio"),
                durationOrSeasons = "2h 08m",
                pageUrl = "https://vegamovies.gallery/download-deadpool-wolverine-2024-hindi-org-dd5-1-english-480p-720p-1080p-2160p-4k-sdr/",
                streamSources = workingStreams,
                providerName = "VegaMovies"
            )
            "multinime" -> MediaItem(
                id = "mn_hero",
                title = "DARK MACHINE THE ANIMATION",
                posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx179876-Wg62CAzp5WQ1.jpg",
                backdropUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx179876-Wg62CAzp5WQ1.jpg",
                overview = "In a dark dystopian future, warriors pilot sentient mecha combat armor to defend the last bastion of humanity against mysterious bio-mechanical invaders.",
                rating = "9.1",
                year = "2025",
                type = MediaType.ANIME,
                genres = listOf("Anime", "Mecha", "Sci-Fi", "English Sub & Dub"),
                durationOrSeasons = "Season 1 • 12 Episodes",
                episodes = createSampleEpisodes("DARK MACHINE", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx179876-Wg62CAzp5WQ1.jpg", 12),
                streamSources = workingStreams,
                providerName = "MultiNime"
            )
            "multimovies" -> MediaItem(
                id = "mm_hero",
                title = "Dune: Part Two (2024)",
                posterUrl = "https://image.tmdb.org/t/p/original/7p0QLfjFx1iJzJ6iB82mp7E9qIH.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/original/7p0QLfjFx1iJzJ6iB82mp7E9qIH.jpg",
                overview = "Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family, facing a choice between love and the fate of the universe.",
                rating = "8.8",
                year = "2024",
                type = MediaType.MOVIE,
                genres = listOf("Sci-Fi", "Adventure", "Action", "Dual Audio"),
                durationOrSeasons = "2h 46m",
                streamSources = workingStreams,
                providerName = "MultiMovies"
            )
            "netmirror" -> MediaItem(
                id = "nm_hero",
                title = "The Boys Season 4",
                posterUrl = "https://image.tmdb.org/t/p/w500/27A45499GfS3e5Gk4tC2zV6k7rF.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/w500/27A45499GfS3e5Gk4tC2zV6k7rF.jpg",
                overview = "The world is on the brink. Victoria Neuman is closer than ever to the Oval Office and under the muscly thumb of Homelander.",
                rating = "8.7",
                year = "2024",
                type = MediaType.SERIES,
                genres = listOf("Superhero", "Action", "Dark Comedy"),
                durationOrSeasons = "4 Seasons • 32 Episodes",
                episodes = createSampleEpisodes("The Boys S4", "https://image.tmdb.org/t/p/w500/27A45499GfS3e5Gk4tC2zV6k7rF.jpg", 8),
                streamSources = workingStreams,
                providerName = "NetMirror"
            )
            else -> MediaItem(
                id = "plex_hero",
                title = "Interstellar",
                posterUrl = "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg",
                overview = "When Earth becomes uninhabitable in the future, a team of explorers undertakes the most important mission in human history.",
                rating = "8.7",
                year = "2014",
                type = MediaType.MOVIE,
                genres = listOf("Sci-Fi", "Drama", "Adventure"),
                durationOrSeasons = "2h 49m",
                streamSources = workingStreams,
                providerName = "Plex Stream"
            )
        }
    }

    fun getHomeCategories(pluginId: String = "bollyflix"): Flow<List<MediaCategory>> = flow {
        val categories = when (pluginId) {
            "bollyflix" -> getBollyFlixCategories()
            "toonstream" -> getToonStreamCategories()
            "vegamovies" -> getVegaMoviesCategories()
            "multinime" -> getMultiNimeCategories()
            "multimovies" -> getMultiMoviesCategories()
            "netmirror" -> getNetMirrorCategories()
            else -> getPlexCategories()
        }
        emit(categories)
    }

    fun getAllSearchItems(): Flow<List<MediaItem>> = flow {
        val all = (getBollyFlixCategories() + getToonStreamCategories() + getVegaMoviesCategories() +
                getMultiNimeCategories() + getMultiMoviesCategories() + getNetMirrorCategories() +
                getPlexCategories()).flatMap { it.items }.distinctBy { it.id }
        emit(all)
    }

    private fun getBollyFlixCategories(): List<MediaCategory> {
        return listOf(
            MediaCategory(
                title = "🎬 Latest Bollywood Releases (BollyFlix)",
                items = listOf(
                    MediaItem(
                        id = "bf_1",
                        title = "Drishyam: The Conclusion (2026)",
                        posterUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/10/drishyam-the-conclusion-2026-hindi-movie.jpg",
                        overview = "Hindi Movie 480p | 720p | 1080p HDTC HC-ESub",
                        rating = "8.9",
                        year = "2026",
                        type = MediaType.MOVIE,
                        genres = listOf("Crime", "Thriller", "Hindi"),
                        durationOrSeasons = "2h 38m",
                        pageUrl = "https://bollyflix.photo/download-drishyam-the-conclusion-2026-hindi-movie/",
                        streamSources = workingStreams,
                        providerName = "BollyFlix"
                    ),
                    MediaItem(
                        id = "bf_2",
                        title = "The Paradise (2026)",
                        posterUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/09/the-paradise-2026-dual-audio-hindi-tamil-movie.jpg",
                        overview = "Dual Audio {Hindi-Tamil} Movie 480p | 720p | 1080p HDTC",
                        rating = "8.2",
                        year = "2026",
                        type = MediaType.MOVIE,
                        genres = listOf("Action", "Drama", "Dual Audio"),
                        durationOrSeasons = "2h 15m",
                        streamSources = workingStreams,
                        providerName = "BollyFlix"
                    ),
                    MediaItem(
                        id = "bf_3",
                        title = "Mahakavya Shri Ramayan Katha (2026)",
                        posterUrl = "https://bflixcdn.xyz/wp-content/uploads/2026/09/mahakavya-shri-ramayan-katha-shri-ram-ki-2026-hindi-movie.jpg",
                        overview = "Hindi Movie 480p | 720p | 1080p HDTC",
                        rating = "9.1",
                        year = "2026",
                        type = MediaType.MOVIE,
                        genres = listOf("Devotional", "Mythology", "Hindi"),
                        durationOrSeasons = "2h 45m",
                        streamSources = workingStreams,
                        providerName = "BollyFlix"
                    )
                )
            ),
            MediaCategory(
                title = "⚡ Dual Audio Hollywood (Hindi Dubbed)",
                items = listOf(
                    MediaItem(
                        id = "bf_4",
                        title = "Deadpool & Wolverine (2024)",
                        posterUrl = "https://vegamovies.gallery/wp-content/uploads/2024/10/Deadpool-Wolverine-2024-Hindi-ORG.jpg",
                        overview = "Dual Audio [Hindi (Studio Dub) & English] 1080p BluRay",
                        rating = "8.2",
                        year = "2024",
                        type = MediaType.MOVIE,
                        genres = listOf("Action", "Comedy", "Dual Audio"),
                        durationOrSeasons = "2h 08m",
                        pageUrl = "https://vegamovies.gallery/download-deadpool-wolverine-2024-hindi-org-dd5-1-english-480p-720p-1080p-2160p-4k-sdr/",
                        streamSources = workingStreams,
                        providerName = "BollyFlix"
                    ),
                    MediaItem(
                        id = "bf_5",
                        title = "Oppenheimer (2023)",
                        posterUrl = "https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg",
                        overview = "Dual Audio {Hindi-English} 480p | 720p | 1080p",
                        rating = "8.9",
                        year = "2023",
                        type = MediaType.MOVIE,
                        genres = listOf("Drama", "History", "Biography"),
                        durationOrSeasons = "3h 00m",
                        pageUrl = "https://vegamovies.gallery/download-oppenheimer-2023-hindi-english-480p-720p-1080p/",
                        streamSources = workingStreams,
                        providerName = "BollyFlix"
                    )
                )
            )
        )
    }

    private fun getToonStreamCategories(): List<MediaCategory> {
        val toonItem1 = MediaItem(
            id = "ts_1",
            title = "The Laid-Off Cheat-Granting Mage",
            posterUrl = "https://image.tmdb.org/t/p/w780/jFGqk1PzNzDarkoMDkKYJUtJY0f.jpg",
            overview = "Season 1 • EP 2 Added. High definition anime with zero buffering.",
            rating = "8.6",
            year = "2024",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Fantasy", "Action"),
            durationOrSeasons = "Season 1 • 12 Episodes",
            episodes = createSampleEpisodes("The Laid-Off Cheat-Granting Mage", "https://image.tmdb.org/t/p/w780/jFGqk1PzNzDarkoMDkKYJUtJY0f.jpg", 12),
            streamSources = workingStreams,
            providerName = "ToonStream"
        )
        val toonItem2 = MediaItem(
            id = "ts_2",
            title = "Solo Leveling",
            posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/bx151807-it355ZgzquUd.png",
            backdropUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/banner/151807-3i864hQpDqC6.jpg",
            overview = "In a world where hunters battle deadly monsters, Sung Jinwoo discovers a system to grow infinitely.",
            rating = "8.8",
            year = "2024",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Action", "Supernatural"),
            durationOrSeasons = "Season 1 • 12 Episodes",
            episodes = createSampleEpisodes(
                "Solo Leveling",
                "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/bx151807-it355ZgzquUd.png",
                12,
                soloLevelingEpisodeLinks
            ),
            pageUrl = "https://nexdrive.beer/genxfm784776346841/",
            providerName = "VegaMovies"
        )
        val toonItem3 = MediaItem(
            id = "ts_3",
            title = "Doraemon: Earth Symphony",
            posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/501.jpg",
            overview = "Doraemon, Nobita and friends use the power of music to save planet Earth from harmony-destroying beings.",
            rating = "8.4",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Cartoon", "Kids", "Music", "Comedy"),
            durationOrSeasons = "1h 55m",
            streamSources = workingStreams,
            providerName = "ToonStream"
        )
        return listOf(
            MediaCategory(
                title = "⛩️ Trending Anime & Fresh Episodes (ToonStream)",
                items = listOf(toonItem1, toonItem2)
            ),
            MediaCategory(
                title = "📺 Popular Animation & Cartoons",
                items = listOf(toonItem3, toonItem1)
            )
        )
    }

    private fun getVegaMoviesCategories(): List<MediaCategory> {
        val reacherItem = MediaItem(
            id = "vm_1",
            title = "Reacher : Season 4 (2026)",
            posterUrl = "https://image.tmdb.org/t/p/w1280/5t1gXdmCqaHPcTwH8roaqV0cmPz.jpg",
            overview = "Download Reacher Season 4 Prime Video Hindi Dubbed 480p 720p 1080p WEB-DL",
            rating = "8.8",
            year = "2026",
            type = MediaType.SERIES,
            genres = listOf("Action", "Crime", "Thriller"),
            durationOrSeasons = "Season 4 • 8 Episodes",
            episodes = createSampleEpisodes("Reacher S4", "https://image.tmdb.org/t/p/w1280/5t1gXdmCqaHPcTwH8roaqV0cmPz.jpg", 8),
            streamSources = workingStreams,
            providerName = "VegaMovies"
        )
        val spiderItem = MediaItem(
            id = "vm_2",
            title = "Spider-Man: Brand New Day (2026)",
            posterUrl = "https://image.tmdb.org/t/p/w500/4MsQLF4n2yBZbyFsUxDyn4oBd5B.jpg",
            overview = "Download Spider-Man: Brand New Day (2026) iMAX-WEB-DL {Hindi-English} 480p | 720p | 1080p | 4K SDR + HDR",
            rating = "8.9",
            year = "2026",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Adventure", "Dual Audio"),
            durationOrSeasons = "2h 25m",
            streamSources = workingStreams,
            providerName = "VegaMovies"
        )
        val hopeItem = MediaItem(
            id = "vm_3",
            title = "Hope – Hopeu (2026)",
            posterUrl = "https://image.tmdb.org/t/p/w500/xcU2DzlAUJeFRkY67WZqaoGFSkL.jpg",
            overview = "Download Hope – Hopeu (2026) WEB-DL (Korean With English Subtitles) Full Movie 480p | 720p | 1080p",
            rating = "8.4",
            year = "2026",
            type = MediaType.MOVIE,
            genres = listOf("Thriller", "Mystery", "Korean"),
            durationOrSeasons = "1h 58m",
            streamSources = workingStreams,
            providerName = "VegaMovies"
        )
        val marshalsItem = MediaItem(
            id = "vm_4",
            title = "Marshals (Season 1 & 2)",
            posterUrl = "https://image.tmdb.org/t/p/w500/nInfaveN1iFpRIcMGNKENoVVUT2.jpg",
            overview = "Download Marshals (Season 1 & 2) {S02E01-2 Added} JioHotstar : Dual Audio {Hindi-English} 480p 720p 1080p",
            rating = "8.6",
            year = "2025",
            type = MediaType.SERIES,
            genres = listOf("Action", "Drama", "Western"),
            durationOrSeasons = "2 Seasons • 16 Episodes",
            episodes = createSampleEpisodes("Marshals S2", "https://image.tmdb.org/t/p/w500/nInfaveN1iFpRIcMGNKENoVVUT2.jpg", 8),
            streamSources = workingStreams,
            providerName = "VegaMovies"
        )
        return listOf(
            MediaCategory(
                title = "🔥 4K & Dual Audio Blockbusters (VegaMovies)",
                items = listOf(spiderItem, hopeItem, reacherItem, marshalsItem)
            ),
            MediaCategory(
                title = "📺 Hot OTT Web Series (Hindi Dubbed)",
                items = listOf(reacherItem, marshalsItem)
            )
        )
    }

    private fun getMultiNimeCategories(): List<MediaCategory> {
        val m1 = MediaItem(
            id = "mn_1",
            title = "DARK MACHINE THE ANIMATION",
            posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx179876-Wg62CAzp5WQ1.jpg",
            overview = "Top rated Mecha and Action anime with instant CDN stream and Sub/Dub options.",
            rating = "9.1",
            year = "2025",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Mecha", "Action"),
            durationOrSeasons = "1 Season • 12 Episodes",
            episodes = createSampleEpisodes("DARK MACHINE", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx179876-Wg62CAzp5WQ1.jpg", 12),
            streamSources = workingStreams,
            providerName = "MultiNime"
        )
        val m2 = MediaItem(
            id = "mn_2",
            title = "Red River",
            posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx207809-cpS7CAyjN7iP.jpg",
            overview = "Historical romance and fantasy anime set in the ancient Hittite Empire.",
            rating = "8.7",
            year = "2025",
            type = MediaType.ANIME,
            genres = listOf("Anime", "Romance", "Historical"),
            durationOrSeasons = "1 Season",
            episodes = createSampleEpisodes("Red River", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx207809-cpS7CAyjN7iP.jpg", 12),
            streamSources = workingStreams,
            providerName = "MultiNime"
        )
        val m3 = MediaItem(
            id = "mn_3",
            title = "Chitose Is in the Ramune Bottle",
            posterUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx198727-BL0JTpg0G8tq.jpg",
            overview = "Saku Chitose is the top popular student in high school navigating friends, rumors, and adolescent drama.",
            rating = "8.5",
            year = "2025",
            type = MediaType.ANIME,
            genres = listOf("Anime", "School", "Drama"),
            durationOrSeasons = "2nd Cour",
            episodes = createSampleEpisodes("Chitose-kun", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx198727-BL0JTpg0G8tq.jpg", 12),
            streamSources = workingStreams,
            providerName = "MultiNime"
        )
        return listOf(
            MediaCategory(
                title = "⛩️ Trending Anime Series (MultiNime)",
                items = listOf(m1, m2, m3)
            )
        )
    }

    private fun getMultiMoviesCategories(): List<MediaCategory> {
        val mm1 = MediaItem(
            id = "mm_1",
            title = "Dune: Part Two (2024)",
            posterUrl = "https://image.tmdb.org/t/p/original/7p0QLfjFx1iJzJ6iB82mp7E9qIH.jpg",
            overview = "Watch free online in HD, Dual Audio and Hindi dubbed on MultiMovies.",
            rating = "8.8",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Sci-Fi", "Action", "Dual Audio"),
            durationOrSeasons = "2h 46m",
            streamSources = workingStreams,
            providerName = "MultiMovies"
        )
        val mm2 = MediaItem(
            id = "mm_2",
            title = "Deadpool & Wolverine",
            posterUrl = "https://image.tmdb.org/t/p/original/ysChlwbKSeTrvqPZhbFYvN28pcy.jpg",
            overview = "Deadpool and Wolverine team up to save the Marvel Cinematic Universe.",
            rating = "8.2",
            year = "2024",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Comedy", "Sci-Fi"),
            durationOrSeasons = "2h 08m",
            streamSources = workingStreams,
            providerName = "MultiMovies"
        )
        return listOf(
            MediaCategory(
                title = "⚡ Multi-Server HD Movies (MultiMovies)",
                items = listOf(mm1, mm2)
            )
        )
    }

    private fun getNetMirrorCategories(): List<MediaCategory> {
        val nm1 = MediaItem(
            id = "nm_1",
            title = "Stranger Things 5",
            posterUrl = "https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPfGn8AIqMGskD.jpg",
            overview = "The final battle in Hawkins begins as the Upside Down breaches our world.",
            rating = "8.8",
            year = "2024",
            type = MediaType.SERIES,
            genres = listOf("Sci-Fi", "Horror", "Mystery"),
            durationOrSeasons = "4 Seasons • 34 Episodes",
            episodes = createSampleEpisodes("Stranger Things", "https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPfGn8AIqMGskD.jpg", 8),
            streamSources = workingStreams,
            providerName = "NetMirror"
        )
        return listOf(
            MediaCategory(
                title = "📺 OTT Platforms Content (NetMirror)",
                items = listOf(nm1)
            )
        )
    }

    private fun getPlexCategories(): List<MediaCategory> {
        val p1 = MediaItem(
            id = "plex_1",
            title = "The Dark Knight",
            posterUrl = "https://image.tmdb.org/t/p/w500/qJ2tW6WMUDux911r6m7haRef0WH.jpg",
            overview = "Batman raises the stakes in his war on crime with the help of Lt. Jim Gordon and District Attorney Harvey Dent.",
            rating = "9.0",
            year = "2008",
            type = MediaType.MOVIE,
            genres = listOf("Action", "Crime", "Drama"),
            durationOrSeasons = "2h 32m",
            streamSources = workingStreams,
            providerName = "Plex Stream"
        )
        return listOf(
            MediaCategory(
                title = "🍿 Free Movies & TV On Demand (Plex)",
                items = listOf(p1)
            )
        )
    }

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
