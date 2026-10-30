package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.core.graphics.scale
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class StorageUsage(
    val totalBytes: Long,
    val fileCount: Int
)

/** Copies external images into private storage to ensure access to them. Only if user chose `Keep local copies` or as fallback for photo references. */
@Singleton
class ImageInternalizer @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    // Though injecting the datastore here is not the best practice it was done for simplicity
    private val appDataStore: AppDataStore
) {
    companion object {
        private const val TAG = "ImageInternalizer"
        // If this changes, update the quality-slider info dialogs in strings.xml, which quote "2 MB".
        private const val LARGE_FILE_THRESHOLD = 2L * 1024 * 1024 // 2 MB
        const val INTERNAL_FOLDER = "internal_wallpapers"

        /** Skip files younger than this so a scan never races a file mid-registration. */
        private const val ORPHAN_GRACE_PERIOD_MS = 10 * 60 * 1000L // 10 minutes

        // Bounds peak bitmap memory; 4 and 10 took the same time on Phone 1
        private const val INTERNALIZE_CONCURRENCY = 10
    }

    suspend fun internalizeImages(uris: List<Uri>): List<Uri> {
        return withContext(Dispatchers.IO) {
            val internalDir = File(appContext.filesDir, INTERNAL_FOLDER)
            if (!internalDir.exists()) internalDir.mkdirs()

            val (screenW, screenH) = ImageProcessingUtils.getWallpaperCanvasSize(appContext)

            // Read from the datastore: going through the repository would be a circular dependency
            val qualityHigh = appDataStore.getCompressionQualityHigh()
            val qualityLow = appDataStore.getCompressionQualityLow()

            uris.mapConcurrently(INTERNALIZE_CONCURRENCY) { uri ->
                try {
                    val fileSize = appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                    val quality = if (fileSize > LARGE_FILE_THRESHOLD) qualityLow else qualityHigh
                    internalizeImage(uri, internalDir, screenW, screenH, quality)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to access image for internalization: $uri", e)
                    null
                }
            }.filterNotNull()
        }
    }

    private fun internalizeImage(
        uri: Uri,
        internalDir: File,
        screenW: Int,
        screenH: Int,
        quality: Int
    ): Uri? {
        return try {
            val fileName = "img_${UUID.randomUUID()}.webp"
            val outputFile = File(internalDir, fileName)

            val sampled = ImageProcessingUtils.decodeSampledBitmap(appContext, uri, screenW, screenH) ?: return null

            val processedBitmap = resizeToFitScreen(sampled, screenW, screenH)

            ImageProcessingUtils.compressToFile(processedBitmap, outputFile, quality = quality)

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

    // TODO tests: see vault note tests/Storage Usage Tests
    suspend fun getStorageUsage(
        excludeUris: List<String> = emptyList()
    ): StorageUsage = withContext(Dispatchers.IO) {
        val excludeFileNames = excludeUris.mapNotNull { it.toUri().lastPathSegment }.toSet()
        val files = File(appContext.filesDir, INTERNAL_FOLDER)
            .listFiles()
            ?.filter { it.name !in excludeFileNames }
        StorageUsage(
            totalBytes = files?.sumOf { it.length() } ?: 0L,
            fileCount = files?.size ?: 0
        )
    }

    fun deleteInternalFile(path: String?) {
        if (path == null) return
        try {
            val file = File(path)
            // Never delete anything that isn't one of our internalized files
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
     * Files younger than [ORPHAN_GRACE_PERIOD_MS] are skipped so an in-progress import is never caught
     * mid-write. A uri that yields no filename aborts the sweep (returns 0).
     */
    fun deleteOrphanInternalFiles(keepUris: List<String>): Int {
        val keepFileNames = keepUris.mapNotNull { it.toUri().lastPathSegment }.toSet()
        if (keepFileNames.size != keepUris.size) {
            Log.w(TAG, "Skipping the orphan sweep: ${keepUris.size - keepFileNames.size} uri(s) yielded no filename")
            return 0
        }

        val internalDir = File(appContext.filesDir, INTERNAL_FOLDER)
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
