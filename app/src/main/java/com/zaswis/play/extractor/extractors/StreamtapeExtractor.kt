package com.zaswis.play.extractor.extractors

import com.zaswis.play.extractor.ExtractorApi
import com.zaswis.play.extractor.model.ExtractorLink

class StreamtapeExtractor : ExtractorApi() {

    override val name: String = "Streamtape"
    override val mainUrl: String = "streamtape"
    override val requiresReferer: Boolean = true

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("streamtape.com") ||
                lower.contains("streamta.pe") ||
                lower.contains("streamtape.net") ||
                lower.contains("streamtape.to")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val embedUrl = if (url.contains("/e/")) url else url.replace("/v/", "/e/")
        val html = fetchHtml(embedUrl, referer ?: "https://streamtape.com/") ?: return emptyList()

        val directUrl = extractStreamtapeUrl(html) ?: return emptyList()

        return listOf(
            ExtractorLink(
                source = name,
                name = "1080p Full HD",
                url = directUrl,
                referer = "https://streamtape.com/",
                quality = "1080p HD",
                isM3u8 = false,
                headers = mapOf(
                    "Referer" to "https://streamtape.com/",
                    "User-Agent" to defaultUserAgent
                )
            )
        )
    }

    private fun extractStreamtapeUrl(html: String): String? {
        // Pattern 1: document.getElementById('robotlink').innerHTML = '...' + ('...').substring(x)
        val complexPattern = Regex(
            """document\.getElementById\(['"](?:robotlink|videolink|norobotlink)['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(['"]([^'"]+)['"]\)\.substring\((\d+)\)"""
        )
        val complexMatch = complexPattern.find(html)
        if (complexMatch != null) {
            val part1 = complexMatch.groupValues[1]
            val part2 = complexMatch.groupValues[2]
            val offset = complexMatch.groupValues[3].toIntOrNull() ?: 0
            val subPart = if (offset < part2.length) part2.substring(offset) else part2
            val fullPath = part1 + subPart
            return formatUrl(fullPath)
        }

        // Pattern 2: id="robotlink" style="display:none;">//streamtape.com/get_video?...
        val directPattern = Regex("""id=['"](?:norobotlink|robotlink|videolink)['"][^>]*>([^<]+)<""")
        val directMatch = directPattern.find(html)
        if (directMatch != null) {
            val fullPath = directMatch.groupValues[1].trim()
            if (fullPath.isNotEmpty()) {
                return formatUrl(fullPath)
            }
        }

        return null
    }

    private fun formatUrl(path: String): String {
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> "https://streamtape.com$path"
            else -> "https://$path"
        }
    }
}
