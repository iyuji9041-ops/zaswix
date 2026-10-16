package com.zaswis.play.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.model.MediaType

@Entity(tableName = "watchlist")
data class MediaEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val posterUrl: String,
    val backdropUrl: String = "",
    val overview: String = "",
    val rating: String = "8.5",
    val year: String = "2024",
    val type: String = MediaType.MOVIE.name,
    val genres: String = "",
    val durationOrSeasons: String = "2h 15m",
    val providerName: String = "Zaswix Stream",
    val pageUrl: String = "",
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toMediaItem(): MediaItem {
        val mediaType = try {
            MediaType.valueOf(type)
        } catch (_: Exception) {
            MediaType.MOVIE
        }
        val genresList = if (genres.isBlank()) {
            emptyList()
        } else {
            genres.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return MediaItem(
            id = id,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            overview = overview,
            rating = rating,
            year = year,
            type = mediaType,
            genres = genresList,
            durationOrSeasons = durationOrSeasons,
            providerName = providerName,
            pageUrl = pageUrl
        )
    }

    companion object {
        fun fromMediaItem(item: MediaItem): MediaEntity {
            return MediaEntity(
                id = item.id,
                title = item.title,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                overview = item.overview,
                rating = item.rating,
                year = item.year,
                type = item.type.name,
                genres = item.genres.joinToString(","),
                durationOrSeasons = item.durationOrSeasons,
                providerName = item.providerName,
                pageUrl = item.pageUrl,
                addedAt = System.currentTimeMillis()
            )
        }
    }
}
