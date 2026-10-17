package com.zaswis.play.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.zaswis.play.extractor.ResolvedStream
import com.zaswis.play.extractor.model.VideoQualityOption
import com.zaswis.play.ui.components.ZaswixIcons
import com.zaswis.play.ui.theme.NetflixCardBg
import com.zaswis.play.ui.theme.NetflixDarkGrey
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite
import com.zaswis.play.utils.DownloadHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.util.Locale

// Ad domains found in ToonStream & MultiMovies HTML source code
private val AD_DOMAINS = listOf(
    "paviliongiddy.com", "bondeddarkenedswerve.com", "crowbarrinds.com",
    "dinomysstorm.com", "21wiz.com", "acscdn.com", "superficial-disaster.com",
    "hilltopads.com", "monetag.com", "a-ads.com", "displayvertising.com",
    "cloudfront.net", "ptekuwiny.pro", "loyal-product.com"
)

data class PlayerTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val name: String,
    val language: String,
    val isSelected: Boolean
)

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, mins, secs)
    } else {
        "%02d:%02d".format(mins, secs)
    }
}

@OptIn(UnstableApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HybridStreamPlayer(
    stream: ResolvedStream,
    title: String = "Video",
    onBackClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (stream.isDirectStream) {
            // 1. NATIVE EXOPLAYER (For .m3u8 & .mp4 streams)
            val exoPlayer = remember(stream.url) {
                val okHttpClient = OkHttpClient.Builder()
                    .dns(com.zaswis.play.network.CloudflareDns.dns)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                    .setDefaultRequestProperties(stream.headers)

                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                    .build()
                    .apply {
                        // Enable default subtitles if available (English preference)
                        trackSelectionParameters = trackSelectionParameters.buildUpon()
                            .setSelectUndeterminedTextLanguage(true)
                            .setPreferredTextLanguage("en")
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                        setMediaItem(MediaItem.fromUri(Uri.parse(stream.url)))
                        prepare()
                        playWhenReady = true
                    }
            }

            // Required Compose State Variables
            var isPlaying by remember { mutableStateOf(exoPlayer.playWhenReady) }
            var currentPosition by remember { mutableLongStateOf(0L) }
            var duration by remember { mutableLongStateOf(0L) }
            var showControls by remember { mutableStateOf(true) }
            var isBuffering by remember { mutableStateOf(true) }
            var isDraggingSlider by remember { mutableStateOf(false) }
            var sliderPosition by remember { mutableFloatStateOf(0f) }
            var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

            // Audio & Subtitle track state
            var audioTracks by remember { mutableStateOf<List<PlayerTrackOption>>(emptyList()) }
            var textTracks by remember { mutableStateOf<List<PlayerTrackOption>>(emptyList()) }
            var areSubtitlesOff by remember { mutableStateOf(false) }

            // Modals visibility
            var showAudioModal by remember { mutableStateOf(false) }
            var showSubtitleModal by remember { mutableStateOf(false) }
            var showQualityModal by remember { mutableStateOf(false) }
            var selectedQualityOption by remember(stream.url) {
                mutableStateOf(stream.qualities.firstOrNull())
            }

            // Gestures feedback state
            var seekFeedbackText by remember { mutableStateOf<String?>(null) }
            var seekFeedbackIsLeft by remember { mutableStateOf(false) }
            var showBrightnessHud by remember { mutableStateOf(false) }
            var currentBrightness by remember {
                val initBrightness = (context as? Activity)?.window?.attributes?.screenBrightness ?: 0.5f
                mutableFloatStateOf(if (initBrightness >= 0f) initBrightness else 0.5f)
            }
            var showVolumeHud by remember { mutableStateOf(false) }
            var currentVolume by remember {
                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 7
                mutableFloatStateOf(curVol.toFloat() / maxVol.coerceAtLeast(1).toFloat())
            }

            fun refreshTracks() {
                val tracks = exoPlayer.currentTracks
                val audioList = mutableListOf<PlayerTrackOption>()
                val textList = mutableListOf<PlayerTrackOption>()
                val isTextDisabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

                for (groupIndex in 0 until tracks.groups.size) {
                    val group = tracks.groups[groupIndex]
                    val type = group.type
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val isSelected = group.isTrackSelected(trackIndex)
                        val lang = format.language.orEmpty()
                        val label = when {
                            !format.label.isNullOrBlank() -> format.label!!
                            lang.isNotBlank() && lang != "und" -> {
                                try {
                                    Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH).ifBlank { lang }
                                } catch (_: Exception) {
                                    lang
                                }
                            }
                            type == C.TRACK_TYPE_AUDIO -> "Audio Track ${trackIndex + 1}"
                            else -> "Subtitle Track ${trackIndex + 1}"
                        }

                        val playerTrack = PlayerTrackOption(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            name = label,
                            language = lang,
                            isSelected = isSelected
                        )

                        if (type == C.TRACK_TYPE_AUDIO) {
                            audioList.add(playerTrack)
                        } else if (type == C.TRACK_TYPE_TEXT) {
                            textList.add(playerTrack)
                        }
                    }
                }
                audioTracks = audioList
                textTracks = textList
                areSubtitlesOff = isTextDisabled || textList.none { it.isSelected }
            }

            DisposableEffect(exoPlayer) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        isBuffering = (state == Player.STATE_BUFFERING)
                        if (state == Player.STATE_READY) {
                            duration = exoPlayer.duration.coerceAtLeast(0L)
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        refreshTracks()
                    }
                }
                exoPlayer.addListener(listener)
                isPlaying = exoPlayer.isPlaying
                isBuffering = (exoPlayer.playbackState == Player.STATE_BUFFERING)
                duration = exoPlayer.duration.coerceAtLeast(0L)
                refreshTracks()

                onDispose {
                    exoPlayer.removeListener(listener)
                    exoPlayer.release()
                }
            }

            // Sync progress tracking every second
            LaunchedEffect(exoPlayer, isPlaying) {
                while (true) {
                    if (!isDraggingSlider) {
                        currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                        val dur = exoPlayer.duration
                        if (dur > 0L) {
                            duration = dur
                            sliderPosition = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        }
                    }
                    delay(1000L)
                }
            }

            // Auto-hide controls after 3 seconds of inactivity
            LaunchedEffect(showControls, lastInteractionTime, isPlaying) {
                if (showControls && isPlaying) {
                    delay(3000L)
                    showControls = false
                }
            }

            // Double tap feedback auto-hide
            LaunchedEffect(seekFeedbackText) {
                if (seekFeedbackText != null) {
                    delay(800)
                    seekFeedbackText = null
                }
            }

            fun selectAudioTrack(track: PlayerTrackOption) {
                val currentTracks = exoPlayer.currentTracks
                if (track.groupIndex < currentTracks.groups.size) {
                    val group = currentTracks.groups[track.groupIndex]
                    val override = TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(override)
                        .build()
                    refreshTracks()
                }
            }

            fun selectTextTrack(track: PlayerTrackOption?) {
                if (track == null) {
                    // Turn Subtitles OFF
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                } else {
                    // Turn Subtitles ON
                    val currentTracks = exoPlayer.currentTracks
                    if (track.groupIndex < currentTracks.groups.size) {
                        val group = currentTracks.groups[track.groupIndex]
                        val override = TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(override)
                            .build()
                    }
                }
                refreshTracks()
            }

            fun notifyUserInteraction() {
                lastInteractionTime = System.currentTimeMillis()
            }

            // Outer gesture container wrapping PlayerView & Custom Compose Controls
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                notifyUserInteraction()
                                if (offset.x < size.width / 2f) {
                                    val activity = context as? Activity
                                    val lp = activity?.window?.attributes
                                    if (lp != null) {
                                        currentBrightness = if (lp.screenBrightness >= 0f) lp.screenBrightness else 0.5f
                                        showBrightnessHud = true
                                    }
                                } else {
                                    val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                                    val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 7
                                    currentVolume = curVol.toFloat() / maxVol.coerceAtLeast(1).toFloat()
                                    showVolumeHud = true
                                }
                            },
                            onDragEnd = {
                                coroutineScope.launch {
                                    delay(900)
                                    showBrightnessHud = false
                                    showVolumeHud = false
                                }
                            },
                            onDragCancel = {
                                showBrightnessHud = false
                                showVolumeHud = false
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                notifyUserInteraction()
                                if (change.position.x < size.width / 2f) {
                                    val activity = context as? Activity
                                    val window = activity?.window
                                    val lp = window?.attributes
                                    if (lp != null) {
                                        val delta = -dragAmount / 350f
                                        currentBrightness = (currentBrightness + delta).coerceIn(0.01f, 1.0f)
                                        lp.screenBrightness = currentBrightness
                                        window.attributes = lp
                                        showBrightnessHud = true
                                    }
                                } else {
                                    val delta = -dragAmount / 350f
                                    currentVolume = (currentVolume + delta).coerceIn(0f, 1f)
                                    if (audioManager != null) {
                                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        val targetVol = (currentVolume * maxVol).toInt().coerceIn(0, maxVol)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                    }
                                    showVolumeHud = true
                                }
                            }
                        )
                    }
                    .pointerInput(exoPlayer) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                notifyUserInteraction()
                                val isLeft = offset.x < size.width / 2f
                                val cur = exoPlayer.currentPosition
                                if (isLeft) {
                                    val target = (cur - 10_000L).coerceAtLeast(0L)
                                    exoPlayer.seekTo(target)
                                    currentPosition = target
                                    seekFeedbackText = "-10s"
                                    seekFeedbackIsLeft = true
                                } else {
                                    val dur = exoPlayer.duration
                                    val target = if (dur > 0) (cur + 10_000L).coerceAtMost(dur) else cur + 10_000L
                                    exoPlayer.seekTo(target)
                                    currentPosition = target
                                    seekFeedbackText = "+10s"
                                    seekFeedbackIsLeft = false
                                }
                            },
                            onTap = {
                                showControls = !showControls
                                notifyUserInteraction()
                            }
                        )
                    }
            ) {
                // 1. AndroidView with useController = false (Requirement 1)
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 2. Double Tap Seek Ripple/Badge Feedback
                AnimatedVisibility(
                    visible = seekFeedbackText != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(if (seekFeedbackIsLeft) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 48.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.75f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (seekFeedbackIsLeft) "⏪" else "⏩",
                                fontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = seekFeedbackText.orEmpty(),
                                color = NetflixWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 3. Brightness HUD Indicator on Left Half
                AnimatedVisibility(
                    visible = showBrightnessHud,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xDD121212), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 18.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "☀️",
                                fontSize = 20.sp
                            )
                            Text(
                                text = "${(currentBrightness * 100).toInt()}%",
                                color = NetflixWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(90.dp)
                                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp)),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height((90 * currentBrightness).dp)
                                        .background(Color(0xFFFFD700), RoundedCornerShape(3.dp))
                                )
                            }
                        }
                    }
                }

                // 4. Volume HUD Indicator on Right Half
                AnimatedVisibility(
                    visible = showVolumeHud,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xDD121212), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 18.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (currentVolume == 0f) "🔇" else if (currentVolume < 0.5f) "🔉" else "🔊",
                                fontSize = 20.sp
                            )
                            Text(
                                text = "${(currentVolume * 100).toInt()}%",
                                color = NetflixWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(90.dp)
                                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp)),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height((90 * currentVolume).dp)
                                        .background(Color(0xFF46D369), RoundedCornerShape(3.dp))
                                )
                            }
                        }
                    }
                }

                // 5. CustomPlayerControls Composable Overlay (Fades out after 3 seconds)
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    CustomPlayerControls(
                        title = title,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        currentPosition = currentPosition,
                        duration = duration,
                        isDraggingSlider = isDraggingSlider,
                        sliderPosition = sliderPosition,
                        onPlayPauseToggle = {
                            val willPlay = !exoPlayer.playWhenReady
                            exoPlayer.playWhenReady = willPlay
                            isPlaying = willPlay
                            notifyUserInteraction()
                        },
                        onSliderChange = { newProgress ->
                            isDraggingSlider = true
                            sliderPosition = newProgress
                            notifyUserInteraction()
                        },
                        onSliderChangeFinished = {
                            val targetTime = (sliderPosition * duration).toLong().coerceIn(0L, duration.coerceAtLeast(0L))
                            exoPlayer.seekTo(targetTime)
                            currentPosition = targetTime
                            isDraggingSlider = false
                            notifyUserInteraction()
                        },
                        onRewind10s = {
                            val cur = exoPlayer.currentPosition
                            val target = (cur - 10_000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                            currentPosition = target
                            seekFeedbackText = "-10s"
                            seekFeedbackIsLeft = true
                            notifyUserInteraction()
                        },
                        onFastForward10s = {
                            val cur = exoPlayer.currentPosition
                            val dur = exoPlayer.duration
                            val target = if (dur > 0) (cur + 10_000L).coerceAtMost(dur) else cur + 10_000L
                            exoPlayer.seekTo(target)
                            currentPosition = target
                            seekFeedbackText = "+10s"
                            seekFeedbackIsLeft = false
                            notifyUserInteraction()
                        },
                        onBackClick = onBackClick,
                        onSettingsClick = {
                            Log.d("HybridStreamPlayer", "Settings/CC clicked: opening Quality/Subtitle BottomSheet")
                            showQualityModal = true
                            notifyUserInteraction()
                        },
                        onAudioClick = {
                            showAudioModal = true
                            showSubtitleModal = false
                            showQualityModal = false
                            notifyUserInteraction()
                        },
                        onSubtitleClick = {
                            showSubtitleModal = true
                            showAudioModal = false
                            showQualityModal = false
                            notifyUserInteraction()
                        },
                        onQualityClick = {
                            showQualityModal = true
                            showAudioModal = false
                            showSubtitleModal = false
                            notifyUserInteraction()
                        },
                        onDownloadClick = {
                            val activeOpt = selectedQualityOption ?: stream.qualities.firstOrNull()
                            val dlUrl = activeOpt?.url ?: stream.url
                            val dlQuality = activeOpt?.quality ?: "HD"
                            val dlHeaders = activeOpt?.headers?.ifEmpty { stream.headers } ?: stream.headers
                            DownloadHelper.downloadVideo(
                                context = context,
                                url = dlUrl,
                                title = title,
                                headers = dlHeaders,
                                quality = dlQuality
                            )
                            notifyUserInteraction()
                        },
                        selectedAudioLabel = audioTracks.find { it.isSelected }?.name,
                        selectedSubtitleLabel = if (areSubtitlesOff) "Off" else (textTracks.find { it.isSelected }?.name ?: "On"),
                        selectedQualityLabel = selectedQualityOption?.quality ?: (if (stream.qualities.isNotEmpty()) stream.qualities.first().quality else "HD"),
                        areSubtitlesOff = areSubtitlesOff,
                        onUserInteraction = { notifyUserInteraction() }
                    )
                }

                // 6. Glassmorphic Audio Tracks Selection Modal
                if (showAudioModal) {
                    GlassmorphicTrackModal(
                        title = "Audio Tracks",
                        icon = ZaswixIcons.Audiotrack,
                        onDismiss = { showAudioModal = false }
                    ) {
                        if (audioTracks.isEmpty()) {
                            Text(
                                text = "Default Audio (Embedded)",
                                color = NetflixLightGrey,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                audioTracks.forEach { track ->
                                    TrackItemRow(
                                        title = track.name,
                                        subText = if (track.language.isNotBlank() && track.language != "und") track.language.uppercase() else null,
                                        isSelected = track.isSelected,
                                        onClick = {
                                            selectAudioTrack(track)
                                            showAudioModal = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 7. Glassmorphic Subtitles Selection Modal
                if (showSubtitleModal) {
                    GlassmorphicTrackModal(
                        title = "Subtitles & Captions",
                        icon = ZaswixIcons.Subtitles,
                        onDismiss = { showSubtitleModal = false }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TrackItemRow(
                                title = "Off",
                                subText = null,
                                isSelected = areSubtitlesOff,
                                onClick = {
                                    selectTextTrack(null)
                                    showSubtitleModal = false
                                }
                            )

                            if (textTracks.isEmpty()) {
                                Text(
                                    text = "No subtitle tracks available for this stream",
                                    color = NetflixLightGrey,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                textTracks.forEach { track ->
                                    TrackItemRow(
                                        title = track.name,
                                        subText = if (track.language.isNotBlank() && track.language != "und") track.language.uppercase() else null,
                                        isSelected = !areSubtitlesOff && track.isSelected,
                                        onClick = {
                                            selectTextTrack(track)
                                            showSubtitleModal = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 8. Glassmorphic Video Quality & Size Selection Modal
                if (showQualityModal) {
                    GlassmorphicTrackModal(
                        title = "Video Quality & Size",
                        icon = ZaswixIcons.Settings,
                        onDismiss = { showQualityModal = false }
                    ) {
                        val qualitiesToDisplay = if (stream.qualities.isNotEmpty()) stream.qualities else listOf(
                            VideoQualityOption(quality = "1080p Full HD", url = stream.url, formattedSize = "~1.8 GB", headers = stream.headers),
                            VideoQualityOption(quality = "720p HD", url = stream.url, formattedSize = "~950 MB", headers = stream.headers),
                            VideoQualityOption(quality = "480p SD (Data Saver)", url = stream.url, formattedSize = "~450 MB", headers = stream.headers)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            qualitiesToDisplay.forEach { opt ->
                                val isSelected = opt.quality == (selectedQualityOption?.quality ?: qualitiesToDisplay.first().quality)
                                TrackItemRow(
                                    title = opt.quality,
                                    subText = if (opt.formattedSize.isNotEmpty()) "Estimated: ${opt.formattedSize}" else null,
                                    isSelected = isSelected,
                                    onClick = {
                                        selectedQualityOption = opt
                                        if (opt.url != stream.url && opt.url.isNotBlank()) {
                                            val currentPos = exoPlayer.currentPosition
                                            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(opt.url)))
                                            exoPlayer.seekTo(currentPos)
                                            exoPlayer.prepare()
                                            exoPlayer.play()
                                        }
                                        showQualityModal = false
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Button(
                                onClick = {
                                    val activeOpt = selectedQualityOption ?: qualitiesToDisplay.first()
                                    DownloadHelper.downloadVideo(
                                        context = context,
                                        url = activeOpt.url,
                                        title = title,
                                        headers = activeOpt.headers.ifEmpty { stream.headers },
                                        quality = activeOpt.quality
                                    )
                                    showQualityModal = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = ZaswixIcons.Download,
                                    contentDescription = "Download",
                                    tint = NetflixWhite,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Download Quality (${selectedQualityOption?.quality ?: qualitiesToDisplay.first().quality})",
                                    color = NetflixWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // 2. AD-BLOCKED EMBED PLAYER (For ModiPlay, Vidrift, VIDOUT, Cloudy, AbyssPlayer)
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = stream.headers["User-Agent"]
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString()?.lowercase().orEmpty()
                                if (AD_DOMAINS.any { reqUrl.contains(it) }) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val targetUrl = request?.url?.toString().orEmpty()
                                val embedHost = Uri.parse(stream.url).host.orEmpty()
                                return !targetUrl.contains(embedHost)
                            }
                        }

                        loadUrl(stream.url, stream.headers)
                    }
                },
                update = { webView ->
                    if (webView.url != stream.url) {
                        webView.loadUrl(stream.url, stream.headers)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Custom Compose UI Overlay for Video Playback (Netflix / Cloudstream Style)
 */
@Composable
fun CustomPlayerControls(
    title: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    duration: Long,
    isDraggingSlider: Boolean,
    sliderPosition: Float,
    onPlayPauseToggle: () -> Unit,
    onSliderChange: (Float) -> Unit,
    onSliderChangeFinished: () -> Unit,
    onRewind10s: () -> Unit,
    onFastForward10s: () -> Unit,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onQualityClick: () -> Unit,
    onDownloadClick: () -> Unit,
    selectedAudioLabel: String?,
    selectedSubtitleLabel: String?,
    selectedQualityLabel: String?,
    areSubtitlesOff: Boolean,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
    ) {
        // Top Bar: Glassmorphic Top Row featuring Back Arrow & Video Title
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.85f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    onUserInteraction()
                    onBackClick()
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .size(38.dp)
            ) {
                Icon(
                    imageVector = ZaswixIcons.ArrowBack,
                    contentDescription = "Back",
                    tint = NetflixWhite,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = title,
                color = NetflixWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Top-right glassmorphic action pills
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassmorphicPlayerButton(
                    icon = ZaswixIcons.Audiotrack,
                    label = "Audio",
                    activeLabel = selectedAudioLabel,
                    onClick = onAudioClick
                )

                GlassmorphicPlayerButton(
                    icon = ZaswixIcons.Subtitles,
                    label = "Subtitles",
                    activeLabel = selectedSubtitleLabel,
                    isActive = !areSubtitlesOff,
                    onClick = onSubtitleClick
                )

                GlassmorphicPlayerButton(
                    icon = ZaswixIcons.Download,
                    label = "Download",
                    activeLabel = selectedQualityLabel,
                    onClick = onDownloadClick
                )
            }
        }

        // Center Controls: Rewind 10s, Large Clean Play/Pause, Forward 10s
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rewind 10s button
            Surface(
                onClick = onRewind10s,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "↺", color = NetflixWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(text = "10s", color = NetflixWhite, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Center Play / Pause / Buffering Indicator
            if (isBuffering) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = NetflixRed,
                        modifier = Modifier.size(46.dp),
                        strokeWidth = 3.dp
                    )
                }
            } else {
                Surface(
                    onClick = onPlayPauseToggle,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.4f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(76.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (isPlaying) ZaswixIcons.Pause else ZaswixIcons.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = NetflixWhite,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            // Forward 10s button
            Surface(
                onClick = onFastForward10s,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "↻", color = NetflixWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(text = "10s", color = NetflixWhite, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Bottom Bar: Compose Slider Seek Bar, Timers, and Settings/CC Icon
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.9f)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Seek Bar Slider
                Slider(
                    value = if (isDraggingSlider) sliderPosition else (if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f),
                    onValueChange = onSliderChange,
                    onValueChangeFinished = onSliderChangeFinished,
                    colors = SliderDefaults.colors(
                        thumbColor = NetflixRed,
                        activeTrackColor = NetflixRed,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Current Time / Total Duration Text
                Text(
                    text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                    color = NetflixWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Settings / CC Icon (logs message and opens quality/subtitle modal)
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.12f), CircleShape)
                        .size(34.dp)
                ) {
                    Icon(
                        imageVector = ZaswixIcons.Settings,
                        contentDescription = "Settings / CC",
                        tint = NetflixWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassmorphicPlayerButton(
    icon: ImageVector,
    label: String,
    activeLabel: String? = null,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = Color(0xAA141414),
        border = BorderStroke(
            1.dp,
            if (isActive) NetflixRed.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f)
        ),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) NetflixRed else NetflixWhite,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = NetflixWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (!activeLabel.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .background(
                            if (isActive) NetflixRed.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.15f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = activeLabel,
                        color = if (isActive) NetflixRed else NetflixLightGrey,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassmorphicTrackModal(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.85f)
                .clickable(enabled = false, onClick = {})
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xF0181818)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = NetflixRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = title,
                            color = NetflixWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = ZaswixIcons.Clear,
                            contentDescription = "Close",
                            tint = NetflixLightGrey,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                content()
            }
        }
    }
}

@Composable
private fun TrackItemRow(
    title: String,
    subText: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) NetflixRed.copy(alpha = 0.2f) else NetflixCardBg,
        border = if (isSelected) BorderStroke(1.dp, NetflixRed) else BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    color = if (isSelected) NetflixWhite else NetflixLightGrey,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
                if (!subText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = subText,
                            color = NetflixLightGrey,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = ZaswixIcons.Check,
                    contentDescription = "Selected",
                    tint = NetflixRed,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
