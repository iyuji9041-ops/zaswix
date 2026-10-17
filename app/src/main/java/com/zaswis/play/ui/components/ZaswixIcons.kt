package com.zaswis.play.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object ZaswixIcons {
    val ArrowBack = Icons.AutoMirrored.Filled.ArrowBack
    val Add = Icons.Default.Add
    val Clear = Icons.Default.Clear

    val PlayArrow: ImageVector by lazy {
        ImageVector.Builder(
            name = "PlayArrow",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(8f, 5f)
                lineTo(19f, 12f)
                lineTo(8f, 19f)
                close()
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 19f)
                horizontalLineTo(10f)
                verticalLineTo(5f)
                horizontalLineTo(6f)
                verticalLineTo(19f)
                close()
                moveTo(14f, 5f)
                verticalLineTo(19f)
                horizontalLineTo(18f)
                verticalLineTo(5f)
                horizontalLineTo(14f)
                close()
            }
        }.build()
    }

    val Info: ImageVector by lazy {
        ImageVector.Builder(
            name = "Info",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(13f, 17f)
                horizontalLineTo(11f)
                verticalLineTo(11f)
                horizontalLineTo(13f)
                verticalLineTo(17f)
                close()
                moveTo(13f, 9f)
                horizontalLineTo(11f)
                verticalLineTo(7f)
                horizontalLineTo(13f)
                verticalLineTo(9f)
                close()
            }
        }.build()
    }

    val Home: ImageVector by lazy {
        ImageVector.Builder(
            name = "Home",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(10f, 20f)
                verticalLineTo(14f)
                horizontalLineTo(14f)
                verticalLineTo(20f)
                horizontalLineTo(19f)
                verticalLineTo(12f)
                horizontalLineTo(22f)
                lineTo(12f, 3f)
                lineTo(2f, 12f)
                horizontalLineTo(5f)
                verticalLineTo(20f)
                close()
            }
        }.build()
    }

    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "Search",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(15.5f, 14f)
                horizontalLineTo(14.71f)
                lineTo(14.43f, 13.73f)
                curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
                curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
                curveTo(5.91f, 3f, 3f, 5.91f, 3f, 9.5f)
                curveTo(3f, 13.09f, 5.91f, 16f, 9.5f, 16f)
                curveTo(11.11f, 16f, 12.59f, 15.41f, 13.73f, 14.43f)
                lineTo(14f, 14.71f)
                verticalLineTo(15.5f)
                lineTo(19f, 20.49f)
                lineTo(20.49f, 19f)
                lineTo(15.5f, 14f)
                close()
                moveTo(9.5f, 14f)
                curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
                curveTo(5f, 7.01f, 7.01f, 5f, 9.5f, 5f)
                curveTo(11.99f, 5f, 14f, 7.01f, 14f, 9.5f)
                curveTo(14f, 11.99f, 11.99f, 14f, 9.5f, 14f)
                close()
            }
        }.build()
    }

    val Extension: ImageVector by lazy {
        ImageVector.Builder(
            name = "Extension",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(20.5f, 11f)
                horizontalLineTo(19f)
                verticalLineTo(7f)
                curveTo(19f, 5.9f, 18.1f, 5f, 17f, 5f)
                horizontalLineTo(13f)
                verticalLineTo(3.5f)
                curveTo(13f, 2.12f, 11.88f, 1f, 10.5f, 1f)
                curveTo(9.12f, 1f, 8f, 2.12f, 8f, 3.5f)
                verticalLineTo(5f)
                horizontalLineTo(4f)
                curveTo(2.9f, 5f, 2f, 5.9f, 2f, 7f)
                verticalLineTo(11f)
                horizontalLineTo(3.5f)
                curveTo(4.88f, 11f, 6f, 12.12f, 6f, 13.5f)
                curveTo(6f, 14.88f, 4.88f, 16f, 3.5f, 16f)
                horizontalLineTo(2f)
                verticalLineTo(20f)
                curveTo(2f, 21.1f, 2.9f, 22f, 4f, 22f)
                horizontalLineTo(8f)
                verticalLineTo(20.5f)
                curveTo(8f, 19.12f, 9.12f, 18f, 10.5f, 18f)
                curveTo(11.88f, 18f, 13f, 19.12f, 13f, 20.5f)
                verticalLineTo(22f)
                horizontalLineTo(17f)
                curveTo(18.1f, 22f, 19f, 21.1f, 19f, 20f)
                verticalLineTo(16f)
                horizontalLineTo(20.5f)
                curveTo(21.88f, 16f, 23f, 14.88f, 23f, 13.5f)
                curveTo(23f, 12.12f, 21.88f, 11f, 20.5f, 11f)
                close()
            }
        }.build()
    }

    val Bookmark: ImageVector by lazy {
        ImageVector.Builder(
            name = "Bookmark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(17f, 3f)
                horizontalLineTo(7f)
                curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
                verticalLineTo(21f)
                lineTo(12f, 18f)
                lineTo(19f, 21f)
                verticalLineTo(5f)
                curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f)
                close()
            }
        }.build()
    }

    val Download: ImageVector by lazy {
        ImageVector.Builder(
            name = "Download",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
                curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
                curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
                horizontalLineTo(19f)
                curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
                curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
                close()
                moveTo(17f, 13f)
                lineTo(12f, 18f)
                lineTo(7f, 13f)
                horizontalLineTo(10f)
                verticalLineTo(9f)
                horizontalLineTo(14f)
                verticalLineTo(13f)
                horizontalLineTo(17f)
                close()
            }
        }.build()
    }

    val CloudDownload: ImageVector = Download

    val ArrowDropDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowDropDown",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(7f, 10f)
                lineTo(12f, 15f)
                lineTo(17f, 10f)
                close()
            }
        }.build()
    }

    val Check: ImageVector by lazy {
        ImageVector.Builder(
            name = "Check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(9f, 16.17f)
                lineTo(4.83f, 12f)
                lineTo(3.41f, 13.41f)
                lineTo(9f, 19f)
                lineTo(21f, 7f)
                lineTo(19.59f, 5.59f)
                close()
            }
        }.build()
    }

    val Settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "Settings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(19.14f, 12.94f)
                curveTo(19.18f, 12.63f, 19.2f, 12.32f, 19.2f, 12f)
                curveTo(19.2f, 11.68f, 19.18f, 11.37f, 19.14f, 11.06f)
                lineTo(21.41f, 9.29f)
                curveTo(21.62f, 9.13f, 21.67f, 8.83f, 21.54f, 8.6f)
                lineTo(19.4f, 4.9f)
                curveTo(19.27f, 4.67f, 18.98f, 4.58f, 18.74f, 4.68f)
                lineTo(16.07f, 5.76f)
                curveTo(15.52f, 5.33f, 14.91f, 4.98f, 14.25f, 4.71f)
                lineTo(13.85f, 1.86f)
                curveTo(13.81f, 1.6f, 13.59f, 1.41f, 13.33f, 1.41f)
                horizontalLineTo(9.07f)
                curveTo(8.81f, 1.41f, 8.59f, 1.6f, 8.55f, 1.86f)
                lineTo(8.15f, 4.71f)
                curveTo(7.49f, 4.98f, 6.88f, 5.33f, 6.33f, 5.76f)
                lineTo(3.66f, 4.68f)
                curveTo(3.42f, 4.58f, 3.13f, 4.67f, 3f, 4.9f)
                lineTo(0.86f, 8.6f)
                curveTo(0.73f, 8.83f, 0.78f, 9.13f, 0.99f, 9.29f)
                lineTo(3.26f, 11.06f)
                curveTo(3.22f, 11.37f, 3.2f, 11.68f, 3.2f, 12f)
                curveTo(3.2f, 12.32f, 3.22f, 12.63f, 3.26f, 12.94f)
                lineTo(0.99f, 14.71f)
                curveTo(0.78f, 14.87f, 0.73f, 15.17f, 0.86f, 15.4f)
                lineTo(3f, 19.1f)
                curveTo(3.13f, 19.33f, 3.42f, 19.42f, 3.66f, 19.32f)
                lineTo(6.33f, 18.24f)
                curveTo(6.88f, 18.67f, 7.49f, 19.02f, 8.15f, 19.29f)
                lineTo(8.55f, 22.14f)
                curveTo(8.59f, 22.4f, 8.81f, 22.59f, 9.07f, 22.59f)
                horizontalLineTo(13.33f)
                curveTo(13.59f, 22.59f, 13.81f, 22.4f, 13.85f, 22.14f)
                lineTo(14.25f, 19.29f)
                curveTo(14.91f, 19.02f, 15.52f, 18.67f, 16.07f, 18.24f)
                lineTo(18.74f, 19.32f)
                curveTo(18.98f, 19.42f, 19.27f, 19.33f, 19.4f, 19.1f)
                lineTo(21.54f, 15.4f)
                curveTo(21.67f, 15.17f, 21.62f, 14.87f, 21.41f, 14.71f)
                lineTo(19.14f, 12.94f)
                close()
                moveTo(11.2f, 15.5f)
                curveTo(9.27f, 15.5f, 7.7f, 13.93f, 7.7f, 12f)
                curveTo(7.7f, 10.07f, 9.27f, 8.5f, 11.2f, 8.5f)
                curveTo(13.13f, 8.5f, 14.7f, 10.07f, 14.7f, 12f)
                curveTo(14.7f, 13.93f, 13.13f, 15.5f, 11.2f, 15.5f)
                close()
            }
        }.build()
    }

    val Subtitles: ImageVector by lazy {
        ImageVector.Builder(
            name = "Subtitles",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(20f, 4f)
                horizontalLineTo(4f)
                curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
                verticalLineTo(18f)
                curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
                horizontalLineTo(20f)
                curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
                verticalLineTo(6f)
                curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
                close()
                moveTo(10f, 11f)
                horizontalLineTo(6f)
                verticalLineTo(9f)
                horizontalLineTo(10f)
                verticalLineTo(11f)
                close()
                moveTo(18f, 15f)
                horizontalLineTo(6f)
                verticalLineTo(13f)
                horizontalLineTo(18f)
                verticalLineTo(15f)
                close()
                moveTo(18f, 11f)
                horizontalLineTo(12f)
                verticalLineTo(9f)
                horizontalLineTo(18f)
                verticalLineTo(11f)
                close()
            }
        }.build()
    }

    val Audiotrack: ImageVector by lazy {
        ImageVector.Builder(
            name = "Audiotrack",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 3f)
                verticalLineTo(13.55f)
                curveTo(11.41f, 13.21f, 10.73f, 13f, 10f, 13f)
                curveTo(7.79f, 13f, 6f, 14.79f, 6f, 17f)
                curveTo(6f, 19.21f, 7.79f, 21f, 10f, 21f)
                curveTo(12.21f, 21f, 14f, 19.21f, 14f, 17f)
                verticalLineTo(7f)
                horizontalLineTo(18f)
                verticalLineTo(3f)
                horizontalLineTo(12f)
                close()
            }
        }.build()
    }

    val AspectRatio: ImageVector by lazy {
        ImageVector.Builder(
            name = "AspectRatio",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(19f, 12f)
                horizontalLineTo(21f)
                verticalLineTo(19f)
                curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
                horizontalLineTo(12f)
                verticalLineTo(19f)
                horizontalLineTo(19f)
                verticalLineTo(12f)
                close()
                moveTo(5f, 12f)
                horizontalLineTo(3f)
                verticalLineTo(5f)
                curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
                horizontalLineTo(12f)
                verticalLineTo(5f)
                horizontalLineTo(5f)
                verticalLineTo(12f)
                close()
            }
        }.build()
    }
}
