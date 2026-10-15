package com.zaswis.play.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaswis.play.data.model.MediaCategory
import com.zaswis.play.data.model.MediaItem
import com.zaswis.play.data.repository.MediaRepository
import com.zaswis.play.plugin.PluginManager
import com.zaswis.play.ui.components.HeroBanner
import com.zaswis.play.ui.components.MediaRow
import com.zaswis.play.ui.components.ZaswixIcons
import com.zaswis.play.ui.theme.NetflixBlack
import com.zaswis.play.ui.theme.NetflixCardBg
import com.zaswis.play.ui.theme.NetflixDarkGrey
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite

@Composable
fun HomeScreen(
    mediaRepository: MediaRepository,
    pluginManager: PluginManager,
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    onSearchClick: () -> Unit,
    onPluginsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val plugins by pluginManager.plugins.collectAsState()
    val selectedPluginId by pluginManager.selectedPluginId.collectAsState()
    val categories by mediaRepository.getHomeCategories(selectedPluginId).collectAsState(initial = emptyList())
    val heroBannerItems by mediaRepository.heroBannerItems.collectAsState()
    var showProviderDialog by remember { mutableStateOf(false) }

    val activePlugin = remember(plugins, selectedPluginId) {
        plugins.find { it.id == selectedPluginId } ?: plugins.firstOrNull()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // Hero Banner at top (Netflix HorizontalPager)
            item {
                HeroBanner(
                    items = heroBannerItems,
                    onPlayClick = onPlayClick,
                    onDetailsClick = onMediaClick
                )
            }

            // Provider Quick-Switch Chips Bar (Single-plugin switching)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        text = "ACTIVE SOURCE PROVIDER",
                        color = NetflixLightGrey,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        plugins.forEach { plugin ->
                            val isSelected = plugin.id == selectedPluginId
                            Surface(
                                onClick = { pluginManager.selectPlugin(plugin.id) },
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) NetflixRed else NetflixDarkGrey,
                                border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF333333))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = ZaswixIcons.Check,
                                            contentDescription = "Selected",
                                            tint = NetflixWhite,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Text(
                                        text = plugin.name,
                                        color = NetflixWhite,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Categories horizontal carousels
            items(categories) { category ->
                MediaRow(
                    category = category,
                    onItemClick = onMediaClick
                )
            }
        }

        // Netflix Floating Glass Header Bar
        TopNetflixHeader(
            activePluginName = activePlugin?.name ?: "BollyFlix",
            onProviderClick = { showProviderDialog = true },
            onSearchClick = onSearchClick,
            onPluginsClick = onPluginsClick,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        )

        // Provider Selector Dialog
        if (showProviderDialog) {
            AlertDialog(
                onDismissRequest = { showProviderDialog = false },
                containerColor = NetflixCardBg,
                title = {
                    Column {
                        Text(
                            text = "Select Streaming Source",
                            color = NetflixWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Single-Plugin Mode: only 1 active provider at a time",
                            color = NetflixLightGrey,
                            fontSize = 12.sp
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        plugins.forEach { plugin ->
                            val isSelected = plugin.id == selectedPluginId
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pluginManager.selectPlugin(plugin.id)
                                        showProviderDialog = false
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) NetflixRed.copy(alpha = 0.2f) else NetflixDarkGrey
                                ),
                                border = if (isSelected) BorderStroke(1.5.dp, NetflixRed) else null,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = plugin.name,
                                            color = NetflixWhite,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = plugin.category,
                                            color = if (isSelected) NetflixRed else NetflixLightGrey,
                                            fontSize = 12.sp
                                        )
                                    }
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .background(NetflixRed, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "ACTIVE",
                                                color = NetflixWhite,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showProviderDialog = false }) {
                        Text("Close", color = NetflixRed, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
fun TopNetflixHeader(
    activePluginName: String,
    onProviderClick: () -> Unit,
    onSearchClick: () -> Unit,
    onPluginsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Zaswix Logo
            Text(
                text = "ZASWIX",
                color = NetflixRed,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )

            // Center: Active Provider Switcher Pill
            Surface(
                onClick = onProviderClick,
                shape = RoundedCornerShape(16.dp),
                color = NetflixDarkGrey.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, NetflixRed.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "🌐",
                        fontSize = 12.sp
                    )
                    Text(
                        text = activePluginName,
                        color = NetflixWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = ZaswixIcons.ArrowDropDown,
                        contentDescription = "Switch Provider",
                        tint = NetflixWhite,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Right: Action Icons (Search & Plugins)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = ZaswixIcons.Search,
                        contentDescription = "Search",
                        tint = NetflixWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onPluginsClick) {
                    Icon(
                        imageVector = ZaswixIcons.Extension,
                        contentDescription = "Plugins",
                        tint = NetflixRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
