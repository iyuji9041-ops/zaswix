package com.zaswis.play.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaswis.play.ui.theme.NetflixBlack
import com.zaswis.play.ui.theme.NetflixLightGrey
import com.zaswis.play.ui.theme.NetflixRed
import com.zaswis.play.ui.theme.NetflixWhite

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", ZaswixIcons.Home)
    object Search : Screen("search", "Search", ZaswixIcons.Search)
    object Plugins : Screen("plugins", "Plugins", ZaswixIcons.Extension)
    object MyList : Screen("mylist", "My List", ZaswixIcons.Bookmark)
}

@Composable
fun NetflixBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    val items = listOf(
        Screen.Home,
        Screen.Search,
        Screen.Plugins,
        Screen.MyList
    )

    NavigationBar(
        containerColor = NetflixBlack,
        tonalElevation = 8.dp
    ) {
        items.forEach { screen ->
            val selected = currentRoute == screen.route
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = screen.title
                    )
                },
                label = {
                    Text(
                        text = screen.title,
                        fontSize = 10.sp
                    )
                },
                selected = selected,
                onClick = { onNavigate(screen.route) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NetflixRed,
                    selectedTextColor = NetflixWhite,
                    indicatorColor = NetflixBlack,
                    unselectedIconColor = NetflixLightGrey,
                    unselectedTextColor = NetflixLightGrey
                )
            )
        }
    }
}
