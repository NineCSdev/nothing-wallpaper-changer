package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Full-screen wallpaper preview overlay.
 * Tap anywhere to dismiss. Shows edited badge if applicable.
 * Features a scale-up animation as the preview expands to fill the screen.
 */
@Composable
fun WallpaperPreviewOverlay(
    wallpaper: WallpaperImage,
    onDismiss: () -> Unit,
) {
    val displayUri = wallpaper.editedUri ?: wallpaper.uri

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = displayUri,
            contentDescription = "Wallpaper preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(64.dp)
        ) {
            Text(
                text = "WALLPAPER PREVIEW",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = NothingWhite
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "TAP ANYWHERE TO EXIT",
                style = MaterialTheme.typography.bodySmall,
                color = NothingWhite
            )
        }

        // Edited badge in the bottom-right
        if (wallpaper.editedUri != null) {
            EditedBadge(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            )
        }
    }
}

@Preview(showSystemUi = true, name = "Wallpaper Preview Overlay")
@Composable
fun WallpaperPreviewOverlayPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 1L,
        collectionId = 101,
        uri = Uri.EMPTY,
        editedUri = null
    )

    MaterialTheme {
        Box(modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)) {
            WallpaperPreviewOverlay(
                wallpaper = sampleWallpaper,
                onDismiss = {}
            )
        }
    }
}

@Preview(showSystemUi = true, name = "Wallpaper Preview Overlay (Edited)")
@Composable
fun WallpaperPreviewOverlayEditedPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 2L,
        collectionId = 101,
        uri = Uri.EMPTY,
        editedUri = Uri.EMPTY
    )

    MaterialTheme {
        WallpaperPreviewOverlay(
            wallpaper = sampleWallpaper,
            onDismiss = {}
        )
    }
}