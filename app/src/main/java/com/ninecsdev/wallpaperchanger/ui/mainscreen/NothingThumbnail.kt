package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Universal preview thumbnail for a wallpaper.
 * Used in Grids, Selection Cards, and Default Wallpaper settings.
 */
@Composable
internal fun NothingThumbnail(
    uri: Uri?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cornerRadius: Int = 4
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(NothingWhite.copy(alpha = 0.05f))
            .border(1.dp, NothingWhite.copy(alpha = 0.15f), RoundedCornerShape(cornerRadius.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.icon_preview_placeholder),
                contentDescription = null,
                tint = NothingWhite.copy(alpha = 0.2f)
            )
        }
    }
}