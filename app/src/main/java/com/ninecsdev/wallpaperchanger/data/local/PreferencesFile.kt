package com.ninecsdev.wallpaperchanger.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

private const val TAG = "PreferencesFile"
private const val OLD_PREFS_NAME = "smart_wallpaper_prefs"

/**
 * The one preferences file the app persists to. Shared by [AppDataStore] and [WallpaperRecordStore].
 * On first launch after migration, existing SharedPreferences values are imported and the old file is deleted.
 */
internal val Context.appPreferences by preferencesDataStore(
    name = "app_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, OLD_PREFS_NAME))
    }
)

/**
 * Reads that survive a damaged file. IO and corruption errors (CorruptionException is an
 * IOException) fall back to empty preferences so every reader gets its defaults instead of a crash
 */
internal fun DataStore<Preferences>.safeData(): Flow<Preferences> = data.catch { e ->
    if (e is IOException) {
        Log.e(TAG, "DataStore read failed, falling back to defaults", e)
        emit(emptyPreferences())
    } else {
        throw e
    }
}
