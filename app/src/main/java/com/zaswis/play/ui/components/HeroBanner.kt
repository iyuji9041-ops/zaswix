package com.zaswis.play.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.ui.theme.NetflixBlack
import com.zaswis.play.ui.theme.NetflixDarkGrey
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite
import kotlinx.coroutines.delay

/**
 * Netflix-style Swipable Hero Carousel with HorizontalPager and 5-second auto-scroll
 */
@Composable
fun HeroBanner(
    items: List<MediaItem>,
    onPlayClick: (MediaItem) -> Unit,
    onDetailsClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })

    // Auto-scroll every 5 seconds
    LaunchedEffect(pagerState, items.size) {
        while (true) {
            delay(5000L)
            if (!pagerState.isScrollInProgress && items.isNotEmpty()) {
                val nextPage = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(
                    page = nextPage,
                    animationSpec = tween(durationMillis = 800)
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(490.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val mediaItem = items[page]

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onDetailsClick(mediaItem) }
            ) {
                // High-resolution backdrop image
                AsyncImage(
                    model = mediaItem.backdropUrl.ifEmpty { mediaItem.posterUrl },
                    contentDescription = mediaItem.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Top-to-bottom dark vertical gradient (Glassmorphic dark aesthetic)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.55f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.25f),
                                    NetflixBlack.copy(alpha = 0.70f),
                                    NetflixBlack.copy(alpha = 0.95f),
                                    NetflixBlack
                                )
                            )
                        )
                )

                // Hero Movie Details & Action Buttons
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Provider badge
                    Surface(
                        color = NetflixRed.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = mediaItem.providerName.uppercase(),
                            color = NetflixWhite,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    // Title
                    Text(
                        text = mediaItem.title,
                        color = NetflixWhite,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 30.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Rating, Year & Duration badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = Color(0x33FFFFFF),
                            shape = RoundedCornerShape(3.dp)
                        ) {
                            Text(
                                text = "★ ${mediaItem.rating}",
                                color = Color(0xFFFFD700),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }

                        Text(
                            text = mediaItem.year,
                            color = NetflixLightGrey,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = "•",
                            color = NetflixLightGrey,
                            fontSize = 10.sp
                        )

                        Text(
                            text = mediaItem.durationOrSeasons,
                            color = NetflixLightGrey,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Genres
                    Text(
                        text = mediaItem.genres.take(4).joinToString(" • "),
                        color = NetflixLightGrey,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons: My List, Play, and More Info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // My List Button
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { /* My List toggle */ }
                                .padding(horizontal = 16.dp)
                        ) {
                            Icon(
                                imageVector = ZaswixIcons.Add,
                                contentDescription = "My List",
                                tint = NetflixWhite,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "My List",
                                color = NetflixLightGrey,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }

                        // Netflix Play Button (Solid White with Black Text)
                        Button(
                            onClick = { onPlayClick(mediaItem) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NetflixWhite,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .height(42.dp)
                                .padding(horizontal = 8.dp)
                        ) {
                            Icon(
                                imageVector = ZaswixIcons.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Play",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }

                        // Glassmorphic Info Button (Subtle translucent glass)
                        Surface(
                            onClick = { onDetailsClick(mediaItem) },
                            color = Color(0x3DFFFFFF),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                            modifier = Modifier
                                .height(42.dp)
                                .padding(horizontal = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = ZaswixIcons.Info,
                                    contentDescription = "More Info",
                                    tint = NetflixWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "More Info",
                                    color = NetflixWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Carousel Page Indicator Dots
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(items.size) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .size(
                            width = if (isSelected) 18.dp else 6.dp,
                            height = 5.dp
                        )
                        .background(
                            color = if (isSelected) NetflixWhite else Color.White.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(3.dp)
                        )
                )
            }
        }
    }
}

/**
 * Backward-compatible single item HeroBanner overload
 */
@Composable
fun HeroBanner(
    mediaItem: MediaItem,
    onPlayClick: (MediaItem) -> Unit,
    onDetailsClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    HeroBanner(
        items = listOf(mediaItem),
        onPlayClick = onPlayClick,
        onDetailsClick = onDetailsClick,
        modifier = modifier
    )
}
