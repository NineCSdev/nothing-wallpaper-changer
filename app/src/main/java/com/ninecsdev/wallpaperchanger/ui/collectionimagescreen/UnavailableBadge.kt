package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Small rounded box with a warning icon.
 * Indicates that a wallpaper's source file could no longer be read (deleted, permission
 * revoked, offline) — it is excluded from rotation until it becomes readable again.
 */
@Composable
internal fun UnavailableBadge(modifier: Modifier = Modifier, size: Int = 14) {
    StatusBadge(
        backgroundColor = NothingRed,
        modifier = modifier,
        size = size
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = stringResource(R.string.cd_unavailable_wallpaper),
            tint = NothingWhite,
            modifier = Modifier.size(size.dp)
        )
    }
}

@Preview(name = "Unavailable Badge preview", backgroundColor = 0xFF000000)
@Composable
private fun UnavailableBadgePreview() {
    Surface(
        color = NothingBlack,
        modifier = Modifier.padding(16.dp)
    ) {
        UnavailableBadge()
    }
}
