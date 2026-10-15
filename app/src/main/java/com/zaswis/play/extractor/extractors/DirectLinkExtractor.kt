package com.zaswis.play.extractor.extractors

import com.zaswis.play.extractor.ExtractorApi
import com.zaswis.play.extractor.model.ExtractorLink

class DirectLinkExtractor : ExtractorApi() {

    override val name: String = "Direct CDN"
    override val mainUrl: String = ""

    override fun canHandle(url: String): Boolean {
        val clean = url.lowercase().substringBefore("?")
        return clean.endsWith(".mp4") ||
                clean.endsWith(".m3u8") ||
                clean.endsWith(".mkv") ||
                clean.endsWith(".webm") ||
                url.contains("plyr.io") ||
                url.contains("zencdn.net") ||
                url.contains("archive.org")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val isM3u8 = url.contains(".m3u8", ignoreCase = true)
        val quality = if (url.contains("1080p")) "1080p HD"
        else if (url.contains("720p")) "720p HD"
        else if (url.contains("480p")) "480p SD"
        else if (isM3u8) "Auto Multi-Quality"
        else "Original Quality"

        val headers = mutableMapOf("User-Agent" to defaultUserAgent)
        referer?.let { headers["Referer"] = it }

        return listOf(
            ExtractorLink(
                source = name,
                name = quality,
                url = url,
                referer = referer ?: "",
                quality = quality,
                isM3u8 = isM3u8,
                headers = headers
            )
        )
    }
}
