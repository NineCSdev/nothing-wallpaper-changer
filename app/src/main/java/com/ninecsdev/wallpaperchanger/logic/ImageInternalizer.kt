package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles copying external images into the app's private storage.
 * Used for manual collections to not get limited by android persistence file access.
 */
@Singleton
class ImageInternalizer @Inject constructor(
    // Though injecting the datastore here is not the best practice it was done for simplicity
    private val appDataStore: AppDataStore
) {
    private companion object {
        const val TAG = "ImageInternalizer"
        const val INTERNAL_FOLDER = "internal_wallpapers"
        const val LARGE_FILE_THRESHOLD = 2L * 1024 * 1024
    }

    /**
     * Copies a list of URIs into internal storage as WebP files.
     * Uses inSampleSize subsampling to avoid loading full-res bitmaps and
     * processes all images concurrently.
     * @return List of internal file URIs.
     */
    suspend fun internalizeImages(context: Context, uris: List<Uri>): List<Uri> {
        return withContext(Dispatchers.IO) {
            val internalDir = File(context.filesDir, INTERNAL_FOLDER)
            if (!internalDir.exists()) internalDir.mkdirs()

            val (screenW, screenH) = ImageProcessingUtils.getScreenDimensions(context)

            // Ideally we would check this using the repository but to avoid circular dependency
            // I used the datastore directly
            val qualityHigh = appDataStore.getCompressionQualityHigh()
            val qualityLow = appDataStore.getCompressionQualityLow()

            // Added a semaphore approach to avoid possible OOM errors thought in my testing the previous
            // one process process per uri for pure speed didn't produce problems. Added it because I didn't
            // find a noticeable time increase between this and previous approach probably due to Android/Kotlin
            // guardrails
            val batchSemaphore = Semaphore(10)

            coroutineScope {
                uris.map { uri ->
                    async {
                        batchSemaphore.withPermit {
                            val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                            val quality = if (fileSize > LARGE_FILE_THRESHOLD) qualityLow else qualityHigh
                            internalizeImage(context, uri, internalDir, screenW, screenH, quality)
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }
    }

    private fun internalizeImage(
        context: Context,
        uri: Uri,
        internalDir: File,
        screenW: Int,
        screenH: Int,
        quality: Int
    ): Uri? {
        return try {
            val fileName = "img_${UUID.randomUUID()}.webp"
            val outputFile = File(internalDir, fileName)

            // Decode at reduced resolution via two-pass helper
            val sampled = ImageProcessingUtils.decodeSampledBitmap(context, uri, screenW, screenH)
                ?: return null

            // Fine-resize to exact screen dimensions
            val processedBitmap = resizeToFitScreen(sampled, screenW, screenH)

            ImageProcessingUtils.compressToFile(processedBitmap, outputFile, quality = quality)

            // Clean up
            ImageProcessingUtils.recycleSafely(sampled, processedBitmap)

            Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to internalize image: $uri", e)
            null
        }
    }



    private fun resizeToFitScreen(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val width = src.width
        val height = src.height

        val scale = maxOf(targetW.toFloat() / width, targetH.toFloat() / height)

        if (scale >= 1f) return src

        return src.scale((width * scale).toInt(), (height * scale).toInt())
    }

    /**
     * Safely deletes internal wallpaper files.
     */
    fun deleteInternalFile(path: String?) {
        if (path == null) return
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed for: $path", e)
        }
    }
}
