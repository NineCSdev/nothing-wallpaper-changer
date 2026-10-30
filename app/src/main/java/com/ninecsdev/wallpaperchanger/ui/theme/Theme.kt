package com.ninecsdev.wallpaperchanger.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val NothingColorScheme = darkColorScheme(
    primary = NothingWhite,
    onPrimary = NothingBlack,
    secondary = NothingWhite,
    onSecondary = NothingBlack,
    background = NothingBlack,
    onBackground = NothingWhite,
    surface = NothingBlack,
    onSurface = NothingWhite,
    error = NothingRed,
    onError = NothingWhite,
)

private val NothingShapes = Shapes(
    small = RoundedCornerShape(SmallCornerRadius),
    large = RoundedCornerShape(CardCornerRadius),
)

@Composable
fun WallpaperChangerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NothingColorScheme,
        shapes = NothingShapes,
    ) {
        CompositionLocalProvider(LocalContentColor provides NothingWhite, content = content)
    }
}
