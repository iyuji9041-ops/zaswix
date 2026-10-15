package com.zaswis.play.extractor.extractors

import com.zaswis.play.extractor.ExtractorApi
import com.zaswis.play.extractor.model.ExtractorLink
import okhttp3.Request
import java.net.URI
import kotlin.random.Random

class DoodStreamExtractor : ExtractorApi() {

    override val name: String = "DoodStream"
    override val mainUrl: String = "dood"
    override val requiresReferer: Boolean = true

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("dood") || lower.contains("ds2play")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val embedUrl = if (url.contains("/e/")) url else url.replace("/d/", "/e/")
        val hostUri = URI(embedUrl)
        val host = "${hostUri.scheme}://${hostUri.host}"

        val html = fetchHtml(embedUrl, referer ?: host) ?: return emptyList()

        val md5Pattern = Regex("""(?:/pass_md5/|/pass_file/)([^'"]+)""")
        val md5Match = md5Pattern.find(html) ?: return emptyList()
        val md5Path = md5Match.groupValues[1]

        val passUrl = "$host/pass_md5/$md5Path"

        val tokenBody = try {
            val req = Request.Builder()
                .url(passUrl)
                .header("User-Agent", defaultUserAgent)
                .header("Referer", embedUrl)
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) resp.body?.string()?.trim() else null
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val randomStr = generateRandomString(10)
        val expiry = System.currentTimeMillis()
        val directUrl = "$tokenBody$randomStr?token=$md5Path&expiry=$expiry"

        return listOf(
            ExtractorLink(
                source = name,
                name = "720p HD",
                url = directUrl,
                referer = host,
                quality = "720p HD",
                isM3u8 = false,
                headers = mapOf(
                    "Referer" to host,
                    "User-Agent" to defaultUserAgent
                )
            )
        )
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
