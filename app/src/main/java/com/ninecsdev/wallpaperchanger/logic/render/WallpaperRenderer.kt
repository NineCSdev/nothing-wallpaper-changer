package com.ninecsdev.wallpaperchanger.logic.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.atmosphere.RenderedAtmosphere
import com.ninecsdev.wallpaperchanger.logic.atmosphere.SeedExtractor
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.VertexInfo
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import com.ninecsdev.wallpaperchanger.util.Bitmaps
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a wallpaper for a destination mode: decode, edit transform or crop rule, then the framing that
 * mode needs. Edit params bypass the collection's [CropRule]. The mode is named by the entry point, never
 * resolved here, because a source rendered for the system picker precedes the engine going live.
 */
@Singleton
class WallpaperRenderer @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    private val seedExtractor: SeedExtractor
) {
    private companion object {
        const val TAG = "WallpaperRenderer"
        const val BLUR_DOWNSCALE_FACTOR = 24
        const val EDIT_DECODE_SCALE = 2
    }

    /**
     * The platform's parallax zoom differs by mode. Static pads so the platform's crop eats the padding,
     * or does nothing. Atmosphere has no platform crop: zoom fix off crops in ourselves to match the static
     * framing, and blurred/edge needs nothing (padding would leave permanent bars).
     */
    private enum class Framing { NONE, PAD_BLURRED, PAD_EDGE, CROP_IN }

    private data class TargetSize(
        val width: Int, val height: Int
    )

    private data class BitmapPlacement(
        val scale: Float,
        val xOffset: Float,
        val yOffset: Float
    )

    /** Read per call, so a zoom-fix change applies from the next refill without a service restart. */
    private suspend fun framing(mode: WallpaperMode): Framing {
        val zoomFix = appDataStore.getWallpaperZoomFix()
        if (mode == WallpaperMode.ATMOSPHERE) {
            return if (zoomFix == WallpaperZoomFix.OFF) Framing.CROP_IN else Framing.NONE
        }
        return when (zoomFix) {
            WallpaperZoomFix.OFF -> Framing.NONE
            WallpaperZoomFix.BLURRED -> Framing.PAD_BLURRED
            WallpaperZoomFix.EDGE -> Framing.PAD_EDGE
        }
    }

    /**
     * The caller owns the bitmap and must recycle it. Returns pixels rather than a file because
     * callers need them for `setBitmap`.
     */
    suspend fun renderForStatic(wallpaper: WallpaperImage, cropRule: CropRule): Bitmap? {
        return try {
            renderStatic(wallpaper, cropRule)
        } catch (e: CancellationException) {
            // A canceled render must abort, not be reported as a failed one
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render $wallpaper for static delivery", e)
            null
        }
    }

    /**
     * The caller owns the bitmap and must recycle it. The palette cannot be derived from the returned
     * pixels (see [RenderedAtmosphere]).
     */
    suspend fun renderForAtmosphere(wallpaper: WallpaperImage, cropRule: CropRule): RenderedAtmosphere? {
        return try {
            renderAtmosphere(wallpaper, cropRule)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render $wallpaper for atmosphere delivery", e)
            null
        }
    }

    /** [renderScreenFitted] plus static framing. Failures propagate so callers can classify them. */
    internal suspend fun renderStatic(wallpaper: WallpaperImage, cropRule: CropRule): Bitmap? {
        val screenBitmap = renderScreenFitted(wallpaper, cropRule) ?: return null
        return frameAndRecycle(screenBitmap, framing(WallpaperMode.STATIC))
    }

    /**
     * Seeds come from the screen-fitted photo before framing, so the zoom fix never changes colors; only
     * anchors follow it. Failures propagate so callers can classify them.
     */
    internal suspend fun renderAtmosphere(
        wallpaper: WallpaperImage,
        cropRule: CropRule
    ): RenderedAtmosphere? {
        val screenBitmap = renderScreenFitted(wallpaper, cropRule) ?: return null
        val framing = framing(WallpaperMode.ATMOSPHERE)
        val seeds = seedExtractor.extract(screenBitmap)
        return RenderedAtmosphere(
            bitmap = frameAndRecycle(screenBitmap, framing),
            seeds = anchorsWithin(seeds, framing)
        )
    }

    /**
     * [Framing.CROP_IN] moves every point of the photo, so positions follow it though colors do not.
     * The scan reads a narrower window than it keeps, so a mapped anchor always lands inside.
     */
    private fun anchorsWithin(seeds: List<VertexInfo>, framing: Framing): List<VertexInfo> = when (framing) {
        Framing.NONE -> seeds
        Framing.CROP_IN -> seeds.map { seed ->
            seed.copy(
                x = ParallaxZoom.photoToDelivered(seed.x, seed.bitmapWidth),
                y = ParallaxZoom.photoToDelivered(seed.y, seed.bitmapHeight)
            )
        }
        // Not reachable for an atmosphere render
        Framing.PAD_BLURRED, Framing.PAD_EDGE -> seeds
    }

    /**
     * Decode and edit transform or crop rule, stopping before framing (the palette is defined over the
     * photo at this stage). The returned bitmap is the caller's. Null only when the source could not be
     * decoded; other failures propagate so callers can classify them as definitive or transient.
     */
    private suspend fun renderScreenFitted(
        wallpaper: WallpaperImage,
        cropRule: CropRule
    ): Bitmap? = withContext(Dispatchers.IO) {
        val targetSize = getTargetSize()
        val editParams = wallpaper.editParams

        val sourceBitmap = decodeSourceBitmap(
            wallpaper.uriString.toUri(),
            targetSize,
            oversample = if (editParams != null) EDIT_DECODE_SCALE else 1
        ) ?: return@withContext null

        try {
            if (editParams != null) {
                applyEditTransform(
                    source = sourceBitmap,
                    targetSize = targetSize,
                    zoom = editParams.zoom,
                    normalizedOffsetX = editParams.offsetX,
                    normalizedOffsetY = editParams.offsetY
                )
            } else {
                renderScreenBitmap(sourceBitmap, targetSize, cropRule)
            }
        } finally {
            sourceBitmap.recycle()
        }
    }

    private fun frameAndRecycle(screenBitmap: Bitmap, framing: Framing): Bitmap {
        val framed = applyFraming(screenBitmap, framing)
        if (framed !== screenBitmap) screenBitmap.recycle()
        return framed
    }

    private fun getTargetSize(): TargetSize {
        val (width, height) = Bitmaps.getWallpaperCanvasSize(appContext)
        return TargetSize(width, height)
    }

    private fun decodeSourceBitmap(sourceUri: Uri, targetSize: TargetSize, oversample: Int = 1): Bitmap? {
        val reqW = targetSize.width * oversample
        val reqH = targetSize.height * oversample
        return Bitmaps.decodeSampledBitmap(appContext, sourceUri, reqW, reqH)
    }

    /** Padded results are deliberately larger than the screen so the platform's crop has something to eat. */
    private fun applyFraming(bitmap: Bitmap, framing: Framing): Bitmap = when (framing) {
        Framing.NONE -> bitmap
        Framing.PAD_BLURRED -> addZoomFixPadding(bitmap, WallpaperZoomFix.BLURRED)
        Framing.PAD_EDGE -> addZoomFixPadding(bitmap, WallpaperZoomFix.EDGE)
        Framing.CROP_IN -> cropToZoomInset(bitmap)
    }

    /** Reproduces the platform's parallax crop, which live-wallpaper surfaces never get: the center region scaled back to fill the screen. */
    private fun cropToZoomInset(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val insetX = ParallaxZoom.cropInset(width)
        val insetY = ParallaxZoom.cropInset(height)
        if (insetX <= 0 && insetY <= 0) return source

        val cropped = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(cropped).drawBitmap(
            source,
            Rect(insetX, insetY, width - insetX, height - insetY),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Bitmaps.createRenderPaint()
        )
        return cropped
    }

    /** Geometry comes from [computeEditTransform], so the result matches what the editor showed. */
    private fun applyEditTransform(
        source: Bitmap,
        targetSize: TargetSize,
        zoom: Float,
        normalizedOffsetX: Float,
        normalizedOffsetY: Float
    ): Bitmap {
        val transform = computeEditTransform(
            contentWidth = source.width.toFloat(),
            contentHeight = source.height.toFloat(),
            containerWidth = targetSize.width.toFloat(),
            containerHeight = targetSize.height.toFloat(),
            zoom = zoom,
            offsetX = normalizedOffsetX,
            offsetY = normalizedOffsetY
        )

        val output = createBitmap(targetSize.width, targetSize.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)

        val matrix = Matrix().apply {
            postScale(transform.scale, transform.scale)
            postTranslate(transform.drawX, transform.drawY)
        }
        canvas.drawBitmap(source, matrix, Bitmaps.createRenderPaint())

        return output
    }

    private fun renderScreenBitmap(source: Bitmap, targetSize: TargetSize, rule: CropRule): Bitmap {
        val placement = calculateBitmapPlacement(source.width, source.height, targetSize, rule)

        return Bitmaps.renderScaledBitmap(
            source,
            targetSize.width,
            targetSize.height,
            placement.scale,
            placement.xOffset,
            placement.yOffset
        )
    }

    private fun calculateBitmapPlacement(
        sourceWidth: Int,
        sourceHeight: Int,
        targetSize: TargetSize,
        rule: CropRule
    ): BitmapPlacement {
        val targetWidth = targetSize.width.toFloat()
        val targetHeight = targetSize.height.toFloat()
        val scale = CropPlacement.scale(rule, sourceWidth.toFloat(), sourceHeight.toFloat(), targetWidth, targetHeight)
        val xOffset = CropPlacement.offset(targetWidth, sourceWidth * scale, CropPlacement.horizontalBias(rule))
        val yOffset = CropPlacement.offset(targetHeight, sourceHeight * scale, 0f)

        return BitmapPlacement(scale, xOffset, yOffset)
    }

    private fun addZoomFixPadding(screenBitmap: Bitmap, zoomFix: WallpaperZoomFix): Bitmap {
        val targetW = screenBitmap.width
        val targetH = screenBitmap.height

        // Full resolution plus padding for the platform's presentation zoom to consume
        val insetX = ParallaxZoom.padInset(targetW)
        val insetY = ParallaxZoom.padInset(targetH)
        val paddedW = targetW + insetX * 2
        val paddedH = targetH + insetY * 2

        Log.d(TAG, "Adding zoom-fix padding: $paddedW x $paddedH with insets $insetX x $insetY")

        val padded = createBitmap(paddedW, paddedH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        val paint = Bitmaps.createRenderPaint()
        var success = false

        try {
            drawPaddingBackground(screenBitmap, canvas, paddedW, paddedH, insetX, insetY, paint, zoomFix)
            canvas.drawBitmap(screenBitmap, insetX.toFloat(), insetY.toFloat(), paint)
            success = true
            return padded
        } finally {
            if (!success) padded.recycle()
        }
    }

    private fun drawPaddingBackground(
        source: Bitmap,
        canvas: Canvas,
        paddedW: Int,
        paddedH: Int,
        insetX: Int,
        insetY: Int,
        paint: Paint,
        zoomFix: WallpaperZoomFix
    ) {
        when (zoomFix) {
            WallpaperZoomFix.BLURRED -> drawBlurredPadding(source, canvas, paddedW, paddedH, paint)
            WallpaperZoomFix.EDGE -> drawEdgePadding(source, canvas, insetX, insetY, paint)
            WallpaperZoomFix.OFF -> Unit
        }
    }

    private fun drawBlurredPadding(
        source: Bitmap,
        canvas: Canvas,
        paddedW: Int,
        paddedH: Int,
        paint: Paint
    ) {
        val blurW = (paddedW / BLUR_DOWNSCALE_FACTOR).coerceAtLeast(1)
        val blurH = (paddedH / BLUR_DOWNSCALE_FACTOR).coerceAtLeast(1)
        val blurSize = TargetSize(blurW, blurH)
        val blurred = createBitmap(blurW, blurH, Bitmap.Config.ARGB_8888)
        val blurCanvas = Canvas(blurred)
        val placement = calculateBitmapPlacement(source.width, source.height, blurSize, CropRule.CENTER)

        try {
            val matrix = Matrix().apply {
                postScale(placement.scale, placement.scale)
                postTranslate(placement.xOffset, placement.yOffset)
            }

            blurCanvas.drawBitmap(source, matrix, paint)
            canvas.drawBitmap(
                blurred,
                Rect(0, 0, blurW, blurH),
                RectF(0f, 0f, paddedW.toFloat(), paddedH.toFloat()),
                paint
            )
        } finally {
            blurred.recycle()
        }
    }

    private fun drawEdgePadding(
        source: Bitmap,
        canvas: Canvas,
        insetX: Int,
        insetY: Int,
        paint: Paint
    ) {
        val width = source.width
        val height = source.height
        val paddedW = width + insetX * 2
        val paddedH = height + insetY * 2

        if (insetY > 0) {
            canvas.drawBitmap(
                source,
                Rect(0, 0, width, 1),
                RectF(0f, 0f, paddedW.toFloat(), insetY.toFloat()),
                paint
            )
            canvas.drawBitmap(
                source,
                Rect(0, height - 1, width, height),
                RectF(0f, (paddedH - insetY).toFloat(), paddedW.toFloat(), paddedH.toFloat()),
                paint
            )
        }

        if (insetX > 0) {
            canvas.drawBitmap(
                source,
                Rect(0, 0, 1, height),
                RectF(0f, insetY.toFloat(), insetX.toFloat(), (insetY + height).toFloat()),
                paint
            )
            canvas.drawBitmap(
                source,
                Rect(width - 1, 0, width, height),
                RectF((insetX + width).toFloat(), insetY.toFloat(), paddedW.toFloat(), (insetY + height).toFloat()),
                paint
            )
        }
    }
}
