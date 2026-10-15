package com.zaswis.play.extractor.extractors

import com.zaswis.play.extractor.ExtractorApi
import com.zaswis.play.extractor.model.ExtractorLink

/**
 * PixelDrain Extractor (Ported directly from Cloudstream Core)
 * Directly resolves PixelDrain file links into high-speed direct streaming endpoints
 */
class PixelDrainExtractor : ExtractorApi() {
    override val name: String = "PixelDrain"
    override val mainUrl: String = "pixeldrain"

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("pixeldrain.com") || lower.contains("pixeldrain.dev")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val fileId = when {
            url.contains("/u/") -> url.substringAfter("/u/").substringBefore("?").substringBefore("/")
            url.contains("/api/file/") -> url.substringAfter("/api/file/").substringBefore("?").substringBefore("/")
            else -> url.substringAfterLast("/")
        }

        if (fileId.isBlank()) return emptyList()

        val directDownloadUrl = "https://pixeldrain.com/api/file/$fileId?download"
        return listOf(
            ExtractorLink(
                source = name,
                name = "Direct High Speed Stream",
                url = directDownloadUrl,
                referer = "https://pixeldrain.com/",
                quality = "1080p",
                headers = mapOf(
                    "User-Agent" to defaultUserAgent,
                    "Referer" to "https://pixeldrain.com/"
                )
            )
        )
    }
}
