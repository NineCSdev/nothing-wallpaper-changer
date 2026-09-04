package com.ninecsdev.wallpaperchanger.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Bordered preview thumbnail with a placeholder icon, used by the main-screen cards and by the
 * edit-collection card's default-wallpaper row. Collection grids use the plain gray
 * [ThumbnailSlot] instead.
 *
 * [content] replaces the plain image for callers that draw the thumbnail themselves
 */
@Composable
internal fun NothingThumbnail(
    uri: Uri?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cornerRadius: Int = 4,
    content: (@Composable BoxScope.() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(NothingWhite.copy(alpha = 0.05f))
            .border(1.dp, NothingWhite.copy(alpha = 0.15f), RoundedCornerShape(cornerRadius.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (content != null) {
            content()
        } else if (uri != null) {
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
/**
 * A [NothingThumbnail] that honors the wallpaper's own framing: an edited membership renders
 * through [EditableWallpaperImage] so the thumbnail shows what will actually be applied, an
 * unedited one falls back to the plain image, and a null wallpaper to the placeholder.
 *
 * [decodeFraction] is the share of the screen width the thumbnail occupies; callers approximate it.
 */
@Composable
internal fun WallpaperThumbnail(
    wallpaper: WallpaperImage?,
    modifier: Modifier = Modifier,
    decodeFraction: Float = 1f
) {
    if (wallpaper?.editParams == null) {
        NothingThumbnail(uri = wallpaper?.uri, modifier = modifier)
        return
    }

    // Drawn as the frame's content, so the edited render is clipped to its corners and keeps its border
    NothingThumbnail(uri = null, modifier = modifier) {
        EditableWallpaperImage(
            wallpaper = wallpaper,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            decodeFraction = decodeFraction
        )
    }
}
