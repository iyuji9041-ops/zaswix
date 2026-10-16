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
 */
interface ZaswixPlugin {
    val providerName: String
    val version: String

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
