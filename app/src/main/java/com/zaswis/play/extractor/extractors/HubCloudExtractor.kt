package com.zaswis.play.extractor.extractors

import android.util.Base64
import com.zaswis.play.extractor.ExtractorApi
import com.zaswis.play.extractor.model.ExtractorLink

/**
 * HubCloud / V-Cloud Extractor (Ported directly from Cloudstream CSX VegaMovies)
 * Decodes double atob obfuscation, extracts fast Cloudflare R2 / S3 storage streams,
 * and handles BuzzServer and PixelDrain download links.
 */
class HubCloudExtractor : ExtractorApi() {
    override val name: String = "HubCloud / V-Cloud"
    override val mainUrl: String = "hubcloud"

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("hubcloud") || lower.contains("vcloud") || lower.contains("v-cloud")
    }

    private fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        val match = regex.find(html)?.groupValues?.get(1) ?: return null
        return try {
            val first = String(Base64.decode(match, Base64.DEFAULT), Charsets.UTF_8)
            val second = String(Base64.decode(first, Base64.DEFAULT), Charsets.UTF_8)
            second
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPxlUrl(html: String): String? {
        val regex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
        return regex.find(html)?.groupValues?.get(1)
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        val effectiveReferer = referer ?: url
        try {
            val html = fetchHtml(url, effectiveReferer) ?: return emptyList()

            // 1. Double atob redirect unpacker
            var targetPageUrl = extractDoubleAtob(html)
            var targetHtml = html

            if (targetPageUrl != null && targetPageUrl.isNotEmpty()) {
                if (!targetPageUrl.startsWith("http")) {
                    val base = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore("/")
                    targetPageUrl = base + targetPageUrl
                }
                val secondHtml = fetchHtml(targetPageUrl, url)
                if (secondHtml != null) {
                    targetHtml = secondHtml
                }
            }

            // 2. Discover direct cloud and CDN download links
            val hrefPattern = Regex("""href=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            for (match in hrefPattern.findAll(targetHtml)) {
                val foundUrl = match.groupValues[1]
                if (foundUrl.contains("cloudflarestorage.com") || foundUrl.contains(".r2.") ||
                    foundUrl.contains(".mkv") || foundUrl.contains(".mp4") || foundUrl.contains(".m3u8") || foundUrl.contains("s3.aws")) {
                    val isM3u8 = foundUrl.contains(".m3u8", ignoreCase = true)
                    links.add(
                        ExtractorLink(
                            source = "HubCloud R2",
                            name = if (isM3u8) "HLS Adaptive Stream" else "Fast Cloud Stream",
                            url = foundUrl,
                            quality = "1080p",
                            referer = effectiveReferer,
                            isM3u8 = isM3u8,
                            headers = mapOf(
                                "Referer" to effectiveReferer,
                                "User-Agent" to defaultUserAgent
                            )
                        )
                    )
                } else if (foundUrl.contains("pixeldrain")) {
                    val pxlId = foundUrl.substringAfterLast("/")
                    val directPxl = "https://pixeldrain.com/api/file/$pxlId?download"
                    links.add(
                        ExtractorLink(
                            source = "HubCloud Pixel",
                            name = "High Speed Mirror",
                            url = directPxl,
                            quality = "1080p",
                            referer = "https://pixeldrain.com/",
                            isM3u8 = false,
                            headers = mapOf(
                                "Referer" to "https://pixeldrain.com/",
                                "User-Agent" to defaultUserAgent
                            )
                        )
                    )
                }
            }

            // 3. Check for embedded var pxl
            extractPxlUrl(targetHtml)?.let { pxlLink ->
                val pxlId = pxlLink.substringAfterLast("/")
                val directPxl = "https://pixeldrain.com/api/file/$pxlId?download"
                links.add(
                    ExtractorLink(
                        source = "HubCloud Pixel",
                        name = "Direct CDN",
                        url = directPxl,
                        quality = "1080p",
                        referer = "https://pixeldrain.com/",
                        isM3u8 = false,
                        headers = mapOf(
                            "Referer" to "https://pixeldrain.com/",
                            "User-Agent" to defaultUserAgent
                        )
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
        return links
    }
}
