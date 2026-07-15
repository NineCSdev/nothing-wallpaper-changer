package com.ninecsdev.wallpaperchanger.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** The app is a fixed dark, monochrome-plus-red surface. */
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

/** Shape tokens. */
private val NothingShapes = Shapes(
    small = RoundedCornerShape(SmallCornerRadius),
    large = RoundedCornerShape(CardCornerRadius),
)

/**
 * App theme: the Nothing black/white/red palette, the default Material type scale (the
 * Nothing text styles live in [NothingType]), and [NothingShapes]. Content color defaults
 * to white so every `Text` renders on-brand without repeating the color per call site.
 */
@Composable
fun WallpaperChangerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NothingColorScheme,
        shapes = NothingShapes,
    ) {
        CompositionLocalProvider(LocalContentColor provides NothingWhite, content = content)
    }
}
