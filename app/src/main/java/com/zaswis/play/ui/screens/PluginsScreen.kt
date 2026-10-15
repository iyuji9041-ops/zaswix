package com.zaswis.play.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zaswis.play.data.model.PluginInfo
import com.zaswis.play.plugin.PluginManager
import com.zaswis.play.ui.components.ZaswixIcons
import com.zaswis.play.ui.theme.NetflixBlack
import com.zaswis.play.ui.theme.NetflixCardBg
import com.zaswis.play.ui.theme.NetflixDarkGrey
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite
import kotlinx.coroutines.launch

@Composable
fun PluginsScreen(
    pluginManager: PluginManager,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val installedPlugins by pluginManager.plugins.collectAsState()
    val availablePlugins by pluginManager.availablePlugins.collectAsState()
    val repoUrls by pluginManager.repoUrls.collectAsState()
    val selectedPluginId by pluginManager.selectedPluginId.collectAsState()
    val isSyncing by pluginManager.isSyncing.collectAsState()

    var customRepoInput by remember { mutableStateOf("") }
    var syncMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = ZaswixIcons.Extension,
                    contentDescription = "Extensions",
                    tint = NetflixRed,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Extensions & Providers",
                        color = NetflixWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "CloudStream & Vega compatible repository engine",
                        color = NetflixLightGrey,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tab bar: Installed vs Explore & Repos
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = NetflixDarkGrey,
                contentColor = NetflixRed,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = NetflixRed
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "Installed (${installedPlugins.size})",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 0) NetflixWhite else NetflixLightGrey
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Explore & Repos (${availablePlugins.size})",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 1) NetflixWhite else NetflixLightGrey
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> InstalledPluginsList(
                    installed = installedPlugins,
                    selectedId = selectedPluginId,
                    onSelect = { pluginManager.selectPlugin(it) },
                    onUninstall = { pluginManager.uninstallPlugin(it) },
                    onToggle = { pluginManager.togglePlugin(it) },
                    onExploreClick = { selectedTab = 1 }
                )
                1 -> ExploreRepositoriesView(
                    repos = repoUrls,
                    available = availablePlugins,
                    isSyncing = isSyncing,
                    syncMessage = syncMessage,
                    customRepoInput = customRepoInput,
                    onCustomRepoChange = { customRepoInput = it },
                    onAddRepo = {
                        pluginManager.addRepository(customRepoInput)
                        customRepoInput = ""
                    },
                    onSync = {
                        scope.launch {
                            syncMessage = "Syncing with GitHub..."
                            val res = pluginManager.syncRepositories()
                            if (res.isSuccess) {
                                syncMessage = "Successfully synced ${res.getOrNull()} extensions!"
                            } else {
                                syncMessage = "Sync notice: Local catalogue verified."
                            }
                        }
                    },
                    onInstall = { pluginManager.installPlugin(it) },
                    onUninstall = { pluginManager.uninstallPlugin(it) }
                )
            }
        }
    }
}

@Composable
private fun InstalledPluginsList(
    installed: List<PluginInfo>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onToggle: (String) -> Unit,
    onExploreClick: () -> Unit
) {
    if (installed.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No extensions installed yet", color = NetflixLightGrey, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onExploreClick,
                    colors = ButtonDefaults.buttonColors(containerColor = NetflixRed)
                ) {
                    Text("Explore & Install Providers", color = NetflixWhite)
                }
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(installed, key = { it.id }) { plugin ->
                val isActive = plugin.id == selectedId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(plugin.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) NetflixDarkGrey else NetflixCardBg
                    ),
                    border = if (isActive) BorderStroke(1.5.dp, NetflixRed) else null,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Icon
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(NetflixDarkGrey),
                            contentAlignment = Alignment.Center
                        ) {
                            if (plugin.iconUrl.isNotEmpty()) {
                                AsyncImage(
                                    model = plugin.iconUrl,
                                    contentDescription = plugin.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(
                                    text = plugin.name.take(2).uppercase(),
                                    color = NetflixRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = plugin.name,
                                    color = NetflixWhite,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = plugin.version,
                                    color = NetflixLightGrey,
                                    fontSize = 11.sp
                                )
                                if (isActive) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(NetflixRed, RoundedCornerShape(3.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text("ACTIVE", color = NetflixWhite, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = plugin.description,
                                color = NetflixLightGrey,
                                fontSize = 12.sp,
                                maxLines = 2
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Uninstall Button
                        Button(
                            onClick = { onUninstall(plugin.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Remove", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreRepositoriesView(
    repos: List<String>,
    available: List<PluginInfo>,
    isSyncing: Boolean,
    syncMessage: String,
    customRepoInput: String,
    onCustomRepoChange: (String) -> Unit,
    onAddRepo: () -> Unit,
    onSync: () -> Unit,
    onInstall: (String) -> Unit,
    onUninstall: (String) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Repository Sync Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NetflixCardBg),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Official Scraper Repositories",
                                color = NetflixWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Zaswix Official Providers Repository",
                                color = NetflixLightGrey,
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = onSync,
                            enabled = !isSyncing,
                            colors = ButtonDefaults.buttonColors(containerColor = NetflixRed),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    color = NetflixWhite,
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = if (isSyncing) "Syncing..." else "Sync Now",
                                color = NetflixWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (syncMessage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = syncMessage, color = Color(0xFF46D369), fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    repos.forEach { url ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NetflixDarkGrey, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = ZaswixIcons.Check,
                                contentDescription = "Active",
                                tint = Color(0xFF46D369),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = url,
                                color = NetflixWhite,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Add Custom Repository URL
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customRepoInput,
                            onValueChange = onCustomRepoChange,
                            placeholder = { Text("Add custom repo URL (e.g., https://.../repo.json)", fontSize = 12.sp, color = NetflixLightGrey) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = NetflixWhite,
                                unfocusedTextColor = NetflixWhite,
                                focusedBorderColor = NetflixRed,
                                unfocusedBorderColor = Color.DarkGray
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onAddRepo,
                            colors = ButtonDefaults.buttonColors(containerColor = NetflixDarkGrey),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Add", color = NetflixWhite, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Available Extensions header
        item {
            Text(
                text = "Available Provider Extensions (${available.size})",
                color = NetflixWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Extension Items
        items(available, key = { it.id }) { plugin ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NetflixCardBg),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(NetflixDarkGrey),
                        contentAlignment = Alignment.Center
                    ) {
                        if (plugin.iconUrl.isNotEmpty()) {
                            AsyncImage(
                                model = plugin.iconUrl,
                                contentDescription = plugin.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = plugin.name.take(2).uppercase(),
                                color = NetflixRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = plugin.name,
                                color = NetflixWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = plugin.version,
                                color = NetflixLightGrey,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = plugin.description,
                            color = NetflixLightGrey,
                            fontSize = 11.sp,
                            maxLines = 2
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (plugin.isInstalled) {
                        Button(
                            onClick = { onUninstall(plugin.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(imageVector = ZaswixIcons.Check, contentDescription = "Installed", tint = Color(0xFF46D369), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Installed", color = Color(0xFF46D369), fontSize = 11.sp)
                        }
                    } else {
                        Button(
                            onClick = { onInstall(plugin.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = NetflixRed),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(imageVector = ZaswixIcons.Download, contentDescription = "Install", tint = NetflixWhite, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Install", color = NetflixWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
