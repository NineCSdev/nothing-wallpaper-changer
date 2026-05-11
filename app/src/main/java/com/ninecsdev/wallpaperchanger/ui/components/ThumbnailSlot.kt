package com.ninecsdev.wallpaperchanger.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray

/**
 * Square slot for a single image preview.
 * Shared between CollectionGridItem and CollectionImageScreen.
 */
@Composable
fun ThumbnailSlot(
    uri: Uri?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
) {
    Box(
        modifier = modifier
            .padding(1.dp)
            .clip(shape)
            .background(NothingGray)
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewThumbnailSlot() {
    MaterialTheme {
        ThumbnailSlot(
            uri = null,
            modifier = Modifier.size(100.dp),
            shape = RoundedCornerShape(8.dp)
        )
    }
}