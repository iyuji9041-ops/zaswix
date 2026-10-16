package com.zaswis.play.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.model.StreamSource
import com.zaswis.play.data.repository.MediaRepository
import com.zaswis.play.extractor.EpisodeItem
import com.zaswis.play.extractor.MediaDetailsPayload
import com.zaswis.play.extractor.ServerOption
import com.zaswis.play.ui.components.ZaswixIcons
import com.zaswis.play.ui.theme.NetflixBlack
import com.zaswis.play.ui.theme.NetflixCardBg
import com.zaswis.play.ui.theme.NetflixDarkGrey
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite
import kotlinx.coroutines.launch

@Composable
fun DetailsScreen(
    item: MediaItem,
    mediaRepository: MediaRepository = remember { MediaRepository() },
    onBackClick: () -> Unit,
    onPlayClick: (item: MediaItem, servers: List<ServerOption>, selectedServer: ServerOption?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        mediaRepository.initDatabase(context)
    }
    val isInWatchlist by mediaRepository.isInWatchlist(item.id).collectAsState(initial = false)

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var currentStreams by remember(item.id) { mutableStateOf(item.streamSources) }
    var isAutoResolving by remember(item.id) { mutableStateOf(false) }

    var detailsPayload by remember(item.id) { mutableStateOf<MediaDetailsPayload?>(null) }
    var isLoadingDetails by remember(item.id) { mutableStateOf(false) }
    var loadingEpisodeUrl by remember(item.id) { mutableStateOf<String?>(null) }

    // Automatic Background Link Extraction (Cloudstream style: auto-scrape and resolve)
    LaunchedEffect(item.id) {
        val isDetailsProvider = item.pageUrl.contains("multimovies", ignoreCase = true) ||
                item.providerName.contains("multimovies", ignoreCase = true) ||
                item.pageUrl.contains("toonstream", ignoreCase = true) ||
                item.providerName.contains("toonstream", ignoreCase = true)

        if (isDetailsProvider && item.pageUrl.isNotEmpty()) {
            isLoadingDetails = true
            val payload = mediaRepository.loadMediaDetails(item.pageUrl, item.providerName)
            if (payload != null) {
                detailsPayload = payload
            }
            isLoadingDetails = false
        } else if (item.embedUrls.isNotEmpty() || item.pageUrl.isNotEmpty()) {
            isAutoResolving = true
            val resolved = mediaRepository.autoResolveMediaStreams(item)
            if (resolved.isNotEmpty()) {
                currentStreams = resolved
            }
            isAutoResolving = false
        }
    }

    val displayTitle = detailsPayload?.title?.ifEmpty { item.title } ?: item.title
    val displayOverview = detailsPayload?.synopsis?.ifEmpty { item.overview } ?: item.overview
    val displayGenres = if (!detailsPayload?.genres.isNullOrEmpty()) detailsPayload!!.genres else item.genres
    val displayRating = detailsPayload?.rating?.ifEmpty { item.rating } ?: item.rating
    val displayYear = detailsPayload?.year?.ifEmpty { item.year } ?: item.year
    val displayBackdrop = detailsPayload?.backdropUrl?.ifEmpty { item.backdropUrl }?.ifEmpty { item.posterUrl }
        ?: item.backdropUrl.ifEmpty { item.posterUrl }
    val extractedEpisodes = detailsPayload?.episodes.orEmpty()
    val extractedServers = detailsPayload?.servers.orEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // Backdrop with gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                AsyncImage(
                    model = displayBackdrop,
                    contentDescription = displayTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.5f),
                                    Color.Transparent,
                                    NetflixBlack
                                )
                            )
                        )
                )

                // Back Button
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .padding(top = 16.dp, start = 12.dp)
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                ) {
                    Icon(
                        imageVector = ZaswixIcons.ArrowBack,
                        contentDescription = "Back",
                        tint = NetflixWhite
                    )
                }
            }

            // Metadata and description
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = displayTitle,
                    color = NetflixWhite,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "$displayRating Rating",
                        color = Color(0xFF46D369),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = displayYear,
                        color = NetflixLightGrey,
                        fontSize = 13.sp
                    )
                    Box(
                        modifier = Modifier
                            .background(NetflixCardBg, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "HD 1080p",
                            color = NetflixWhite,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = item.durationOrSeasons,
                        color = NetflixLightGrey,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Play Button
                Button(
                    onClick = {
                        if (extractedServers.isNotEmpty()) {
                            onPlayClick(item, extractedServers, extractedServers.firstOrNull())
                        } else if (extractedEpisodes.isNotEmpty()) {
                            val ep1 = extractedEpisodes.first()
                            coroutineScope.launch {
                                loadingEpisodeUrl = ep1.url
                                val epPayload = mediaRepository.loadMediaDetails(ep1.url, item.providerName)
                                loadingEpisodeUrl = null
                                val epServers = epPayload?.servers.orEmpty()
                                val epItem = item.copy(
                                    id = "${item.id}_s${ep1.season}_e${ep1.episode}",
                                    title = "${item.title} - S${ep1.season}E${ep1.episode}: ${ep1.title}",
                                    pageUrl = ep1.url
                                )
                                onPlayClick(epItem, epServers, epServers.firstOrNull())
                            }
                        } else if (item.episodes.isNotEmpty()) {
                            val ep1 = item.episodes.first()
                            onPlayClick(
                                item.copy(
                                    id = "${item.id}_ep_${ep1.episodeNumber}",
                                    title = "${item.title} - Ep. ${ep1.episodeNumber}: ${ep1.title}",
                                    streamSources = ep1.streamSources,
                                    embedUrls = if (ep1.embedUrls.isNotEmpty()) ep1.embedUrls else item.embedUrls,
                                    pageUrl = if (ep1.pageUrl.isNotEmpty()) ep1.pageUrl else item.pageUrl
                                ),
                                emptyList(),
                                null
                            )
                        } else {
                            onPlayClick(item.copy(streamSources = currentStreams), emptyList(), null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NetflixWhite,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(
                        imageVector = ZaswixIcons.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Play",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Save / Add to My List Toggle Button
                Button(
                    onClick = {
                        coroutineScope.launch {
                            if (isInWatchlist) {
                                mediaRepository.removeFromWatchlist(item.id)
                            } else {
                                mediaRepository.addToWatchlist(item)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isInWatchlist) NetflixRed.copy(alpha = 0.2f) else NetflixCardBg,
                        contentColor = if (isInWatchlist) NetflixRed else NetflixWhite
                    ),
                    shape = RoundedCornerShape(6.dp),
                    border = if (isInWatchlist) BorderStroke(1.dp, NetflixRed) else null
                ) {
                    Icon(
                        imageVector = if (isInWatchlist) ZaswixIcons.Bookmark else ZaswixIcons.Add,
                        contentDescription = if (isInWatchlist) "In My List" else "Add to My List",
                        tint = if (isInWatchlist) NetflixRed else NetflixWhite,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isInWatchlist) "✓ In My List" else "+ Add to My List",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isInWatchlist) NetflixRed else NetflixWhite
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Download Button
                Button(
                    onClick = { },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NetflixCardBg,
                        contentColor = NetflixWhite
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(
                        imageVector = ZaswixIcons.Download,
                        contentDescription = "Download",
                        tint = NetflixWhite,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Download",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Overview Synopsis
                Text(
                    text = displayOverview,
                    color = NetflixWhite,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Provider and Category tags
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Source Provider: ${item.providerName}",
                        color = NetflixRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (isLoadingDetails) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = NetflixRed,
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Fetching Servers...",
                                color = NetflixLightGrey,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Genres: ${displayGenres.joinToString(", ")}",
                    color = NetflixLightGrey,
                    fontSize = 12.sp
                )

                // Episodes Section (for series / anime)
                if (extractedEpisodes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Episodes (${extractedEpisodes.size})",
                            color = NetflixWhite,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.durationOrSeasons,
                            color = NetflixLightGrey,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    extractedEpisodes.forEach { episode ->
                        val isEpLoading = loadingEpisodeUrl == episode.url
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp)
                                .clickable {
                                    coroutineScope.launch {
                                        loadingEpisodeUrl = episode.url
                                        val epPayload = mediaRepository.loadMediaDetails(episode.url, item.providerName)
                                        loadingEpisodeUrl = null
                                        val epServers = epPayload?.servers.orEmpty()
                                        val epItem = item.copy(
                                            id = "${item.id}_s${episode.season}_e${episode.episode}",
                                            title = "${item.title} - S${episode.season}E${episode.episode}: ${episode.title}",
                                            pageUrl = episode.url
                                        )
                                        onPlayClick(epItem, epServers, epServers.firstOrNull())
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = NetflixDarkGrey),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 110.dp, height = 65.dp)
                                        .background(NetflixCardBg, RoundedCornerShape(6.dp))
                                ) {
                                    AsyncImage(
                                        model = episode.thumbnailUrl.ifEmpty { displayBackdrop },
                                        contentDescription = episode.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isEpLoading) {
                                            CircularProgressIndicator(
                                                color = NetflixRed,
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = ZaswixIcons.PlayArrow,
                                                contentDescription = "Play Episode",
                                                tint = NetflixWhite,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "S${episode.season}E${episode.episode} • ${episode.title}",
                                        color = NetflixWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    if (episode.runtime.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = episode.runtime,
                                            color = NetflixLightGrey,
                                            fontSize = 12.sp
                                        )
                                    }
                                    if (episode.description.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = episode.description,
                                            color = NetflixLightGrey,
                                            fontSize = 11.sp,
                                            maxLines = 2
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            loadingEpisodeUrl = episode.url
                                            val epPayload = mediaRepository.loadMediaDetails(episode.url, item.providerName)
                                            loadingEpisodeUrl = null
                                            val epServers = epPayload?.servers.orEmpty()
                                            val epItem = item.copy(
                                                id = "${item.id}_s${episode.season}_e${episode.episode}",
                                                title = "${item.title} - S${episode.season}E${episode.episode}: ${episode.title}",
                                                pageUrl = episode.url
                                            )
                                            onPlayClick(epItem, epServers, epServers.firstOrNull())
                                        }
                                    }
                                ) {
                                    if (isEpLoading) {
                                        CircularProgressIndicator(
                                            color = NetflixRed,
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = ZaswixIcons.PlayArrow,
                                            contentDescription = "Play",
                                            tint = NetflixRed,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (item.episodes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Episodes (${item.episodes.size})",
                            color = NetflixWhite,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.durationOrSeasons,
                            color = NetflixLightGrey,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    item.episodes.forEach { episode ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp)
                                .clickable {
                                    onPlayClick(
                                        item.copy(
                                            id = "${item.id}_ep_${episode.episodeNumber}",
                                            title = "${item.title} - Ep. ${episode.episodeNumber}: ${episode.title}",
                                            streamSources = episode.streamSources,
                                            embedUrls = if (episode.embedUrls.isNotEmpty()) episode.embedUrls else item.embedUrls,
                                            pageUrl = if (episode.pageUrl.isNotEmpty()) episode.pageUrl else item.pageUrl
                                        ),
                                        emptyList(),
                                        null
                                    )
                                },
                            colors = CardDefaults.cardColors(containerColor = NetflixDarkGrey),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 110.dp, height = 65.dp)
                                        .background(NetflixCardBg, RoundedCornerShape(6.dp))
                                ) {
                                    AsyncImage(
                                        model = episode.thumbnail.ifEmpty { displayBackdrop },
                                        contentDescription = episode.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = ZaswixIcons.PlayArrow,
                                            contentDescription = "Play Episode",
                                            tint = NetflixWhite,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${episode.episodeNumber}. ${episode.title}",
                                        color = NetflixWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = episode.duration,
                                        color = NetflixLightGrey,
                                        fontSize = 12.sp
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        onPlayClick(
                                            item.copy(
                                                title = "${item.title} - Ep. ${episode.episodeNumber}: ${episode.title}",
                                                streamSources = episode.streamSources.ifEmpty { currentStreams }
                                            ),
                                            emptyList(),
                                            null
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = ZaswixIcons.PlayArrow,
                                        contentDescription = "Play",
                                        tint = NetflixRed,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Available Stream Servers
                if (extractedServers.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Available Stream Servers (${extractedServers.size})",
                                color = NetflixWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Direct & Embed Providers (Ruby, Cineverse, Vidrift...)",
                                color = NetflixLightGrey,
                                fontSize = 11.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(Color(0xFF46D369).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "READY TO STREAM",
                                color = Color(0xFF46D369),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    extractedServers.forEach { server ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(NetflixDarkGrey, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = server.serverName,
                                        color = NetflixWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFF46D369).copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "LIVE EXTRACTED",
                                            color = Color(0xFF46D369),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = "Quality: ${server.quality} • Fast Cloud Server",
                                    color = NetflixLightGrey,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = {
                                    onPlayClick(item, extractedServers, server)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(text = "Watch", fontSize = 12.sp, color = NetflixWhite)
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Available Stream Servers (${currentStreams.size})",
                                color = NetflixWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Auto-resolved via Cloudstream Engine",
                                color = NetflixLightGrey,
                                fontSize = 11.sp
                            )
                        }

                        if (isAutoResolving) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = NetflixRed,
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Auto-Extracting...",
                                    color = NetflixLightGrey,
                                    fontSize = 11.sp
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF46D369).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "READY TO STREAM",
                                    color = Color(0xFF46D369),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    currentStreams.forEach { source ->
                        val isLiveExtracted = source.headers.isNotEmpty() || source.serverName.contains("•")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(NetflixDarkGrey, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = source.serverName,
                                        color = NetflixWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (isLiveExtracted) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF46D369).copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "LIVE EXTRACTED",
                                                color = Color(0xFF46D369),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "Quality: ${source.quality}${if (source.isM3u8) " • HLS Stream" else " • Direct Video"}",
                                    color = NetflixLightGrey,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = {
                                    val reordered = listOf(source) + currentStreams.filter { it != source }
                                    onPlayClick(item.copy(streamSources = reordered), emptyList(), null)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(text = "Watch", fontSize = 12.sp, color = NetflixWhite)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}
