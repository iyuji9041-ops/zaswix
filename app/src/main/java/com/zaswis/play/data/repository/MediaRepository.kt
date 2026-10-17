package com.zaswis.play.data.repository

import android.content.Context
import com.zaswis.play.data.local.AppDatabase
import com.zaswis.play.data.local.MediaDao
import com.zaswis.play.data.local.MediaEntity
import com.zaswis.play.data.model.MediaCategory
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.model.MediaType
import com.zaswis.play.data.model.StreamSource
import com.zaswis.play.extractor.CloudstreamProvider
import com.zaswis.play.extractor.ExtractorRegistry
import com.zaswis.play.extractor.MediaDetailsPayload
import com.zaswis.play.extractor.ScrapedCategoryRow
import com.zaswis.play.extractor.ScrapedMediaItem
import com.zaswis.play.plugin.PluginManager
import com.zaswis.play.plugin.toModelMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Dynamic MediaRepository:
 * Fully decoupled from hardcoded scrapers. Operates as a Legal Empty Shell that
 * dynamically collects and invokes loaded plugins via PluginManager.loadedPlugins.
 */
class MediaRepository(context: Context? = null) {

    private var mediaDao: MediaDao? = context?.let { AppDatabase.getInstance(it).mediaDao() }

    fun initDatabase(context: Context) {
        if (mediaDao == null) {
            mediaDao = AppDatabase.getInstance(context).mediaDao()
        }
    }

    suspend fun addToWatchlist(item: MediaItem) {
        withContext(Dispatchers.IO) {
            mediaDao?.insert(MediaEntity.fromMediaItem(item))
        }
    }

    suspend fun removeFromWatchlist(id: String) {
        withContext(Dispatchers.IO) {
            mediaDao?.deleteById(id)
        }
    }

    fun getWatchlist(): Flow<List<MediaItem>> {
        return mediaDao?.getWatchlist()?.map { entities ->
            entities.map { it.toMediaItem() }
        } ?: flow { emit(emptyList()) }
    }

    fun isItemInWatchlist(id: String): Flow<Boolean> {
        return mediaDao?.isInWatchlist(id) ?: flow { emit(false) }
    }

    fun isInWatchlist(id: String): Flow<Boolean> = isItemInWatchlist(id)

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

        // 2. If media has pageUrl, resolve live with generic embed scraper
        if (mediaItem.pageUrl.isNotEmpty()) {
            try {
                if (extractorRegistry.canHandle(mediaItem.pageUrl)) {
                    val directResolved = extractorRegistry.extract(mediaItem.pageUrl)
                    extractedSources.addAll(directResolved)
                } else {
                    val resolvedFromPage = CloudstreamProvider.resolveMoviePage(mediaItem.pageUrl, extractorRegistry)
                    extractedSources.addAll(resolvedFromPage)
                }
            } catch (_: Exception) {}
        }

        val finalSources = (extractedSources + mediaItem.streamSources).filter { it.url.isNotBlank() }
        finalSources.distinctBy { it.url }
    }

    /**
     * Dynamically searches across all active plugins in PluginManager.loadedPlugins.
     * Returns emptyList if no plugins are loaded.
     */
    suspend fun searchLivePlugins(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val plugins = PluginManager.loadedPlugins.value
        if (plugins.isEmpty() || query.isBlank()) return@withContext emptyList()

        coroutineScope {
            plugins.map { plugin ->
                async {
                    try {
                        plugin.search(query).map { it.toModelMediaItem(plugin.providerName) }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten().distinctBy { it.pageUrl }
        }
    }

    /**
     * Resolves rich media details dynamically using loaded plugins in PluginManager.loadedPlugins.
     * Returns null if no plugins are loaded or no matching plugin is found.
     */
    suspend fun loadMediaDetails(url: String, providerName: String = ""): MediaDetailsPayload? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val plugins = PluginManager.loadedPlugins.value
        if (plugins.isEmpty()) return@withContext null

        try {
            val matchingPlugin = plugins.find { plugin ->
                plugin.providerName.equals(providerName, ignoreCase = true) ||
                url.contains(plugin.providerName.lowercase().replace("\\s+".toRegex(), ""), ignoreCase = true)
            } ?: plugins.firstOrNull { plugin ->
                url.contains(plugin.providerName.take(4), ignoreCase = true)
            } ?: plugins.firstOrNull()

            if (matchingPlugin != null) {
                val details = matchingPlugin.getDetails(url)
                if (details.servers.isNotEmpty() || details.episodes.isNotEmpty() || details.synopsis.isNotEmpty()) {
                    return@withContext MediaDetailsPayload(
                        title = details.title,
                        posterUrl = "",
                        backdropUrl = "",
                        synopsis = details.synopsis,
                        rating = details.rating,
                        year = "",
                        genres = details.genres,
                        servers = details.servers,
                        episodes = details.episodes
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * StateFlow exposing live heroBannerItems dynamically derived from loaded plugins.
     * Starts empty for a Legal Empty Shell.
     */
    private val _heroBannerItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val heroBannerItems: StateFlow<List<MediaItem>> = _heroBannerItems.asStateFlow()

    fun getHeroBannerItems(): List<MediaItem> = _heroBannerItems.value

    fun getFeaturedHero(pluginId: String = "all"): MediaItem? {
        val heroes = _heroBannerItems.value
        if (heroes.isEmpty()) return null
        return if (pluginId.isNotBlank() && pluginId != "all") {
            heroes.find { it.providerName.equals(pluginId, ignoreCase = true) } ?: heroes[0]
        } else {
            heroes[0]
        }
    }

    /**
     * Homepage categories: Dynamically queries PluginManager.loadedPlugins.
     * If loadedPlugins is empty, returns empty list.
     * If plugins are loaded, iterates through them and calls plugin.getHomePage() dynamically.
     */
    fun getHomeCategories(pluginId: String = "all"): Flow<List<MediaCategory>> = flow {
        val allPlugins = PluginManager.loadedPlugins.value
        if (allPlugins.isEmpty()) {
            _heroBannerItems.value = emptyList()
            emit(emptyList())
            return@flow
        }

        val pluginsToRun = if (pluginId.isBlank() || pluginId == "all") {
            allPlugins
        } else {
            allPlugins.filter {
                it.providerName.equals(pluginId, ignoreCase = true) ||
                it.providerName.lowercase().replace("\\s+".toRegex(), "") == pluginId.lowercase()
            }
        }.ifEmpty { allPlugins }

        coroutineScope {
            val payloads = pluginsToRun.map { plugin ->
                async {
                    try {
                        plugin.getHomePage()
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()

            val combinedHeroes = payloads.flatMap { it.heroBannerItems }
                .distinctBy { it.title }
                .take(10)
                .map { it.toMediaItem() }

            if (combinedHeroes.isNotEmpty()) {
                _heroBannerItems.value = combinedHeroes
            }

            val categories = payloads.flatMap { it.categoryRows }
                .filter { it.items.isNotEmpty() }
                .map { it.toMediaCategory() }

            emit(categories)
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

    fun getAllSearchItems(): Flow<List<MediaItem>> = flow {
        emit(_heroBannerItems.value)
    }
}
