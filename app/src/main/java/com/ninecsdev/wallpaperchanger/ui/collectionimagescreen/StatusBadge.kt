package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Small rounded status indicator box with a centered icon.
 * Callers supply their own fully-configured [Icon] (imageVector or painter, tint,
 * contentDescription) sized to match [size].
 */
@Composable
internal fun StatusBadge(
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    size: Int = 14,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size((size + 8).dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@Preview(name = "Status Badge preview", backgroundColor = 0xFF000000)
@Composable
private fun StatusBadgePreview() {
    Surface(
        color = NothingBlack,
        modifier = Modifier.padding(16.dp)
    ) {
        StatusBadge(backgroundColor = NothingRed) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = NothingWhite,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
