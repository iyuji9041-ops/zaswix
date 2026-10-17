package com.zaswis.play.extractor

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
    val type: String = "Movie",
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
