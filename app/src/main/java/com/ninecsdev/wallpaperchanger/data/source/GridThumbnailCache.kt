package com.ninecsdev.wallpaperchanger.data.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.ninecsdev.wallpaperchanger.logic.ImageProcessingUtils
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Small on-disk copies of registry files for grid tiles; never authoritative. One folder per file row.
 * A name carries the uri, pixel size and the source's size and modified time, so an image edited in
 * place, or a row relinked to another uri, never serves the old picture.
 */
@Singleton
class GridThumbnailCache @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val wallpaperSources: WallpaperSources
) {
    private companion object {
        const val TAG = "GridThumbnailCache"
        const val FOLDER = "grid_thumbnails"
        const val QUALITY = 85
        const val TEMP_SUFFIX = ".tmp"
        /** Marks a thumbnail kept at its source's full size, which no box can outgrow. */
        const val FULL_SIZE_MARK = "+"
        val SIZE_SEGMENT = Regex("""^[0-9a-f]+_(\d+)x(\d+)(\+?)_""")
    }

    private val root by lazy { File(appContext.cacheDir, FOLDER) }

    /**
     * When the source cannot be read or says nothing about itself, the thumbnail already held is returned
     * as is, so an unavailable file keeps its last known picture. Null only with no readable source and no stored thumbnail.
     */
    suspend fun fileFor(fileId: Long, uri: String, sourceType: SourceType, box: ThumbnailBox): File? =
        withContext(Dispatchers.IO) {
            val folder = File(root, fileId.toString())
            val uriKey = uri.hashCode().toUInt().toString(16)
            val stamp = stampOf(wallpaperSources.fingerprint(uri, sourceType))
            val heldForUri = finished(folder).filter { it.name.startsWith("${uriKey}_") }
            if (stamp != null) {
                heldForUri
                    .firstOrNull { it.name.endsWith("_$stamp") && covers(it, box) }
                    ?.let { return@withContext it }
            }

            val held = heldForUri.maxByOrNull { it.lastModified() }
            // No stamp is also how an unreachable source answers
            if (stamp == null && held != null) return@withContext held
            create(uri, box, folder) { width, height, isFullSize ->
                "${uriKey}_${width}x$height${if (isFullSize) FULL_SIZE_MARK else ""}_${stamp ?: "unknown"}"
            } ?: held
        }

    suspend fun delete(fileIds: Collection<Long>) {
        if (fileIds.isEmpty()) return
        withContext(Dispatchers.IO) { fileIds.forEach { File(root, it.toString()).deleteRecursively() } }
    }

    suspend fun retainOnly(fileIds: Set<Long>) = withContext(Dispatchers.IO) {
        root.listFiles()
            ?.filter { it.name.toLongOrNull() !in fileIds }
            ?.forEach { it.deleteRecursively() }
    }

    private fun create(
        uri: String,
        box: ThumbnailBox,
        folder: File,
        nameFor: (width: Int, height: Int, isFullSize: Boolean) -> String
    ): File? {
        val (bitmap, isFullSize) = decode(uri, box) ?: return null
        return try {
            val target = File(folder, nameFor(bitmap.width, bitmap.height, isFullSize))
            folder.mkdirs()
            val temp = File.createTempFile("thumb", TEMP_SUFFIX, folder)
            try {
                // JPEG decodes faster than WebP; WebP only to keep transparency
                val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
                ImageProcessingUtils.compressToFile(bitmap, temp, format, QUALITY)
                if (!temp.renameTo(target)) return null
                // Only once the new one is in place, so a failed write leaves the old one to fall back on
                finished(folder).filter { it != target }.forEach { it.delete() }
                target
            } finally {
                temp.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the thumbnail of $uri", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** The bitmap plus whether it is the source's full size; null if unreadable. */
    private fun decode(uri: String, box: ThumbnailBox): Pair<Bitmap, Boolean>? = try {
        var sampleSize = 1
        val sampled = ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, uri.toUri())) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (w, h) = info.size.width to info.size.height
            // Both orientations, so a source the decoder rotates is never sampled below what the box needs
            val needed = maxOf(box.shortSideFor(w, h), box.shortSideFor(h, w))
            sampleSize = Integer.highestOneBit(minOf(w, h) / needed).coerceAtLeast(1)
            decoder.setTargetSampleSize(sampleSize)
        }
        val scale = box.scaleFor(sampled.width, sampled.height)

        if (scale >= 1f) {
            sampled to (sampleSize == 1)
        } else {
            val scaled = sampled.scale(
                (sampled.width * scale).roundToInt().coerceAtLeast(1),
                (sampled.height * scale).roundToInt().coerceAtLeast(1)
            )
            if (scaled !== sampled) sampled.recycle()
            scaled to false
        }
    } catch (e: IOException) { // DecodeException included
        Log.w(TAG, "Could not decode $uri", e)
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "No longer allowed to read $uri", e)
        null
    }

    /** Skips temp files, which may be another request's write-in flight. */
    private fun finished(folder: File): List<File> = folder.listFiles()?.filter { !it.name.endsWith(TEMP_SUFFIX) }.orEmpty()

    /** Reads the size back from the `uriKey_WxH[+]_stamp` name. */
    private fun covers(file: File, box: ThumbnailBox): Boolean {
        val match = SIZE_SEGMENT.find(file.name) ?: return false
        if (match.groupValues[3] == FULL_SIZE_MARK) return true
        val (width, height) = match.groupValues[1].toInt() to match.groupValues[2].toInt()
        val shortSide = minOf(width, height)
        // A pixel of slack for the rounding the thumbnail was made with
        return shortSide * box.scaleFor(width, height) <= shortSide + 1
    }

    private fun stampOf(fingerprint: SourceFingerprint): String? {
        if (fingerprint.sizeBytes == null && fingerprint.modifiedAt == null) return null
        return "${fingerprint.sizeBytes ?: "x"}-${fingerprint.modifiedAt ?: "x"}"
    }
}

/** Cropped to cover the box when [fill], fitted inside it otherwise. */
data class ThumbnailBox(val width: Int, val height: Int, val fill: Boolean) {

    fun scaleFor(w: Int, h: Int): Float {
        val sx = width.toFloat() / w
        val sy = height.toFloat() / h
        return if (fill) maxOf(sx, sy) else minOf(sx, sy)
    }

    fun shortSideFor(w: Int, h: Int): Int =
        (minOf(w, h) * scaleFor(w, h).coerceAtMost(1f)).roundToInt().coerceAtLeast(1)
}
