package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ninecsdev.wallpaperchanger.data.local.PreviewFile
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.SmallCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Square slot for one image of a [CollectionGridItem], drawn from the file's grid thumbnail.
 *
 * Its memory-cache key is its own, not the grid tile's.
 */
@Composable
fun ThumbnailSlot(
    file: PreviewFile?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
) {
    Box(
        modifier = modifier
            .padding(1.dp)
            .clip(shape)
            .background(NothingGray)
    ) {
        if (file != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(GridThumbnail(file.fileId, file.uriString, file.sourceType))
                    .memoryCacheKey("collection-slot-${file.uriString}")
                    .build(),
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
    WallpaperChangerTheme {
        ThumbnailSlot(
            file = null,
            modifier = Modifier.size(100.dp),
            shape = RoundedCornerShape(SmallCornerRadius)
        )
    }
}