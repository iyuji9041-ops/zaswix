package com.zaswis.play.extractor

import com.zaswis.play.extractor.model.VideoQualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Universal Stream Resolver: Resolves playable video streams (HLS .m3u8, MP4, or Web Embeds)
 * and estimates quality variants and file sizes.
 * Completely generic and decoupled from any specific content scraper.
 */
object UniversalStreamResolver {

    private val client = OkHttpClient.Builder()
        .dns(com.zaswis.play.network.CloudflareDns.dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "Unknown Size"
        val kilo = 1024.0
        val mega = kilo * 1024.0
        val giga = mega * 1024.0
        return when {
            bytes >= giga -> String.format(Locale.US, "%.2f GB", bytes / giga)
            bytes >= mega -> String.format(Locale.US, "%.1f MB", bytes / mega)
            bytes >= kilo -> String.format(Locale.US, "%.0f KB", bytes / kilo)
            else -> "$bytes B"
        }
    }

    /**
     * Size Estimation Utility using OkHttp.
     * Makes a HEAD request to fetch Content-Length, with fallback to Range request.
     */
    suspend fun estimateFileSize(url: String, headers: Map<String, String>): Pair<Long?, String> = withContext(Dispatchers.IO) {
        try {
            val headReq = Request.Builder().url(url).head()
            headers.forEach { (k, v) -> headReq.header(k, v) }
            val headRes = client.newCall(headReq.build()).execute()
            val contentLength = headRes.header("Content-Length")?.toLongOrNull()
            if (contentLength != null && contentLength > 0) {
                return@withContext Pair(contentLength, formatBytes(contentLength))
            }
        } catch (_: Exception) {}

        // Fallback: Range request bytes=0-0 to read Content-Range
        try {
            val rangeReq = Request.Builder().url(url).header("Range", "bytes=0-0")
            headers.forEach { (k, v) -> if (k != "Range") rangeReq.header(k, v) }
            val rangeRes = client.newCall(rangeReq.build()).execute()
            val contentRange = rangeRes.header("Content-Range")
            if (contentRange != null && contentRange.contains("/")) {
                val totalBytes = contentRange.substringAfterLast("/").trim().toLongOrNull()
                if (totalBytes != null && totalBytes > 0) {
                    return@withContext Pair(totalBytes, formatBytes(totalBytes))
                }
            }
        } catch (_: Exception) {}

        Pair(null, "")
    }

    /**
     * Resolves all available video qualities (1080p, 720p, 480p, etc.)
     * For .m3u8 playlists: parses #EXT-X-STREAM-INF and estimates size from bandwidth.
     * For .mp4 streams: computes file size and generates standard quality profiles.
     */
    suspend fun resolveStreamQualities(
        streamUrl: String,
        headers: Map<String, String>
    ): List<VideoQualityOption> = withContext(Dispatchers.IO) {
        val qualityList = mutableListOf<VideoQualityOption>()

        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            try {
                val req = Request.Builder().url(streamUrl)
                headers.forEach { (k, v) -> req.header(k, v) }
                val m3u8Text = client.newCall(req.build()).execute().use { it.body?.string().orEmpty() }

                if (m3u8Text.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val lines = m3u8Text.lines()
                    var currentBandwidth = 0L
                    var currentResolution = ""

                    for (i in lines.indices) {
                        val line = lines[i].trim()
                        if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                            val bwMatcher = Pattern.compile("""BANDWIDTH=(\d+)""").matcher(line)
                            if (bwMatcher.find()) {
                                currentBandwidth = bwMatcher.group(1)?.toLongOrNull() ?: 0L
                            }
                            val resMatcher = Pattern.compile("""RESOLUTION=(\d+x\d+)""").matcher(line)
                            if (resMatcher.find()) {
                                currentResolution = resMatcher.group(1).orEmpty()
                            }
                        } else if (line.isNotEmpty() && !line.startsWith("#")) {
                            val resolvedUrl = try {
                                java.net.URI(streamUrl).resolve(line).toString()
                            } catch (_: Exception) {
                                if (line.startsWith("http")) line else "${streamUrl.substringBeforeLast("/")}/$line"
                            }

                            val height = currentResolution.substringAfter("x", "").toIntOrNull()
                            val qualityLabel = when {
                                height != null && height >= 1080 -> "1080p Full HD"
                                height != null && height >= 720 -> "720p HD"
                                height != null && height >= 480 -> "480p SD"
                                height != null && height >= 360 -> "360p Data Saver"
                                height != null -> "${height}p"
                                currentBandwidth > 3_000_000L -> "1080p Full HD"
                                currentBandwidth > 1_500_000L -> "720p HD"
                                currentBandwidth > 700_000L -> "480p SD"
                                else -> "HD Stream"
                            }

                            val estBytes = if (currentBandwidth > 0) (currentBandwidth / 8.0 * 7200).toLong() else 0L
                            val estFormatted = if (estBytes > 0) "${formatBytes(estBytes)} (2h est.)" else "Adaptive"

                            qualityList.add(
                                VideoQualityOption(
                                    quality = qualityLabel,
                                    url = resolvedUrl,
                                    sizeBytes = if (estBytes > 0) estBytes else null,
                                    formattedSize = estFormatted,
                                    bandwidth = currentBandwidth,
                                    headers = headers
                                )
                            )

                            currentBandwidth = 0L
                            currentResolution = ""
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (qualityList.isEmpty()) {
            val (sizeBytes, formattedSize) = estimateFileSize(streamUrl, headers)
            val baseSize = sizeBytes ?: (1200L * 1024L * 1024L)

            qualityList.add(
                VideoQualityOption(
                    quality = "1080p Full HD",
                    url = streamUrl,
                    sizeBytes = baseSize,
                    formattedSize = if (formattedSize.isNotEmpty()) formattedSize else formatBytes(baseSize),
                    bandwidth = 4_500_000L,
                    headers = headers
                )
            )
            qualityList.add(
                VideoQualityOption(
                    quality = "720p HD",
                    url = streamUrl,
                    sizeBytes = (baseSize * 0.6).toLong(),
                    formattedSize = formatBytes((baseSize * 0.6).toLong()),
                    bandwidth = 2_200_000L,
                    headers = headers
                )
            )
            qualityList.add(
                VideoQualityOption(
                    quality = "480p SD (Data Saver)",
                    url = streamUrl,
                    sizeBytes = (baseSize * 0.35).toLong(),
                    formattedSize = formatBytes((baseSize * 0.35).toLong()),
                    bandwidth = 1_000_000L,
                    headers = headers
                )
            )
        }

        qualityList.distinctBy { it.quality }
    }

    suspend fun resolveServerToStream(server: ServerOption): ResolvedStream = withContext(Dispatchers.IO) {
        val defaultHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to server.embedUrl
        )

        // 0. Instant Bypass: If URL is already a direct playable stream (.mp4, .m3u8, .mkv, or Google CDN)
        val lowerUrl = server.embedUrl.lowercase()
        if (lowerUrl.contains("video-downloads.googleusercontent.com") ||
            lowerUrl.contains(".m3u8") ||
            lowerUrl.contains(".mp4") ||
            lowerUrl.contains(".mkv")
        ) {
            val qualities = resolveStreamQualities(server.embedUrl, defaultHeaders)
            return@withContext ResolvedStream(
                url = server.embedUrl,
                headers = defaultHeaders,
                isDirectStream = true,
                qualities = qualities,
                estimatedSize = qualities.firstOrNull()?.formattedSize.orEmpty()
            )
        }

        // 1. Check ExtractorRegistry for supported hosts (HubCloud, Filemoon, DoodStream, Streamwish, Streamtape, GDFlix, PixelDrain)
        val registry = ExtractorRegistry()
        if (registry.canHandle(server.embedUrl)) {
            try {
                val extracted = registry.extract(server.embedUrl, server.referer)
                val first = extracted.firstOrNull()
                if (first != null && (first.url.contains(".m3u8") || first.url.contains(".mp4") || first.url.contains(".mkv") || first.url.contains("video-downloads.googleusercontent.com") || first.url.contains("pixeldrain.com"))) {
                    val finalHeaders = if (first.headers.isNotEmpty()) first.headers else defaultHeaders
                    val qualities = resolveStreamQualities(first.url, finalHeaders)
                    return@withContext ResolvedStream(
                        url = first.url,
                        headers = finalHeaders,
                        isDirectStream = true,
                        qualities = qualities,
                        estimatedSize = qualities.firstOrNull()?.formattedSize.orEmpty()
                    )
                }
            } catch (_: Exception) {}
        }

        try {
            val req = Request.Builder()
                .url(server.embedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", server.referer)
                .build()

            val html = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val cleanHtml = html.replace("\\/", "/")

            // 2. Check for direct .m3u8 or .mp4 or .mkv in unescaped HTML
            val streamRegex = Pattern.compile("""["'](https?://[^"'\s<>]+\.(?:m3u8|mp4|mkv)[^"'\s<>]*)["']""")
            val matcher = streamRegex.matcher(cleanHtml)
            if (matcher.find()) {
                val directUrl = matcher.group(1)!!.replace("\\", "")
                val qualities = resolveStreamQualities(directUrl, defaultHeaders)
                val primaryQuality = qualities.firstOrNull()
                val estimatedSize = primaryQuality?.formattedSize.orEmpty()

                return@withContext ResolvedStream(
                    url = directUrl,
                    headers = defaultHeaders,
                    isDirectStream = true,
                    qualities = qualities,
                    estimatedSize = estimatedSize
                )
            }

            // 3. Check for packed JS
            val unpacked = unpackJs(html) ?: unpackJs(cleanHtml)
            if (unpacked != null) {
                val cleanUnpacked = unpacked.replace("\\/", "/")
                val unpackedMatcher = streamRegex.matcher(cleanUnpacked)
                if (unpackedMatcher.find()) {
                    val directUrl = unpackedMatcher.group(1)!!.replace("\\", "")
                    val qualities = resolveStreamQualities(directUrl, defaultHeaders)
                    val primaryQuality = qualities.firstOrNull()
                    val estimatedSize = primaryQuality?.formattedSize.orEmpty()

                    return@withContext ResolvedStream(
                        url = directUrl,
                        headers = defaultHeaders,
                        isDirectStream = true,
                        qualities = qualities,
                        estimatedSize = estimatedSize
                    )
                }
            }

            // 4. Check for nested iframes that ExtractorRegistry can handle
            val iframePattern = Pattern.compile("""<iframe[^>]+src=["'](https?://[^"']+)["']""", Pattern.CASE_INSENSITIVE)
            val ifrMatcher = iframePattern.matcher(cleanHtml)
            while (ifrMatcher.find()) {
                val ifrUrl = ifrMatcher.group(1)!!
                if (registry.canHandle(ifrUrl)) {
                    val extracted = registry.extract(ifrUrl, server.embedUrl)
                    val first = extracted.firstOrNull()
                    if (first != null && (first.url.contains(".m3u8") || first.url.contains(".mp4") || first.url.contains(".mkv"))) {
                        val finalHeaders = if (first.headers.isNotEmpty()) first.headers else defaultHeaders
                        val qualities = resolveStreamQualities(first.url, finalHeaders)
                        return@withContext ResolvedStream(
                            url = first.url,
                            headers = finalHeaders,
                            isDirectStream = true,
                            qualities = qualities,
                            estimatedSize = qualities.firstOrNull()?.formattedSize.orEmpty()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Fallback: Return the Embed URL for WebView Player
        ResolvedStream(
            url = server.embedUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to server.referer),
            isDirectStream = false,
            qualities = listOf(
                VideoQualityOption(
                    quality = "Auto (Embed Web Player)",
                    url = server.embedUrl,
                    formattedSize = "Dynamic Stream",
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to server.referer)
                )
            ),
            estimatedSize = "Web Embed"
        )
    }

    private fun unpackJs(html: String): String? {
        val packedRegex = Pattern.compile(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            Pattern.DOTALL
        )
        val matcher = packedRegex.matcher(html)
        if (!matcher.find()) return null

        val payload = matcher.group(1) ?: return null
        val radix = matcher.group(2)?.toIntOrNull() ?: 36
        val symtab = matcher.group(4)?.split("|") ?: return null

        val wordRegex = Pattern.compile("""\b\w+\b""")
        val wordMatcher = wordRegex.matcher(payload)
        val sb = StringBuffer()

        while (wordMatcher.find()) {
            val word = wordMatcher.group(0)!!
            val index = word.toIntOrNull(radix)
            val replacement = if (index != null && index < symtab.size && symtab[index].isNotEmpty()) {
                symtab[index]
            } else {
                word
            }
            wordMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement))
        }
        wordMatcher.appendTail(sb)
        return sb.toString()
    }
}
