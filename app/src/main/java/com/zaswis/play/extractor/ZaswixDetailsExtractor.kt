package com.zaswis.play.extractor

import com.zaswis.play.extractor.model.VideoQualityOption

/**
 * Delegating extractor for universal playback stream resolution.
 * All site-specific scrapers have been decoupled into external plugins.
 */
object ZaswixDetailsExtractor {

    suspend fun resolveStreamQualities(
        streamUrl: String,
        headers: Map<String, String>
    ): List<VideoQualityOption> = UniversalStreamResolver.resolveStreamQualities(streamUrl, headers)

    suspend fun resolveServerToStream(server: ServerOption): ResolvedStream =
        UniversalStreamResolver.resolveServerToStream(server)

    suspend fun estimateFileSize(url: String, headers: Map<String, String>): Pair<Long?, String> =
        UniversalStreamResolver.estimateFileSize(url, headers)

    fun formatBytes(bytes: Long): String = UniversalStreamResolver.formatBytes(bytes)
}
