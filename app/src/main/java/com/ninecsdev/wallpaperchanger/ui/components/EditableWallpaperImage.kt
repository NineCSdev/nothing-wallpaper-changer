package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.walleditscreen.calculateTransform

/**
 * A composable that displays a [WallpaperImage] and applies the user's edit transform
 * (zoom + pan offset) in the UI via [graphicsLayer] when edit params are present.
 *
 * When no edit params exist the image is displayed normally using [contentScale].
 * When edit params exist [ContentScale.Fit] is used as the base and the zoom/offset
 * is applied as a graphics layer transform — matching the math in the editor preview
 * and in [BufferManager][com.ninecsdev.wallpaperchanger.logic.BufferManager].
 *
 * @param wallpaper The wallpaper to display.
 * @param contentDescription Accessibility description.
 * @param contentScale Scale strategy when there are no edit params. Defaults to [ContentScale.Crop].
 * @param modifier Modifier applied to the [AsyncImage].
 */
@Composable
fun EditableWallpaperImage(
    wallpaper: WallpaperImage,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val hasEdit = wallpaper.editZoom != null
    var imageAspectRatio by remember(wallpaper.uri) { mutableFloatStateOf(1f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    AsyncImage(
        model = wallpaper.uri,
        contentDescription = contentDescription,
        // When an edit is applied we use Fit so the image is fully visible and the
        // graphicsLayer scale/translate produces the correct result.
        contentScale = if (hasEdit) ContentScale.Fit else contentScale,
        onSuccess = { state ->
            val intrinsic: Size = state.painter.intrinsicSize
            if (intrinsic.width > 0 && intrinsic.height > 0) {
                imageAspectRatio = intrinsic.width / intrinsic.height
            }
        },
        modifier = modifier
            .onSizeChanged { viewSize = it }
            .then(
                if (hasEdit && viewSize != IntSize.Zero) {
                    val zoom = wallpaper.editZoom ?: 1f
                    val offsetX = wallpaper.editOffsetX ?: 0f
                    val offsetY = wallpaper.editOffsetY ?: 0f
                    Modifier.graphicsLayer {
                        val t = calculateTransform(
                            viewWidth = viewSize.width.toFloat(),
                            viewHeight = viewSize.height.toFloat(),
                            imageAspectRatio = imageAspectRatio,
                            zoom = zoom,
                            offsetX = offsetX,
                            offsetY = offsetY,
                        )
                        scaleX = t.scale
                        scaleY = t.scale
                        translationX = t.translationX
                        translationY = t.translationY
                    }
                } else {
                    Modifier
                }
            )
    )
}
