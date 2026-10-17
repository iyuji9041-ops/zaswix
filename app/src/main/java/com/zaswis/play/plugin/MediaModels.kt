package com.zaswis.play.plugin

import com.zaswis.play.extractor.EpisodeItem
import com.zaswis.play.extractor.HomePagePayload
import com.zaswis.play.extractor.ScrapedCategoryRow
import com.zaswis.play.extractor.ScrapedMediaItem
import com.zaswis.play.extractor.ServerOption

/**
 * Common media models container for compatibility with external plugins
 * referencing `com.zaswis.play.plugin.MediaModels`.
 */
open class MediaModels {

    data class MediaItem(
        val title: String = "",
        val poster: String = "",
        val url: String = "",
        val type: String = "movie"
    )

    data class MediaDetails(
        val title: String = "",
        val synopsis: String = "",
        val rating: String = "",
        val servers: List<ServerOption> = emptyList(),
        val episodes: List<EpisodeItem> = emptyList(),
        val genres: List<String> = emptyList()
    )

    data class ServerOption(
        val id: String = "",
        val serverName: String = "",
        val embedUrl: String = "",
        val quality: String = "HD",
        val referer: String = ""
    )

    data class EpisodeItem(
        val season: Int = 1,
        val episode: Int = 1,
        val title: String = "",
        val url: String = "",
        val thumbnailUrl: String = "",
        val runtime: String = "",
        val description: String = ""
    )

    data class HomePagePayload(
        val heroBannerItems: List<ScrapedMediaItem> = emptyList(),
        val categoryRows: List<ScrapedCategoryRow> = emptyList()
    )

    data class StreamSource(
        val serverName: String = "Server 1",
        val quality: String = "1080p",
        val url: String = "",
        val referer: String = "",
        val headers: Map<String, String> = emptyMap(),
        val isM3u8: Boolean = url.contains(".m3u8", ignoreCase = true)
    )
}
