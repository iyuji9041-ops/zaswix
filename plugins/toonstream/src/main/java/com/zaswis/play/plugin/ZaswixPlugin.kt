package com.zaswis.play.plugin

// Minimal standalone contract declarations matching the main app for DexClassLoader loading

data class MediaItem(
    val title: String,
    val poster: String,
    val url: String,
    val type: String = "movie"
)

data class ServerOption(
    val id: String,
    val serverName: String,
    val embedUrl: String,
    val quality: String = "HD",
    val referer: String
)

data class EpisodeItem(
    val season: Int,
    val episode: Int,
    val title: String,
    val url: String,
    val thumbnailUrl: String = "",
    val runtime: String = "",
    val description: String = ""
)

data class MediaDetails(
    val title: String,
    val synopsis: String,
    val rating: String,
    val servers: List<com.zaswis.play.extractor.ServerOption> = emptyList(),
    val episodes: List<com.zaswis.play.extractor.EpisodeItem> = emptyList(),
    val genres: List<String> = emptyList()
)

interface ZaswixPlugin {
    val providerName: String
    val version: String

    suspend fun getHomePage(): com.zaswis.play.extractor.HomePagePayload
    suspend fun search(query: String): List<MediaItem>
    suspend fun getDetails(url: String): MediaDetails
}
