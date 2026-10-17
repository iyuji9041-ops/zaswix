package com.zaswis.play.extractor

import com.zaswis.play.extractor.model.VideoQualityOption

/**
 * Common data models for media items, categories, details, and streams.
 * Decoupled from any specific scraper or website implementation.
 */
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
    val heroBannerItems: List<ScrapedMediaItem> = emptyList(),
    val categoryRows: List<ScrapedCategoryRow> = emptyList()
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

data class ServerOption(
    val id: String,
    val serverName: String,
    val embedUrl: String,
    val quality: String = "HD",
    val referer: String
)

data class MediaDetailsPayload(
    val title: String,
    val posterUrl: String,
    val backdropUrl: String,
    val synopsis: String,
    val rating: String,
    val year: String,
    val genres: List<String>,
    val servers: List<ServerOption>,
    val episodes: List<EpisodeItem>,
    val downloadLinks: List<ServerOption> = emptyList()
)

data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val isDirectStream: Boolean, // true = ExoPlayer (.m3u8/.mp4), false = AdBlocked WebView (Embed)
    val qualities: List<VideoQualityOption> = emptyList(),
    val estimatedSize: String = ""
)
