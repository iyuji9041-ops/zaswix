package com.zaswis.play.extractor.extractors

import com.zaswis.play.extractor.ExtractorApi
import com.zaswis.play.extractor.model.ExtractorLink
import okhttp3.Request

/**
 * GDFlix / FastDL Extractor (Ported directly from Cloudstream CSX Bollyflix)
 * Resolves GDFlix, GDLink, and fastdlserver redirection mirrors into direct streams.
 */
class GDFlixExtractor : ExtractorApi() {
    override val name: String = "GDFlix"
    override val mainUrl: String = "gdflix"

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("gdflix") || lower.contains("gdlink") || lower.contains("fastdl")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        try {
            val html = fetchHtml(url, referer) ?: return emptyList()

            val aPattern = Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            for (match in aPattern.findAll(html)) {
                val href = match.groupValues[1]
                val text = match.groupValues[2]

                if (text.contains("Instant DL", ignoreCase = true) || href.contains("instant", ignoreCase = true)) {
                    try {
                        val req = Request.Builder().url(href).header("User-Agent", defaultUserAgent).build()
                        val resp = client.newCall(req).execute()
                        val loc = resp.header("location") ?: resp.request.url.toString()
                        val actual = loc.substringAfter("url=", loc)
                        if (actual.startsWith("http")) {
                            links.add(
                                ExtractorLink(
                                    source = "GDFlix Instant",
                                    name = "Instant Fast DL",
                                    url = actual,
                                    quality = "1080p"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        links.add(
                            ExtractorLink(
                                source = "GDFlix Instant",
                                name = "Instant Fast DL",
                                url = href,
                                quality = "1080p"
                            )
                        )
                    }
                } else if (text.contains("CLOUD DOWNLOAD", ignoreCase = true) || text.contains("FSL V2", ignoreCase = true) || text.contains("DIRECT", ignoreCase = true)) {
                    links.add(
                        ExtractorLink(
                            source = "GDFlix Cloud",
                            name = "Cloud Direct Server",
                            url = href,
                            quality = "1080p"
                        )
                    )
                } else if (href.contains("pixeldrain")) {
                    val pxlId = href.substringAfterLast("/")
                    links.add(
                        ExtractorLink(
                            source = "GDFlix Pixeldrain",
                            name = "Fast Mirror",
                            url = "https://pixeldrain.com/api/file/$pxlId?download",
                            quality = "1080p",
                            referer = "https://pixeldrain.com/"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return links
    }
}
