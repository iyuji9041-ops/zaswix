package com.zaswis.play.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast

/**
 * Native Android DownloadManager Helper for Zaswix.
 * Downloads video streams to public Downloads folder with proper headers and notifications.
 */
object DownloadHelper {

    fun downloadVideo(
        context: Context,
        url: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
        quality: String = "HD"
    ): Long {
        if (url.isBlank()) {
            Toast.makeText(context, "Cannot download: stream URL is empty", Toast.LENGTH_SHORT).show()
            return -1L
        }

        try {
            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri).apply {
                val displayTitle = if (quality.isNotEmpty()) "$title ($quality)" else title
                setTitle(displayTitle)
                setDescription("Downloading $displayTitle with Zaswix")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                // Pass User-Agent and Referer headers required for authenticated video CDNs
                headers.forEach { (key, value) ->
                    if (key.isNotBlank() && value.isNotBlank()) {
                        addRequestHeader(key, value)
                    }
                }

                // Determine file extension
                val extension = when {
                    url.contains(".m3u8", ignoreCase = true) -> ".m3u8"
                    url.contains(".mp4", ignoreCase = true) -> ".mp4"
                    url.contains(".mkv", ignoreCase = true) -> ".mkv"
                    else -> ".mp4"
                }

                val sanitizedTitle = title
                    .replace("[^a-zA-Z0-9.-]".toRegex(), "_")
                    .take(60)
                val sanitizedQuality = quality.replace("[^a-zA-Z0-9]".toRegex(), "")
                val fileName = if (sanitizedQuality.isNotEmpty()) {
                    "${sanitizedTitle}_${sanitizedQuality}$extension"
                } else {
                    "$sanitizedTitle$extension"
                }

                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Zaswix/$fileName"
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)

            Toast.makeText(
                context,
                "Download started: $title ${if (quality.isNotEmpty()) "[$quality]" else ""}",
                Toast.LENGTH_SHORT
            ).show()

            return downloadId
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Download failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
            return -1L
        }
    }
}
