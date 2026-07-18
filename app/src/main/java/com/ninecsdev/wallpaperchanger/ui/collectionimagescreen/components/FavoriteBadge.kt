package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
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
 * Small rounded white box with a filled red heart.
 * Passive indicator that a wallpaper's file is favourited (a member of the Favourites collection).
 * Shown on every copy of a favourited file across collections, suppressed inside Favourites itself.
 */
@Composable
internal fun FavoriteBadge(modifier: Modifier = Modifier, size: Int = 14) {
    StatusBadge(
        backgroundColor = NothingWhite,
        modifier = modifier,
        size = size
    ) {
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = stringResource(R.string.cd_favorited_wallpaper),
            tint = NothingRed,
            modifier = Modifier.size(size.dp)
        )
    }
}

@Preview(name = "Favorite Badge preview", backgroundColor = 0xFF000000)
@Composable
private fun FavoriteBadgePreview() {
    Surface(
        color = NothingBlack,
        modifier = Modifier.padding(16.dp)
    ) {
        FavoriteBadge()
    }
}
