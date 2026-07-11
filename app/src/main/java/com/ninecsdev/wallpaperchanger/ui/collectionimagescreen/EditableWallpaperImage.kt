package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.ninecsdev.wallpaperchanger.logic.ImageProcessingUtils
import com.ninecsdev.wallpaperchanger.logic.computeEditTransform
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import kotlin.math.ceil
import kotlin.math.roundToInt

// TODO: Add tests for the frame math here (see "EditTransform Tests" in the notes)

/**
 * Displays an edited wallpaper exactly as it will appear on the device screen.
 *
 * The Coil request is sized from [decodeFraction] × screen
 * (a stable resting size, NOT the measured container) so
 * shared-element flights that remeasure this composable every frame never spam
 * new image loads. The per-frame transform is a pure graphicsLayer.
 *
 * @param wallpaper The wallpaper to display. Must carry [editParams][WallpaperImage.editParams].
 * @param contentDescription Accessibility description.
 * @param modifier Modifier applied to the outer clipping Box.
 * @param decodeFraction Fraction of the screen this composable occupies at rest
 *   (1f full-screen overlay, 1/columns for a grid cell). Used for decode.
 * @param memoryCacheKey Optional explicit Coil memory-cache key for this request
 *   (set by the grid so the overlay can reuse the bitmap as a placeholder).
 * @param placeholderMemoryCacheKey Optional cache key of an already-loaded
 *   smaller rendition to draw while this request decodes (set by the overlay).
 */
@Composable
internal fun EditableWallpaperImage(
    wallpaper: WallpaperImage,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    decodeFraction: Float = 1f,
    memoryCacheKey: String? = null,
    placeholderMemoryCacheKey: String? = null,
) {
    val edit = requireNotNull(wallpaper.editParams) { "EditableWallpaperImage requires editParams; non-edited images render with a plain AsyncImage" }

    val context = LocalContext.current
    val (screenW, screenH) = remember { ImageProcessingUtils.getScreenDimensions(context) }
    // null until ANY painter reports an intrinsic size. Guessing rendered a visible wrong-crop flash during flights.
    var imageAspectRatio by remember(wallpaper.uri) { mutableStateOf<Float?>(null) }

    val model = remember(wallpaper.uri, edit.zoom, decodeFraction, memoryCacheKey, placeholderMemoryCacheKey) {
        val zoomFactor = ceil(edit.zoom).toInt().coerceAtLeast(1)
        val targetW = (screenW * decodeFraction * zoomFactor).roundToInt().coerceIn(1, screenW)
        val targetH = (screenH * decodeFraction * zoomFactor).roundToInt().coerceIn(1, screenH)
        ImageRequest.Builder(context)
            .data(wallpaper.uri)
            .size(targetW, targetH)
            .scale(Scale.FILL)
            .apply {
                memoryCacheKey?.let { memoryCacheKey(it) }
                placeholderMemoryCacheKey?.let { placeholderMemoryCacheKey(it) }
            }
            .build()
    }

    fun learnAspectRatio(painter: Painter?) {
        val intrinsic: Size = painter?.intrinsicSize ?: return
        // NaN (Size.Unspecified, e.g. color painters) fails both comparisons.
        if (intrinsic.width > 0 && intrinsic.height > 0) {
            imageAspectRatio = intrinsic.width / intrinsic.height
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            onLoading = { state -> learnAspectRatio(state.painter) },
            onSuccess = { state -> learnAspectRatio(state.painter) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Hidden with a placeholder (not wrongly-transformed) until the aspect is known;
                    val aspect = imageAspectRatio
                    if (aspect == null || size.width <= 0f || size.height <= 0f) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val cw = size.width
                    val ch = size.height
                    // Virtual screen-aspect frame, cover-fitted into the container
                    // (in container pixels; one axis matches, the other overflows).
                    val cover = maxOf(cw / screenW, ch / screenH)
                    val frameW = screenW * cover
                    val frameH = screenH * cover
                    val t = computeEditTransform(
                        contentWidth = aspect,
                        contentHeight = 1f,
                        containerWidth = frameW,
                        containerHeight = frameH,
                        zoom = edit.zoom,
                        offsetX = edit.offsetX,
                        offsetY = edit.offsetY,
                    )
                    val containerFitScale = minOf(cw / aspect, ch)
                    scaleX = t.scale / containerFitScale
                    scaleY = t.scale / containerFitScale
                    translationX = t.panX
                    translationY = t.panY
                }
        )
    }
}