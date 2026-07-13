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

/** Disk footprint of the internalized wallpapers ("local copies" in the UI). */
data class StorageUsage(
    val totalBytes: Long,
    val fileCount: Int
)

/**
 * Handles copying external images into the app's private storage.
 * Used for manual collections to not get limited by android persistence file access.
 */
@Singleton
class ImageInternalizer @Inject constructor(
    // Though injecting the datastore here is not the best practice it was done for simplicity
    private val appDataStore: AppDataStore
) {
    companion object {
        private const val TAG = "ImageInternalizer"
        private const val LARGE_FILE_THRESHOLD = 2L * 1024 * 1024 // 2 MB
        const val INTERNAL_FOLDER = "internal_wallpapers"

        /** Skip files younger than this so a scan never races a file mid-registration. */
        private const val ORPHAN_GRACE_PERIOD_MS = 10 * 60 * 1000L // 10 minutes

        /** True if [uri] points inside the app-private internal wallpapers' folder. */
        fun isInternalUri(uri: Uri): Boolean = uri.pathSegments.contains(INTERNAL_FOLDER)
    }

    /**
     * Copies a list of URIs into internal storage as WebP files.
     * Uses inSampleSize subsampling to avoid loading full-res bitmaps and
     * processes all images concurrently.
     * @return List of internal file URIs.
     */
    suspend fun internalizeImages(
        context: Context,
        uris: List<Uri>
    ): List<Uri> {
        return withContext(Dispatchers.IO) {
            val internalDir = File(context.filesDir, INTERNAL_FOLDER)
            if (!internalDir.exists()) internalDir.mkdirs()

            val (screenW, screenH) = ImageProcessingUtils.getScreenDimensions(context)

            // Ideally we would check this using the repository but to avoid circular dependency
            // I used the datastore directly
            val qualityHigh = appDataStore.getCompressionQualityHigh()
            val qualityLow = appDataStore.getCompressionQualityLow()

            // Added a semaphore approach to avoid possible OOM errors thought in my testing the previous
            // one process per uri for pure speed didn't produce problems. Added it because I didn't
            // find a noticeable time increase between this and previous approach probably due to Android/Kotlin
            // guardrails
            // Although on my Phone 1 it takes the same with 4 than 10 keep it high for higher performance phones
            val batchSemaphore = Semaphore(10)

            coroutineScope {
                uris.map { uri ->
                    async {
                        batchSemaphore.withPermit {
                            try {
                                val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                                val quality = if (fileSize > LARGE_FILE_THRESHOLD) qualityLow else qualityHigh
                                internalizeImage(context, uri, internalDir, screenW, screenH, quality)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to access image for internalization: $uri", e)
                                null
                            }
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
     * Sums the current disk usage of [INTERNAL_FOLDER]. Computed from the filesystem on every call.
     */
    // TODO tests: see vault note tests/Storage Usage Tests
    suspend fun getStorageUsage(context: Context): StorageUsage = withContext(Dispatchers.IO) {
        val files = File(context.filesDir, INTERNAL_FOLDER).listFiles()
        StorageUsage(
            totalBytes = files?.sumOf { it.length() } ?: 0L,
            fileCount = files?.size ?: 0
        )
    }

    /**
     * Safely deletes internal wallpaper files. Non-internal files won't be deleted.
     */
    fun deleteInternalFile(path: String?) {
        if (path == null) return
        try {
            val file = File(path)
            // Safety rail: never delete anything that isn't one of our internalized files.
            if (file.parentFile?.name != INTERNAL_FOLDER) {
                Log.w(TAG, "Refusing to delete non-internal file: $path")
                return
            }
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed for: $path", e)
        }
    }

    /**
     * Deletes any file under [INTERNAL_FOLDER] whose name isn't in [keepFileNames], reclaiming
     * files orphaned. Files younger than [ORPHAN_GRACE_PERIOD_MS] are skipped so an in-progress
     * import is never caught mid-write.
     * @return the number of files deleted.
     */
    fun deleteOrphanInternalFiles(context: Context, keepFileNames: Set<String>): Int {
        val internalDir = File(context.filesDir, INTERNAL_FOLDER)
        val files = internalDir.listFiles() ?: return 0
        val graceCutoff = System.currentTimeMillis() - ORPHAN_GRACE_PERIOD_MS

        var deleted = 0
        for (file in files) {
            if (file.name !in keepFileNames && file.lastModified() < graceCutoff) {
                if (file.delete()) deleted++
            }
        }
        if (deleted > 0) Log.d(TAG, "Deleted $deleted orphaned internal wallpaper file(s)")
        return deleted
    }
}
