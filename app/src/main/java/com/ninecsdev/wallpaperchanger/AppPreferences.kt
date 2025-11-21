package com.ninecsdev.wallpaperchanger

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * A singleton object to manage the application's SharedPreferences.
 * This provides a centralized and type-safe way to store and retrieve simple key-value data,
 * such as the selected folder URI and the service's running state. Using an object makes it
 * a thread-safe singleton automatically managed by Kotlin.
 */
object AppPreferences {
    // The name of the preference file where all data will be stored.
    private const val PREFS_NAME = "NothingWallpaperPrefs"
    // The key for storing the wallpaper folder's content URI as a string.
    private const val KEY_FOLDER_URI = "folder_uri"
    // The key for storing the default wallpaper URI as a string.
    private const val KEY_DEFAULT_WALLPAPER_URI="default_wallpaper_uri"
    // The key for storing a boolean flag indicating if the wallpaper should revert to the default on stop.
    private const val KEY_REVERT_TO_DEFAULT = "revert_to_default_on_stop"
    // The key for storing a boolean flag indicating if the background service is currently active.
    private const val KEY_SERVICE_RUNNING = "service_running"

    /**
     * A private helper function to get an instance of the SharedPreferences file.
     * @param context The context required to access SharedPreferences.
     * @return The SharedPreferences instance for this application.
     */
    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Saves the URI of the user-selected wallpaper folder.
     * The URI is converted to a String for storage.
     * @param context The context required to access SharedPreferences.
     * @param uri The folder URI to save.
     */
    fun saveFolderUri(context: Context, uri: Uri) {
        // This now correctly uses apply() by default, which is asynchronous.
        getPrefs(context).edit {
            putString(KEY_FOLDER_URI, uri.toString())
        }
    }

    /**
     * Retrieves the saved wallpaper folder URI.
     * @param context The context required to access SharedPreferences.
     * @return The saved Uri, or null if no URI has been saved yet.
     */
    fun getFolderUri(context: Context): Uri? {
        val uriString = getPrefs(context).getString(KEY_FOLDER_URI, null)
        return uriString?.toUri()
    }

    /**
     * Saves the URI of the user-selected default wallpaper.
     * @param context The context required to access SharedPreferences.
     * @param uri The image URI to save as the default.
     */
    fun saveDefaultWallpaperUri(context: Context, uri: Uri) {
        getPrefs(context).edit {
            putString(KEY_DEFAULT_WALLPAPER_URI, uri.toString())
        }
    }

    /**
     * Retrieves the saved default wallpaper URI.
     * @param context The context required to access SharedPreferences.
     * @return The saved Uri for the default wallpaper, or null if none has been set.
     */
    fun getDefaultWallpaperUri(context: Context): Uri? {
        val uriString = getPrefs(context).getString(KEY_DEFAULT_WALLPAPER_URI, null)
        return uriString?.toUri()
    }

    /**
     * Saves the user's preference for reverting to the default wallpaper.
     * @param context The context required to access SharedPreferences.
     * @param revert True if the app should set the default wallpaper on service stop, false otherwise.
     */
    fun setRevertToDefault(context: Context, revert: Boolean) {
        getPrefs(context).edit {
            putBoolean(KEY_REVERT_TO_DEFAULT, revert)
        }
    }

    /**
     * NEW: Checks the user's preference for reverting to the default wallpaper.
     * @param context The context required to access SharedPreferences.
     * @return True if reverting is enabled, defaults to true.
     */
    fun shouldRevertToDefault(context: Context): Boolean {
        // Default to true so the existing behavior is maintained if not explicitly changed.
        return getPrefs(context).getBoolean(KEY_REVERT_TO_DEFAULT, true)
    }

    /**
     * Sets the running state of the background service.
     * This is used by the service to report its status and by the UI to reflect it.
     * @param context The context required to access SharedPreferences.
     * @param isRunning True if the service is running, false otherwise.
     */
    fun setServiceRunning(context: Context, isRunning: Boolean) {
        // This now correctly uses apply() by default, which is asynchronous.
        getPrefs(context).edit {
            putBoolean(KEY_SERVICE_RUNNING, isRunning)
        }
    }

    /**
     * Checks if the background service is marked as running.
     * @param context The context required to access SharedPreferences.
     * @return True if the service is considered running, false otherwise. Defaults to false.
     */
    fun isServiceRunning(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SERVICE_RUNNING, false)
    }

    /**
     * Checks if the device is currently in Power Save Mode.
     * @param context The context required to access the PowerManager system service.
     * @return True if Power Save Mode is active, false otherwise.
     */
    fun isPowerSaveMode(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isPowerSaveMode ?: false
    }

}