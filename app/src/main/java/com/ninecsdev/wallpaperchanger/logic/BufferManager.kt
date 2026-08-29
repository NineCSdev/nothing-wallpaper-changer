package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
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
     * @param definitive true when the failure proves the source is gone (file deleted, permission
     * revoked) — [RotationEngine][com.ninecsdev.wallpaperchanger.logic.RotationEngine] marks the file
     * unavailable rather than retrying it. false for any other failure (decode error, transient IO) —
     * the image is simply skipped for this rotation and retried later, no side effects.
     */
    data class Failure(val definitive: Boolean) : BufferPreparationResult()
}

/**
 * The render pipeline, and the owner of whatever it has prepared for the next rotation.
 * Handles downsampling, aspect-ratio cropping, framing, and WebP compression.
 *
 * When a wallpaper has edit params (zoom/offsetX/offsetY), the collection's [CropRule] is
 * bypassed entirely.
 *
 * **Rendering is asked for by delivery mode** The two modes need opposite framing (see [Framing])
 * and the palette an atmosphere delivery carries is only derivable here, so callers name the destination
 * and get back something already correct for it.
 *
 * **What is prepared is mode-shaped, and only one shape exists at a time.** A static refill leaves a
 * WebP buffer; an atmosphere refill leaves a staged container holding the same pixels plus their
 * seeds. Which file exists *is* the record of which mode prepared it.
 */
@Singleton
class BufferManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    private val modeResolver: WallpaperModeResolver,
    private val seedExtractor: SeedExtractor
) {
    private companion object {
        const val TAG = "BufferManager"
        const val BUFFER_FILENAME = "buffer_next.webp"
        const val TEMP_FILENAME = "buffer_temp.webp"
        const val COMPRESSION_QUALITY = 95 // Quality left high as only 1 image will exist at any time
        const val ZOOM_INSET_FRACTION = 0.045f // Zoom that I observed in a 20:9 screen
        const val BLUR_DOWNSCALE_FACTOR = 24
        const val EDIT_DECODE_SCALE = 2
    }

    /**
     * What to do about the platform's parallax zoom, which is **not** the same question in the two
     * delivery modes.
     *
     * - static, zoom fix off: the platform crops ~4.5%, and we do nothing.
     * - static, blurred/edge: we pad ~4.5% and the platform's crop eats exactly the padding.
     * - **atmosphere, zoom fix off**: nothing will crop for us, so we [CROP_IN] ourselves to land on
     *   the same framing the static path gets for free.
     * - **atmosphere, blurred/edge**: the user's whole image already survives, and padding it would
     *   only put permanent bars on screen with nothing to eat them so [NONE].
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

    /**
     * Opens the prepared image for a static apply, or null when nothing is prepared. Caller
     * owns the stream.
     *
     * Normally that is the WebP buffer. It falls back to the staged atmosphere delivery so if the engine
     * stopped being the system wallpaper the mode resolves static and the only prepared pixels engine's.
     * They carry engine's framing but the rotation happens, and the next refill prepares the right shape.
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

    /**
     * Prepares the next wallpaper for whichever mode is currently effective, leaving one
     * prepared artifact behind.
     *
     * If the wallpaper has edit params, the edit transform (fit + zoom + offset) is applied
     * and the [cropRule] is **bypassed**.
     * Otherwise, the standard [cropRule] pipeline is used.
     */
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
            Log.w(TAG, "Source unreadable, likely deleted: ${wallpaper.uri}", e)
            BufferPreparationResult.Failure(definitive = true)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission revoked for source: ${wallpaper.uri}", e)
            BufferPreparationResult.Failure(definitive = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare the next wallpaper", e)
            BufferPreparationResult.Failure(definitive = false)
        }
    }

    /**
     * Stages the whole delivery (pixels and palette in one container) so publishing later
     * is a copy and a broadcast.
     */
    private suspend fun stageAtmosphereDelivery(
        wallpaper: WallpaperImage,
        cropRule: CropRule
    ): BufferPreparationResult {
        val render = renderAtmosphere(wallpaper, cropRule) ?: return BufferPreparationResult.Failure(definitive = false)
        try {
            val imageBytes = ImageProcessingUtils.compressToBytes(render.bitmap, quality = COMPRESSION_QUALITY)
            // Cleared before the new one is written, never after. A crash between the two must leave
            // nothing prepared (recoverable, and handled) rather than the previous mode's image
            discardStaticBuffer()
            if (!AtmosphereSource.writePending(appContext.filesDir, render.seeds, imageBytes)) {
                return BufferPreparationResult.Failure(definitive = false)
            }
            appDataStore.setBufferedWallpaperId(wallpaper.id)
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
            appDataStore.setBufferedWallpaperId(wallpaper.id)
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

    /**
     * The user's zoom-fix setting resolved against [mode], the mode that will actually deliver
     * the image. See [Framing] for why the two modes need opposite treatment.
     *
     * The zoom-fix setting is read per call, so changing it between two refills takes effect on the
     * next one without a service restart.
     */
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
     * Renders [wallpaper] framed for static delivery and hands the bitmap back; the **caller owns it
     * and must recycle it**.
     *
     * It returns a bitmap rather than writing a file because callers need the pixels for
     * `setBitmap`. The bytes staged for the engine are never reusable here.
     */
    suspend fun renderForStatic(wallpaper: WallpaperImage, cropRule: CropRule): Bitmap? {
        return try {
            renderStatic(wallpaper, cropRule)
        } catch (e: CancellationException) {
            // Rethrown rather than reported as a failed render. A canceled render must abort, not fall through.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render $wallpaper for static delivery", e)
            null
        }
    }

    /**
     * Renders [wallpaper] framed for the atmosphere engine, together with the palette the engine
     * needs. The **caller owns the bitmap and must recycle it**.
     *
     * The palette cannot be derived from the returned pixels (see [AtmosphereRender]).
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

    /**
     * [renderScreenFitted], then the palette, then framing.
     *
     * The palette is quantized from the screen-fitted photo, before framing, so the same photo
     * yields the same colors independently of the zoom fix. Framing runs afterward and only the
     * anchors follow it (see [anchorsWithin]).
     */
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
     * Re-expresses seed anchors from the photo's frame into the delivered bitmap's.
     *
     * A seed is two things with different owners: a **color**, which belongs to the photo and must
     * not move when a presentation setting changes, and a **position**, which is a coordinate into
     * the image the engine actually draws. [Framing.CROP_IN] moves every point of the photo, so the
     * positions follow it even though the colors do not.
     *
     * The scan reads a narrower window than [Framing.CROP_IN] keeps, so a mapped anchor always lands
     * inside the delivered image; the clamp in [mapAnchor] is a guard, not a working part.
     */
    private fun anchorsWithin(seeds: List<VertexInfo>, framing: Framing): List<VertexInfo> = when (framing) {
        Framing.NONE -> seeds
        Framing.CROP_IN -> seeds.map { seed ->
            seed.copy(
                x = mapAnchor(seed.x, seed.bitmapWidth),
                y = mapAnchor(seed.y, seed.bitmapHeight)
            )
        }
        // Not reachable for an atmosphere render
        Framing.PAD_BLURRED, Framing.PAD_EDGE -> seeds
    }

    private fun mapAnchor(value: Int, extent: Int): Int {
        if (extent <= 1) return 0
        val kept = 1f - 2f * ZOOM_INSET_FRACTION
        val withinDelivered = (value.toFloat() / extent - ZOOM_INSET_FRACTION) / kept
        return (withinDelivered * extent).roundToInt().coerceIn(0, extent - 1)
    }

    /**
     * The single decode → transform pipeline behind every render. Decodes [wallpaper] at the
     * screen's target size (oversampling when it carries edit params) and applies the edit transform
     * or [cropRule], stopping **before** framing. The intermediate source bitmap is always recycled;
     * **the returned bitmap is the caller's.**
     *
     * Framing is deliberately not part of this as the palette is defined over the photo at this stage.
     *
     * Returns null only when the source could not be decoded; every other failure propagates as an
     * exception so callers can classify it (definitive vs. transient).
     */
    private suspend fun renderScreenFitted(
        wallpaper: WallpaperImage,
        cropRule: CropRule
    ): Bitmap? = withContext(Dispatchers.IO) {
        val targetSize = getTargetSize()
        val editParams = wallpaper.editParams

        val sourceBitmap = decodeSourceBitmap(
            wallpaper.uri,
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

    /** [applyFraming], recycling the input when framing produced a new bitmap. */
    private fun frameAndRecycle(screenBitmap: Bitmap, framing: Framing): Bitmap {
        val framed = applyFraming(screenBitmap, framing)
        if (framed !== screenBitmap) screenBitmap.recycle()
        return framed
    }

    private fun getTargetSize(): TargetSize {
        val (width, height) = ImageProcessingUtils.getScreenDimensions(appContext)
        return TargetSize(width, height)
    }

    private fun decodeSourceBitmap(sourceUri: Uri, targetSize: TargetSize, oversample: Int = 1): Bitmap? {
        val reqW = targetSize.width * oversample
        val reqH = targetSize.height * oversample
        return if (ImageInternalizer.isInternalUri(sourceUri)) {
            appContext.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } else {
            ImageProcessingUtils.decodeSampledBitmap(
                appContext,
                sourceUri,
                reqW,
                reqH
            )
        }
    }

    /**
     * Applies [framing] to a screen-sized [bitmap], returning it unchanged when there is nothing to
     * do. The result is always screen-sized except for the padded cases, which are deliberately
     * larger so the platform's crop has something to eat.
     */
    private fun applyFraming(bitmap: Bitmap, framing: Framing): Bitmap = when (framing) {
        Framing.NONE -> bitmap
        Framing.PAD_BLURRED -> addZoomFixPadding(bitmap, WallpaperZoomFix.BLURRED)
        Framing.PAD_EDGE -> addZoomFixPadding(bitmap, WallpaperZoomFix.EDGE)
        Framing.CROP_IN -> cropToZoomInset(bitmap)
    }

    /**
     * Reproduces the platform's parallax crop: takes the center region an inset in from each edge
     * and scales it back out to fill the screen.
     *
     * The live-wallpaper surface never gets this treatment from the system, so with the zoom fix off
     * the atmosphere path applies it here instead to show the same behavior on static and atmosphere.
     */
    private fun cropToZoomInset(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val insetX = calculateZoomInset(width)
        val insetY = calculateZoomInset(height)
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

    /**
     * Applies the user's edit params to produce a screen-sized bitmap.
     *
     * The geometry comes from [computeEditTransform] so the wallpaper
     * matches exactly what the user saw in the editor.
     *
     * @param source Decoded source bitmap (ideally at 2× screen resolution for quality headroom).
     * @param zoom User zoom factor where 1.0 = fit inside the screen.
     * @param normalizedOffsetX Normalized X offset in -1..1.
     * @param normalizedOffsetY Normalized Y offset in -1..1.
     */
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
        val scale = when (rule) {
            CropRule.FIT -> minOf(
                targetSize.width.toFloat() / sourceWidth,
                targetSize.height.toFloat() / sourceHeight
            )
            CropRule.CENTER,
            CropRule.LEFT,
            CropRule.RIGHT -> maxOf(
                targetSize.width.toFloat() / sourceWidth,
                targetSize.height.toFloat() / sourceHeight
            )
        }
        val scaledWidth = sourceWidth * scale
        val scaledHeight = sourceHeight * scale
        val xOffset = when (rule) {
            CropRule.LEFT -> 0f
            CropRule.RIGHT -> targetSize.width - scaledWidth
            CropRule.CENTER,
            CropRule.FIT -> (targetSize.width - scaledWidth) / 2f
        }
        val yOffset = (targetSize.height - scaledHeight) / 2f

        return BitmapPlacement(scale, xOffset, yOffset)
    }

    /**
     * Compression plus the atomic write is the expensive half of a refill, and two of the three
     * refill callers (the atmosphere-confirmed rotation in the foreground service, and the mode
     * switch in settings) reach here from a main-thread scope. So this hops to IO itself rather
     * than inheriting whatever dispatcher the caller happens to be on.
     */
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

        // Nothing OS sometimes zooms the wallpaper presentation after it is set.
        // Keep the wallpaper at full resolution and add tunable padding around it for zoom to consume.
        val insetX = calculateZoomInset(targetW)
        val insetY = calculateZoomInset(targetH)
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

    private fun calculateZoomInset(size: Int, extraPx: Int = 0): Int {
        return ((size * ZOOM_INSET_FRACTION).roundToInt() + extraPx)
            .coerceAtLeast(0)
            .coerceAtMost((size - 1) / 2)
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
