package com.zaswis.play.extractor.model

import com.zaswis.play.data.model.StreamSource

data class VideoQualityOption(
    val quality: String, // "1080p", "720p", "480p", "360p", "Auto / HD"
    val url: String,
    val sizeBytes: Long? = null,
    val formattedSize: String = "",
    val bandwidth: Long = 0L,
    val headers: Map<String, String> = emptyMap()
)

data class ExtractorLink(
    val source: String,
    val name: String,
    val url: String,
    val referer: String = "",
    val quality: String = "1080p",
    val isM3u8: Boolean = url.contains(".m3u8", ignoreCase = true),
    val headers: Map<String, String> = emptyMap(),
    val sizeBytes: Long? = null,
    val formattedSize: String = "",
    val availableQualities: List<VideoQualityOption> = emptyList()
) {
    fun toStreamSource(): StreamSource {
        val mergedHeaders = mutableMapOf<String, String>()
        if (referer.isNotEmpty()) {
            mergedHeaders["Referer"] = referer
        }
        mergedHeaders.putAll(headers)

        return StreamSource(
            serverName = "$source • $name",
            quality = quality,
            url = url,
            referer = referer,
            headers = mergedHeaders,
            isM3u8 = isM3u8
        )
    }
}
