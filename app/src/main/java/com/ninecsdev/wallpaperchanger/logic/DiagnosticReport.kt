package com.ninecsdev.wallpaperchanger.logic

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** A plain-text bug report: the app's state, then its own logcat, saved wherever the user picks. */
@Singleton
class DiagnosticReport @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    private val repository: WallpaperRepository,
    private val wallpaperSources: WallpaperSources,
    private val serviceLifecycle: ServiceLifecycle,
    private val wallpaperModeResolver: WallpaperModeResolver
) {
    companion object {
        private const val TAG = "DiagnosticReport"
        const val MIME_TYPE = "text/plain"

        fun suggestedFileName(): String = "nwc-diagnostics-${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt"
    }

    /** Returns false if the report could not be written, after removing the empty document the picker created. */
    suspend fun write(target: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = appContext.contentResolver.openOutputStream(target) ?: throw IOException("Could not open $target")
            output.buffered().use { out ->
                out.write(header(ZonedDateTime.now()).toByteArray())
                dumpLogcat(out)
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Could not write the diagnostic report", e)
            try {
                DocumentsContract.deleteDocument(appContext.contentResolver, target)
            } catch (cleanup: Exception) {
                Log.w(TAG, "Could not remove the partial report", cleanup)
            }
            false
        }
    }

    private suspend fun header(now: ZonedDateTime): String {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val power = appContext.getSystemService(PowerManager::class.java)
        val activity = appContext.getSystemService(ActivityManager::class.java)
        val active = repository.getActiveCollectionOnce()
        val images = active?.let { repository.getImagesForCollection(it.id).first() }.orEmpty()

        val collection = if (active == null) "none" else {
            val bySource = images.groupingBy { it.sourceType }.eachCount().entries.joinToString { "${it.key} ${it.value}" }
            "${active.type}, ${images.size} images, ${images.count { !it.isAvailable }} unavailable ($bySource)"
        }
        val mediaAccess = when {
            wallpaperSources.hasPartialMediaAccess() -> "partial"
            wallpaperSources.hasMediaAccess() -> "full"
            else -> "none"
        }

        return buildString {
            appendLine("Nothing Wallpaper Changer diagnostics")
            appendLine("Generated: ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
            appendLine("App: ${packageInfo.versionName} (${packageInfo.longVersionCode})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
            appendLine("Service: ${serviceLifecycle.serviceState.value ?: "unresolved"}")
            appendLine("Wallpaper mode: desired ${appDataStore.getWallpaperMode()}, effective ${wallpaperModeResolver.effectiveMode()}")
            appendLine("Destination: ${appDataStore.getWallpaperDestination()}")
            appendLine("Zoom fix: ${appDataStore.getWallpaperZoomFix()}")
            appendLine("Rotation: ${appDataStore.getRotationPolicy()}")
            appendLine("Battery saver policy: ${appDataStore.getBatterySaverPolicy()}, skip on DND: ${appDataStore.getSkipOnDnd()}")
            appendLine("Battery optimization exempt: ${power.isIgnoringBatteryOptimizations(appContext.packageName)}")
            appendLine("Background restricted: ${activity.isBackgroundRestricted}")
            appendLine("Notifications enabled: ${NotificationManagerCompat.from(appContext).areNotificationsEnabled()}")
            appendLine("Media access: $mediaAccess")
            appendLine("Active collection: $collection")
            appendLine()
        }
    }

    // An app may read its own log lines without any permission; the crash buffer holds uncaught exceptions
    private fun dumpLogcat(out: OutputStream) {
        try {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", "main,system,crash")
                .redirectErrorStream(true)
                .start()
            process.inputStream.use { it.copyTo(out) }
            process.waitFor()
        } catch (e: IOException) {
            out.write("Logcat unavailable: $e\n".toByteArray())
        }
    }
}
