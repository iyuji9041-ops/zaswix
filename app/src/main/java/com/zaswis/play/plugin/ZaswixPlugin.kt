package com.zaswis.play.plugin

import com.zaswis.play.extractor.EpisodeItem
import com.zaswis.play.extractor.HomePagePayload
import com.zaswis.play.extractor.ServerOption

/**
 * Lightweight data class representing a media item inside a plugin.
 */
data class MediaItem(
    val title: String,
    val poster: String,
    val url: String,
    val type: String = "movie"
)

/**
 * Rich details payload returned by a plugin scraper.
 */
data class MediaDetails(
    val title: String,
    val synopsis: String,
    val rating: String,
    val servers: List<ServerOption> = emptyList(),
    val episodes: List<EpisodeItem> = emptyList(),
    val genres: List<String> = emptyList()
)

/**
 * Standard contract for dynamic Zaswix plugins loaded via DexClassLoader.
 * Provides mutual fallback for both getProviderName() and getName() so that
 * plugins implementing either providerName or name (or neither) load without AbstractMethodError.
 */
interface ZaswixPlugin {
    val name: String
        get() = try {
            val m = javaClass.methods.firstOrNull { it.name == "getProviderName" && it.parameterCount == 0 }
            (m?.invoke(this) as? String) ?: javaClass.simpleName
        } catch (_: Throwable) {
            javaClass.simpleName
        }

    val providerName: String
        get() = try {
            val m = javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
            (m?.invoke(this) as? String) ?: name
        } catch (_: Throwable) {
            name
        }

    val version: String
        get() = "1.0.0"

    suspend fun getHomePage(): HomePagePayload
    suspend fun search(query: String): List<MediaItem>
    suspend fun getDetails(url: String): MediaDetails
}

/**
 * Converter extension to map plugin MediaItem to app's model MediaItem.
 */
fun MediaItem.toModelMediaItem(provider: String = ""): com.zaswis.play.data.model.MediaItem {
    val mediaType = when (type.lowercase()) {
        "series", "show", "tv", "web series" -> com.zaswis.play.data.model.MediaType.SERIES
        "anime" -> com.zaswis.play.data.model.MediaType.ANIME
        else -> com.zaswis.play.data.model.MediaType.MOVIE
    }
    return com.zaswis.play.data.model.MediaItem(
        id = "${provider.lowercase()}_${url.hashCode()}",
        title = title,
        posterUrl = poster,
        backdropUrl = poster,
        pageUrl = url,
        providerName = provider,
        type = mediaType
    )
}
