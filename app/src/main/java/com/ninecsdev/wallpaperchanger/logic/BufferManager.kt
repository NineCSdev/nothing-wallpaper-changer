package com.ninecsdev.wallpaperchanger.logic

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
import kotlinx.coroutines.CancellationException
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecordStore
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereRender
import com.ninecsdev.wallpaperchanger.logic.atmosphere.SeedExtractor
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereSource
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.VertexInfo
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class BufferPreparationResult {
    object Success : BufferPreparationResult()

    /**
     * [definitive]: the source is provably gone (deleted, permission revoked), so the file is marked
     * unavailable; otherwise the image is just skipped this rotation.
     */
    data class Failure(val definitive: Boolean) : BufferPreparationResult()
}

/**
 * Renders for a destination mode and owns what is prepared for the next rotation. Only one shape
 * exists at a time (a WebP buffer for static, a staged container with seeds for atmosphere), so
 * which file exists records which mode prepared it. Edit params bypass the collection's [CropRule].
 */
@Singleton
class BufferManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    private val wallpaperRecordStore: WallpaperRecordStore,
    private val modeResolver: WallpaperModeResolver,
    private val seedExtractor: SeedExtractor
) {
    private companion object {
        const val TAG = "BufferManager"
        const val BUFFER_FILENAME = "buffer_next.webp"
        const val TEMP_FILENAME = "buffer_temp.webp"
        const val COMPRESSION_QUALITY = 95 // Quality left high as only 1 image will exist at any time
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

    private fun getBufferFile(): File = File(appContext.cacheDir, BUFFER_FILENAME)

    /** Files a cache clear must leave alone, finished and in-flight. */
    fun bufferFiles(): Set<File> = setOf(getBufferFile(), File(appContext.cacheDir, TEMP_FILENAME))

    /** Null when nothing usable is prepared. */
    suspend fun preparedCollectionId(): Long? = wallpaperRecordStore.snapshot().bufferedCollectionId

    /** The artifact may stay on disk but belongs to no rotation. */
    suspend fun forgetPrepared() = wallpaperRecordStore.setBufferedWallpaper(null, null)

    /**
     * Null when nothing is prepared; the caller owns the stream. Falls back to the staged atmosphere
     * delivery (engine framing) if the engine stopped being the system wallpaper; the next refill fixes the shape.
     */
    suspend fun openPrepared(): InputStream? = withContext(Dispatchers.IO) {
        val buffer = getBufferFile()
        if (buffer.exists()) return@withContext buffer.inputStream()

        val staged = AtmosphereSource.readPending(appContext.filesDir)
        if (staged == null) {
            Log.w(TAG, "Nothing prepared to apply. Is the service initialized?")
            return@withContext null
        }

        Log.w(TAG, "No static buffer; applying the staged atmosphere delivery, framed for the engine.")
        ByteArrayInputStream(staged.imageBytes)
    }

    /** Prepares the next wallpaper for whichever mode is currently effective. */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md (zoom-fix gating by effective mode)
    suspend fun prepareNextWallpaper(wallpaper: WallpaperImage, cropRule: CropRule): BufferPreparationResult {
        return try {
            when (modeResolver.effectiveMode()) {
                WallpaperMode.ATMOSPHERE -> stageAtmosphereDelivery(wallpaper, cropRule)
                WallpaperMode.STATIC -> writeStaticBuffer(wallpaper, cropRule)
            }
        } catch (e: CancellationException) {
            // Not a preparation failure: the caller is being torn down
            throw e
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Source unreadable, likely deleted: ${wallpaper.uriString}", e)
            BufferPreparationResult.Failure(definitive = true)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission revoked for source: ${wallpaper.uriString}", e)
            BufferPreparationResult.Failure(definitive = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare the next wallpaper", e)
            BufferPreparationResult.Failure(definitive = false)
        }
    }

    private suspend fun stageAtmosphereDelivery(
        wallpaper: WallpaperImage,
        cropRule: CropRule
    ): BufferPreparationResult {
        val render = renderAtmosphere(wallpaper, cropRule) ?: return BufferPreparationResult.Failure(definitive = false)
        try {
            val imageBytes = ImageProcessingUtils.compressToBytes(render.bitmap, quality = COMPRESSION_QUALITY)
            // Cleared before writing, never after: a crash between must leave nothing prepared
            // (handled) rather than the previous mode's image
            discardStaticBuffer()
            if (!AtmosphereSource.writePending(appContext.filesDir, render.seeds, imageBytes)) {
                return BufferPreparationResult.Failure(definitive = false)
            }
            wallpaperRecordStore.setBufferedWallpaper(wallpaper.id, wallpaper.collectionId)
            Log.d(TAG, "Staged atmosphere delivery: ${imageBytes.size / 1024} KB | Rule: $cropRule")
        } finally {
            render.bitmap.recycle()
        }
        return BufferPreparationResult.Success
    }

    private suspend fun writeStaticBuffer(
        wallpaper: WallpaperImage,
        cropRule: CropRule
    ): BufferPreparationResult {
        val rendered = renderStatic(wallpaper, cropRule) ?: return BufferPreparationResult.Failure(definitive = false)
        try {
            AtmosphereSource.clearPending(appContext.filesDir)
            writeBuffer(rendered, cropRule)
            wallpaperRecordStore.setBufferedWallpaper(wallpaper.id, wallpaper.collectionId)
        } finally {
            rendered.recycle()
        }
        return BufferPreparationResult.Success
    }

    private fun discardStaticBuffer() {
        val buffer = getBufferFile()
        if (buffer.exists() && !buffer.delete()) {
            Log.w(TAG, "Could not delete the static buffer")
        }
    }

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
     * pixels (see [AtmosphereRender]).
     */
    suspend fun renderForAtmosphere(wallpaper: WallpaperImage, cropRule: CropRule): AtmosphereRender? {
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
    private suspend fun renderStatic(wallpaper: WallpaperImage, cropRule: CropRule): Bitmap? {
        val screenBitmap = renderScreenFitted(wallpaper, cropRule) ?: return null
        return frameAndRecycle(screenBitmap, framing(WallpaperMode.STATIC))
    }

    /** Seeds come from the screen-fitted photo before framing, so the zoom fix never changes colors; only anchors follow it. */
    private suspend fun renderAtmosphere(
        wallpaper: WallpaperImage,
        cropRule: CropRule
    ): AtmosphereRender? {
        val screenBitmap = renderScreenFitted(wallpaper, cropRule) ?: return null
        val framing = framing(WallpaperMode.ATMOSPHERE)
        val seeds = seedExtractor.extract(screenBitmap)
        return AtmosphereRender(
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
        val (width, height) = ImageProcessingUtils.getWallpaperCanvasSize(appContext)
        return TargetSize(width, height)
    }

    private fun decodeSourceBitmap(sourceUri: Uri, targetSize: TargetSize, oversample: Int = 1): Bitmap? {
        val reqW = targetSize.width * oversample
        val reqH = targetSize.height * oversample
        return ImageProcessingUtils.decodeSampledBitmap(appContext, sourceUri, reqW, reqH)
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
            ImageProcessingUtils.createRenderPaint()
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
        canvas.drawBitmap(source, matrix, ImageProcessingUtils.createRenderPaint())

        return output
    }

    private fun renderScreenBitmap(source: Bitmap, targetSize: TargetSize, rule: CropRule): Bitmap {
        val placement = calculateBitmapPlacement(source.width, source.height, targetSize, rule)

        return ImageProcessingUtils.renderScaledBitmap(
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

    /** Hops to IO itself: two of the three refill callers reach here from a main-thread scope. */
    private suspend fun writeBuffer(bitmap: Bitmap, cropRule: CropRule) = withContext(Dispatchers.IO) {
        val bufferFile = getBufferFile()
        // Written aside and swapped in: the rotation consumer may open this file at any moment.
        writeAtomically(File(appContext.cacheDir, TEMP_FILENAME), bufferFile) { temp ->
            ImageProcessingUtils.compressToFile(bitmap, temp, quality = COMPRESSION_QUALITY)
        }
        Log.d(TAG, "Buffer ready: ${bufferFile.length() / 1024} KB | Rule: $cropRule")
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
        val paint = ImageProcessingUtils.createRenderPaint()
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
