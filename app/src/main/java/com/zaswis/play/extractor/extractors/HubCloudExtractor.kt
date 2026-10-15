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
        try {
            val html = fetchHtml(url, referer) ?: return emptyList()

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
                    foundUrl.contains(".mkv") || foundUrl.contains(".mp4") || foundUrl.contains("s3.aws")) {
                    links.add(
                        ExtractorLink(
                            source = "HubCloud R2",
                            name = "Fast Cloud Stream",
                            url = foundUrl,
                            quality = "1080p",
                            referer = url
                        )
                    )
                } else if (foundUrl.contains("pixeldrain")) {
                    val pxlId = foundUrl.substringAfterLast("/")
                    links.add(
                        ExtractorLink(
                            source = "HubCloud Pixel",
                            name = "High Speed Mirror",
                            url = "https://pixeldrain.com/api/file/$pxlId?download",
                            quality = "1080p",
                            referer = "https://pixeldrain.com/"
                        )
                    )
                }
            }

            // 3. Check for embedded var pxl
            extractPxlUrl(targetHtml)?.let { pxlLink ->
                val pxlId = pxlLink.substringAfterLast("/")
                links.add(
                    ExtractorLink(
                        source = "HubCloud Pixel",
                        name = "Direct CDN",
                        url = "https://pixeldrain.com/api/file/$pxlId?download",
                        quality = "1080p",
                        referer = "https://pixeldrain.com/"
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
        return links
    }
}
