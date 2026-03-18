package com.ninecsdev.wallpaperchanger.data.local

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import com.ninecsdev.wallpaperchanger.model.DelayLabel

/**
 * Manages simple key-value pairs for global application settings.
 * Will be overhauled with a Datastore in the future.
 *
 * NOTE: Folder paths and Collection data have been moved to [AppDatabase].
 * System states like Power Save have been moved to [WallpaperRepository].
 */
object AppPreferences {
    private const val PREFS_NAME = "smart_wallpaper_prefs"

    private const val KEY_DEFAULT_WALLPAPER_URI = "default_wallpaper_uri"
    private const val KEY_REVERT_TO_DEFAULT = "revert_to_default_on_stop"
    private const val KEY_SERVICE_RUNNING = "service_running"
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_DELAY_LABEL = "delay_label"
    private const val KEY_SKIP_ON_DND = "skip_on_dnd"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Default Wallpaper Settings
    fun saveDefaultWallpaperUri(context: Context, uri: Uri) {
        getPrefs(context).edit {
            putString(KEY_DEFAULT_WALLPAPER_URI, uri.toString())
        }
    }

    fun getDefaultWallpaperUri(context: Context): Uri? {
        return getPrefs(context).getString(KEY_DEFAULT_WALLPAPER_URI, null)?.toUri()
    }

    fun setRevertToDefault(context: Context, revert: Boolean) {
        getPrefs(context).edit {
            putBoolean(KEY_REVERT_TO_DEFAULT, revert)
        }
    }

    fun shouldRevertToDefault(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REVERT_TO_DEFAULT, true)
    }

    // Service State
    // Note: This is a "Soft State" used for UI synchronization.
    fun setServiceRunning(context: Context, isRunning: Boolean) {
        getPrefs(context).edit {
            putBoolean(KEY_SERVICE_RUNNING, isRunning)
        }
    }

    fun isServiceRunning(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SERVICE_RUNNING, false)
    }

    // Boot Behavior
    fun setStartOnBoot(context: Context, enabled: Boolean) {
        getPrefs(context).edit {
            putBoolean(KEY_START_ON_BOOT, enabled)
        }
    }

    fun shouldStartOnBoot(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_START_ON_BOOT, true)
    }

    fun setDelayLabel(context: Context, label: DelayLabel) {
        getPrefs(context).edit {
            putString(KEY_DELAY_LABEL, label.name)
        }
    }

    fun getDelayLabel(context: Context): DelayLabel {
        val name = getPrefs(context).getString(KEY_DELAY_LABEL, DelayLabel.SHORT.name)
        return try {
            DelayLabel.valueOf(name ?: DelayLabel.SHORT.name)
        } catch (e: Exception) {
            DelayLabel.SHORT
        }
    }

    fun setSkipOnDnd(context: Context, skip: Boolean) {
        getPrefs(context).edit {
            putBoolean(KEY_SKIP_ON_DND, skip)
        }
    }

    fun shouldSkipOnDnd(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SKIP_ON_DND, true)
    }
}
