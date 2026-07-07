package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.ninecsdev.wallpaperchanger.logic.computeEditTransform
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import kotlin.math.ceil

/**
 * A composable that displays a [WallpaperImage] and applies the user's edit transform
 * (zoom + pan offset) in the UI via [graphicsLayer] when edit params are present.
 *
 * When no edit params exist the image is displayed normally using [contentScale].
 * When edit params exist [ContentScale.Fit] is used as the base and the zoom/offset
 * is applied as a graphics layer transform — sharing
 * [computeEditTransform][computeEditTransform] with the
 * editor preview and [BufferManager][com.ninecsdev.wallpaperchanger.logic.BufferManager]'s
 * render path.
 *
 * @param wallpaper The wallpaper to display.
 * @param contentDescription Accessibility description.
 * @param contentScale Scale strategy when there are no edit params. Defaults to [ContentScale.Crop].
 * @param modifier Modifier applied to the outer clipping Box.
 */
@Composable
internal fun EditableWallpaperImage(
    wallpaper: WallpaperImage,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val edit = wallpaper.editParams
    val hasEdit = edit != null
    var imageAspectRatio by remember(wallpaper.uri) { mutableFloatStateOf(1f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current

    val model = if (hasEdit && viewSize != IntSize.Zero) {
        val zoomFactor = ceil(edit.zoom).toInt().coerceAtLeast(1)
        val metrics = LocalResources.current.displayMetrics
        val targetW = (viewSize.width * zoomFactor).coerceAtMost(metrics.widthPixels)
        val targetH = (viewSize.height * zoomFactor).coerceAtMost(metrics.heightPixels)
        ImageRequest.Builder(context)
            .data(wallpaper.uri)
            .size(targetW, targetH)
            .scale(Scale.FILL)
            .build()
    } else {
        wallpaper.uri
    }

    Box(modifier = modifier.clipToBounds()) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = if (hasEdit) ContentScale.Fit else contentScale,
            onSuccess = { state ->
                val intrinsic: Size = state.painter.intrinsicSize
                if (intrinsic.width > 0 && intrinsic.height > 0) {
                    imageAspectRatio = intrinsic.width / intrinsic.height
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it }
                .then(
                    if (hasEdit && viewSize != IntSize.Zero) {
                        Modifier.graphicsLayer {
                            val t = computeEditTransform(
                                contentWidth = imageAspectRatio,
                                contentHeight = 1f,
                                containerWidth = viewSize.width.toFloat(),
                                containerHeight = viewSize.height.toFloat(),
                                zoom = edit.zoom,
                                offsetX = edit.offsetX,
                                offsetY = edit.offsetY,
                            )
                            scaleX = edit.zoom
                            scaleY = edit.zoom
                            translationX = t.panX
                            translationY = t.panY
                        }
                    } else {
                        Modifier
                    }
                )
        )
    }
}
