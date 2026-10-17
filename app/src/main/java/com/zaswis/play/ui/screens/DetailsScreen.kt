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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.zaswis.play.extractor.ZaswixDetailsExtractor
import com.zaswis.play.extractor.model.VideoQualityOption
import com.zaswis.play.ui.components.ZaswixIcons
import com.zaswis.play.ui.theme.NetflixBlack
import com.zaswis.play.ui.theme.NetflixCardBg
import com.zaswis.play.ui.theme.NetflixDarkGrey
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite
import com.zaswis.play.utils.DownloadHelper
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
        if (item.pageUrl.isNotEmpty()) {
            isLoadingDetails = true
            val payload = mediaRepository.loadMediaDetails(item.pageUrl, item.providerName)
            if (payload != null && (payload.servers.isNotEmpty() || payload.episodes.isNotEmpty() || payload.synopsis.isNotEmpty())) {
                detailsPayload = payload
            } else if (item.embedUrls.isNotEmpty() || item.pageUrl.isNotEmpty()) {
                isAutoResolving = true
                val resolved = mediaRepository.autoResolveMediaStreams(item)
                if (resolved.isNotEmpty()) {
                    currentStreams = resolved
                }
                isAutoResolving = false
            }
            isLoadingDetails = false
        } else if (item.embedUrls.isNotEmpty()) {
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

    var showQualitySheet by remember { mutableStateOf(false) }
    var qualityOptions by remember { mutableStateOf<List<VideoQualityOption>>(emptyList()) }
    var selectedQualityIndex by remember { mutableIntStateOf(0) }
    var isResolvingQuality by remember { mutableStateOf(false) }
    var qualityTargetTitle by remember { mutableStateOf(item.title) }
    var qualityTargetServer by remember { mutableStateOf<ServerOption?>(null) }

    fun openQualitySheet(targetTitle: String = displayTitle, server: ServerOption? = null) {
        qualityTargetTitle = targetTitle
        qualityTargetServer = server
        showQualitySheet = true
        isResolvingQuality = true
        coroutineScope.launch {
            try {
                val targetSrv = server ?: extractedServers.firstOrNull()
                if (targetSrv != null) {
                    val resolved = ZaswixDetailsExtractor.resolveServerToStream(targetSrv)
                    qualityOptions = if (resolved.qualities.isNotEmpty()) {
                        resolved.qualities
                    } else {
                        listOf(
                            VideoQualityOption("1080p Full HD", resolved.url, formattedSize = "~1.8 GB", headers = resolved.headers),
                            VideoQualityOption("720p HD", resolved.url, formattedSize = "~950 MB", headers = resolved.headers),
                            VideoQualityOption("480p SD (Data Saver)", resolved.url, formattedSize = "~450 MB", headers = resolved.headers)
                        )
                    }
                } else if (currentStreams.isNotEmpty()) {
                    val st = currentStreams.first()
                    val resolvedQualities = ZaswixDetailsExtractor.resolveStreamQualities(st.url, st.headers)
                    qualityOptions = if (resolvedQualities.isNotEmpty()) {
                        resolvedQualities
                    } else {
                        listOf(
                            VideoQualityOption("1080p Full HD", st.url, formattedSize = "~1.8 GB", headers = st.headers),
                            VideoQualityOption("720p HD", st.url, formattedSize = "~950 MB", headers = st.headers),
                            VideoQualityOption("480p SD (Data Saver)", st.url, formattedSize = "~450 MB", headers = st.headers)
                        )
                    }
                } else {
                    qualityOptions = listOf(
                        VideoQualityOption("1080p Full HD", item.pageUrl, formattedSize = "~1.8 GB"),
                        VideoQualityOption("720p HD", item.pageUrl, formattedSize = "~950 MB"),
                        VideoQualityOption("480p SD (Data Saver)", item.pageUrl, formattedSize = "~450 MB")
                    )
                }
            } catch (e: Exception) {
                qualityOptions = listOf(
                    VideoQualityOption("1080p Full HD", item.pageUrl, formattedSize = "~1.8 GB"),
                    VideoQualityOption("720p HD", item.pageUrl, formattedSize = "~950 MB"),
                    VideoQualityOption("480p SD (Data Saver)", item.pageUrl, formattedSize = "~450 MB")
                )
            } finally {
                selectedQualityIndex = 0
                isResolvingQuality = false
            }
        }
    }

    fun openQualitySheetForStream(targetTitle: String, source: StreamSource) {
        qualityTargetTitle = targetTitle
        qualityTargetServer = null
        showQualitySheet = true
        isResolvingQuality = true
        coroutineScope.launch {
            try {
                val resolvedQualities = ZaswixDetailsExtractor.resolveStreamQualities(source.url, source.headers)
                qualityOptions = if (resolvedQualities.isNotEmpty()) {
                    resolvedQualities
                } else {
                    listOf(
                        VideoQualityOption("1080p Full HD", source.url, formattedSize = "~1.8 GB", headers = source.headers),
                        VideoQualityOption("720p HD", source.url, formattedSize = "~950 MB", headers = source.headers),
                        VideoQualityOption("480p SD (Data Saver)", source.url, formattedSize = "~450 MB", headers = source.headers)
                    )
                }
            } catch (e: Exception) {
                qualityOptions = listOf(
                    VideoQualityOption("1080p Full HD", source.url, formattedSize = "~1.8 GB", headers = source.headers),
                    VideoQualityOption("720p HD", source.url, formattedSize = "~950 MB", headers = source.headers),
                    VideoQualityOption("480p SD (Data Saver)", source.url, formattedSize = "~450 MB", headers = source.headers)
                )
            } finally {
                selectedQualityIndex = 0
                isResolvingQuality = false
            }
        }
    }

    fun openQualitySheetForEpisode(episode: EpisodeItem) {
        val epTitle = "${displayTitle} - S${episode.season}E${episode.episode}: ${episode.title}"
        qualityTargetTitle = epTitle
        qualityTargetServer = null
        showQualitySheet = true
        isResolvingQuality = true
        coroutineScope.launch {
            try {
                loadingEpisodeUrl = episode.url
                val epPayload = mediaRepository.loadMediaDetails(episode.url, item.providerName)
                loadingEpisodeUrl = null
                val epServers = epPayload?.servers.orEmpty()
                val targetSrv = epServers.firstOrNull()
                if (targetSrv != null) {
                    qualityTargetServer = targetSrv
                    val resolved = ZaswixDetailsExtractor.resolveServerToStream(targetSrv)
                    qualityOptions = if (resolved.qualities.isNotEmpty()) {
                        resolved.qualities
                    } else {
                        listOf(
                            VideoQualityOption("1080p Full HD", resolved.url, formattedSize = "~1.8 GB", headers = resolved.headers),
                            VideoQualityOption("720p HD", resolved.url, formattedSize = "~950 MB", headers = resolved.headers),
                            VideoQualityOption("480p SD (Data Saver)", resolved.url, formattedSize = "~450 MB", headers = resolved.headers)
                        )
                    }
                } else {
                    qualityOptions = listOf(
                        VideoQualityOption("1080p Full HD", episode.url, formattedSize = "~1.8 GB"),
                        VideoQualityOption("720p HD", episode.url, formattedSize = "~950 MB"),
                        VideoQualityOption("480p SD (Data Saver)", episode.url, formattedSize = "~450 MB")
                    )
                }
            } catch (e: Exception) {
                qualityOptions = listOf(
                    VideoQualityOption("1080p Full HD", episode.url, formattedSize = "~1.8 GB"),
                    VideoQualityOption("720p HD", episode.url, formattedSize = "~950 MB"),
                    VideoQualityOption("480p SD (Data Saver)", episode.url, formattedSize = "~450 MB")
                )
            } finally {
                selectedQualityIndex = 0
                isResolvingQuality = false
            }
        }
    }

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
                    onClick = {
                        openQualitySheet(displayTitle)
                    },
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

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { openQualitySheetForEpisode(episode) }
                                    ) {
                                        Icon(
                                            imageVector = ZaswixIcons.Download,
                                            contentDescription = "Download Episode",
                                            tint = NetflixLightGrey,
                                            modifier = Modifier.size(22.dp)
                                        )
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { openQualitySheet(displayTitle, server) }
                                ) {
                                    Icon(
                                        imageVector = ZaswixIcons.Download,
                                        contentDescription = "Download Server",
                                        tint = NetflixLightGrey,
                                        modifier = Modifier.size(20.dp)
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { openQualitySheetForStream(displayTitle, source) }
                                ) {
                                    Icon(
                                        imageVector = ZaswixIcons.Download,
                                        contentDescription = "Download Stream",
                                        tint = NetflixLightGrey,
                                        modifier = Modifier.size(20.dp)
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
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }

        // Glassmorphic Quality Selection BottomSheet Modal
        if (showQualitySheet) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable { showQualitySheet = false },
                contentAlignment = Alignment.BottomCenter
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) { }
                        .padding(bottom = 0.dp),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        // Drag Handle
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(40.dp)
                                .height(4.dp)
                                .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Select Quality & Data Usage",
                                    color = NetflixWhite,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = qualityTargetTitle,
                                    color = NetflixLightGrey,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                            IconButton(
                                onClick = { showQualitySheet = false },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = ZaswixIcons.Clear,
                                    contentDescription = "Close",
                                    tint = NetflixLightGrey,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isResolvingQuality) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = NetflixRed,
                                        modifier = Modifier.size(36.dp),
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Resolving stream & estimating file sizes...",
                                        color = NetflixLightGrey,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                qualityOptions.forEachIndexed { index, option ->
                                    val isSelected = index == selectedQualityIndex
                                    Surface(
                                        onClick = { selectedQualityIndex = index },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) NetflixRed.copy(alpha = 0.18f) else NetflixDarkGrey,
                                        border = if (isSelected) BorderStroke(1.5.dp, NetflixRed) else BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            if (isSelected) NetflixRed else Color.White.copy(alpha = 0.15f),
                                                            RoundedCornerShape(6.dp)
                                                        )
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = when {
                                                            option.quality.contains("1080", ignoreCase = true) -> "1080p"
                                                            option.quality.contains("720", ignoreCase = true) -> "720p"
                                                            option.quality.contains("480", ignoreCase = true) -> "480p"
                                                            option.quality.contains("360", ignoreCase = true) -> "360p"
                                                            else -> "HD"
                                                        },
                                                        color = NetflixWhite,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }

                                                Column {
                                                    Text(
                                                        text = option.quality,
                                                        color = NetflixWhite,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    val bandwidthText = when {
                                                        option.bandwidth > 0 -> "Data usage: ~${(option.bandwidth / 1_000_000f).let { "%.1f".format(it) }} Mbps"
                                                        option.quality.contains("1080") -> "High Quality • Best for Wi-Fi"
                                                        option.quality.contains("720") -> "Standard HD • Recommended"
                                                        else -> "Data Saver • Mobile friendly"
                                                    }
                                                    Text(
                                                        text = bandwidthText,
                                                        color = NetflixLightGrey,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (option.formattedSize.isNotEmpty()) {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0xFF46D369).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                                    ) {
                                                        Text(
                                                            text = option.formattedSize,
                                                            color = Color(0xFF46D369),
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = ZaswixIcons.Check,
                                                        contentDescription = "Selected",
                                                        tint = NetflixRed,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // Stream and Download Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val selectedOpt = qualityOptions.getOrNull(selectedQualityIndex)
                                        showQualitySheet = false
                                        if (extractedServers.isNotEmpty()) {
                                            onPlayClick(item, extractedServers, qualityTargetServer ?: extractedServers.firstOrNull())
                                        } else if (selectedOpt != null && selectedOpt.url.isNotEmpty()) {
                                            val customStream = StreamSource(
                                                serverName = "Direct Stream • ${selectedOpt.quality}",
                                                quality = selectedOpt.quality,
                                                url = selectedOpt.url,
                                                headers = selectedOpt.headers,
                                                isM3u8 = selectedOpt.url.contains(".m3u8", ignoreCase = true)
                                            )
                                            onPlayClick(item.copy(streamSources = listOf(customStream) + currentStreams), emptyList(), null)
                                        } else {
                                            onPlayClick(item.copy(streamSources = currentStreams), emptyList(), null)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NetflixWhite,
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        imageVector = ZaswixIcons.PlayArrow,
                                        contentDescription = "Stream",
                                        tint = Color.Black,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Stream",
                                        color = Color.Black,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Button(
                                    onClick = {
                                        val selectedOpt = qualityOptions.getOrNull(selectedQualityIndex)
                                        if (selectedOpt != null && selectedOpt.url.isNotEmpty()) {
                                            DownloadHelper.downloadVideo(
                                                context = context,
                                                url = selectedOpt.url,
                                                title = qualityTargetTitle,
                                                headers = selectedOpt.headers,
                                                quality = selectedOpt.quality
                                            )
                                        }
                                        showQualitySheet = false
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NetflixRed,
                                        contentColor = NetflixWhite
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        imageVector = ZaswixIcons.Download,
                                        contentDescription = "Download",
                                        tint = NetflixWhite,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Download",
                                        color = NetflixWhite,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
