package com.zaswis.play.data.model

data class MediaItem(
    val id: String,
    val title: String,
    val posterUrl: String,
    val backdropUrl: String = "",
    val overview: String = "",
    val rating: String = "8.5",
    val year: String = "2024",
    val type: MediaType = MediaType.MOVIE,
    val genres: List<String> = emptyList(),
    val durationOrSeasons: String = "2h 15m",
    val episodes: List<Episode> = emptyList(),
    val streamSources: List<StreamSource> = emptyList(),
    val providerName: String = "Zaswix Stream",
    val pageUrl: String = "",
    val embedUrls: List<String> = emptyList()
)

enum class MediaType {
    MOVIE,
    SERIES,
    ANIME
}

data class Episode(
    val episodeNumber: Int,
    val title: String,
    val thumbnail: String = "",
    val duration: String = "45m",
    val streamSources: List<StreamSource> = emptyList(),
    val pageUrl: String = "",
    val embedUrls: List<String> = emptyList()
)
