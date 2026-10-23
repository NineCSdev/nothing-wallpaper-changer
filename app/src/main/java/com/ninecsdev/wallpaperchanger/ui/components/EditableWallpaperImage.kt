    package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import com.ninecsdev.wallpaperchanger.logic.ImageProcessingUtils
import com.ninecsdev.wallpaperchanger.logic.computeCanvasEditTransform
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import kotlin.math.roundToInt

/**
 * Displays an edited wallpaper exactly as it will appear on the device screen.
 *
 * The Coil request is sized from [decodeFraction] × screen
 * (a stable resting size, NOT the measured container) so
 * shared-element flights that remeasure this composable every frame never spam
 * new image loads. The per-frame transform is draw-only.
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
 * @param fromGridThumbnail Decode from the file's [GridThumbnail] instead of its original. For
 *   small renditions only: the full-screen preview and the editor keep the original's pixels.
 */
@Composable
internal fun EditableWallpaperImage(
    wallpaper: WallpaperImage,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    decodeFraction: Float = 1f,
    memoryCacheKey: String? = null,
    placeholderMemoryCacheKey: String? = null,
    fromGridThumbnail: Boolean = false,
) {
    val edit = requireNotNull(wallpaper.editParams) { "EditableWallpaperImage requires editParams; non-edited images render with a plain AsyncImage" }

    val context = LocalContext.current
    val (screenW, screenH) = remember { ImageProcessingUtils.getWallpaperCanvasSize(context) }
    val model = remember(wallpaper.uriString, edit.zoom, decodeFraction, memoryCacheKey, placeholderMemoryCacheKey, fromGridThumbnail) {
        val frameScale = decodeFraction * edit.zoom
        val targetW = (screenW * frameScale).roundToInt().coerceIn(1, screenW)
        val targetH = (screenH * frameScale).roundToInt().coerceIn(1, screenH)
        ImageRequest.Builder(context)
            .data(
                if (fromGridThumbnail) GridThumbnail(wallpaper.fileId, wallpaper.uriString, wallpaper.sourceType)
                else wallpaper.uriString
            )
            .size(targetW, targetH)
            .scale(if (decodeFraction >= 1f || frameScale > 1f) Scale.FILL else Scale.FIT)
            .apply {
                memoryCacheKey?.let { memoryCacheKey(it) }
                placeholderMemoryCacheKey?.let { placeholderMemoryCacheKey(it) }
            }
            .build()
    }

    val painter = rememberAsyncImagePainter(model)

    Box(modifier = modifier.clipToBounds()) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (contentDescription == null) Modifier
                    else Modifier.semantics {
                        this.contentDescription = contentDescription
                        role = Role.Image
                    }
                )
                .drawBehind {
                    // Drawn at its own size under one float transform
                    val intrinsic = painter.intrinsicSize
                    // Nothing until a painter knows its size: a guessed aspect draws the wrong crop
                    if (!(intrinsic.width > 0f && intrinsic.height > 0f)) return@drawBehind
                    if (size.width <= 0f || size.height <= 0f) return@drawBehind

                    val t = computeCanvasEditTransform(
                        contentWidth = intrinsic.width,
                        contentHeight = intrinsic.height,
                        containerWidth = size.width,
                        containerHeight = size.height,
                        canvasWidth = screenW.toFloat(),
                        canvasHeight = screenH.toFloat(),
                        zoom = edit.zoom,
                        offsetX = edit.offsetX,
                        offsetY = edit.offsetY,
                    )
                    withTransform({
                        translate(left = t.drawX, top = t.drawY)
                        scale(t.scale, t.scale, pivot = Offset.Zero)
                    }) {
                        with(painter) { draw(intrinsic) }
                    }
                }
        )
    }
}