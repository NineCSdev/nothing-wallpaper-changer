package com.ninecsdev.wallpaperchanger.logic

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceStorage(
    val totalBytes: Long,
    val freeBytes: Long
)

/**
 * The app's disposable files: everything under `cacheDir` except the prepared wallpaper, which
 * the next screen-off is counting on. Mostly grid thumbnails.
 */
@Singleton
class AppCache @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val bufferManager: BufferManager
) {
    private companion object {
        const val TAG = "AppCache"
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        val ownFiles = clearable().sumOf { file -> file.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        ownFiles + (appContext.imageLoader.diskCache?.size ?: 0L)
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clear() = withContext(Dispatchers.IO) {
        appContext.imageLoader.diskCache?.clear()
        clearable().forEach { it.deleteRecursively() }
    }

    /** The device's size and free space, or null if the platform will not say. */
    suspend fun deviceStorage(): DeviceStorage? = withContext(Dispatchers.IO) {
        try {
            val stats = appContext.getSystemService(StorageStatsManager::class.java)
            // The volume's real size, not just the data partition's, so system files count as used
            val total = stats.getTotalBytes(StorageManager.UUID_DEFAULT)
            // StatFs, because the stats' free figure also counts caches the system could reclaim, ours included
            DeviceStorage(totalBytes = total, freeBytes = StatFs(appContext.filesDir.path).availableBytes)
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the device's storage", e)
            null
        }
    }

    /** Top-level entries of `cacheDir` that are ours to delete */
    @OptIn(ExperimentalCoilApi::class)
    private fun clearable(): List<File> {
        // Coil's disk cache is cleared through Coil, so its journal never points at deleted files
        val coilDir = appContext.imageLoader.diskCache?.directory?.toFile()
        val kept = bufferManager.bufferFiles() + listOfNotNull(coilDir)
        return appContext.cacheDir.listFiles()?.filter { it !in kept }.orEmpty()
    }
}
