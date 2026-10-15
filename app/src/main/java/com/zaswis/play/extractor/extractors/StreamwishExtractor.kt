package com.zaswis.play.extractor.extractors

import com.zaswis.play.extractor.ExtractorApi
import com.zaswis.play.extractor.model.ExtractorLink
import com.zaswis.play.extractor.utils.JsUnpacker

class StreamwishExtractor : ExtractorApi() {

    override val name: String = "Streamwish"
    override val mainUrl: String = "streamwish"
    override val requiresReferer: Boolean = true

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("streamwish") ||
                lower.contains("wishfast") ||
                lower.contains("strwish") ||
                lower.contains("awish")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val embedUrl = if (url.contains("/e/")) url else url.replace("/f/", "/e/")
        val html = fetchHtml(embedUrl, referer ?: "https://streamwish.to/") ?: return emptyList()

        val unpacked = if (JsUnpacker.detect(html)) {
            JsUnpacker.unpack(html) ?: html
        } else {
            html
        }

        val m3u8Pattern = Regex("""(?:file|src)\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
        val match = m3u8Pattern.find(unpacked) ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpacked)

        val m3u8Url = match?.let {
            if (it.groupValues.size > 1 && it.groupValues[1].isNotEmpty()) it.groupValues[1] else it.value
        } ?: return emptyList()

        return listOf(
            ExtractorLink(
                source = name,
                name = "Fast HLS Stream",
                url = m3u8Url,
                referer = "https://streamwish.to/",
                quality = "1080p HD",
                isM3u8 = true,
                headers = mapOf(
                    "Referer" to "https://streamwish.to/",
                    "User-Agent" to defaultUserAgent
                )
            )
        )
    }
}
