package com.ninecsdev.wallpaperchanger.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

private const val TAG = "PreferencesFile"
private const val OLD_PREFS_NAME = "smart_wallpaper_prefs"

/** The one preferences file, shared by [AppDataStore] and [WallpaperRecordStore]; imports the old SharedPreferences on first launch. */
internal val Context.appPreferences by preferencesDataStore(
    name = "app_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, OLD_PREFS_NAME))
    }
)

/** IO and corruption errors (CorruptionException is an IOException) yield empty preferences, so readers get defaults instead of a crash. */
internal fun DataStore<Preferences>.safeData(): Flow<Preferences> = data.catch { e ->
    if (e is IOException) {
        Log.e(TAG, "DataStore read failed, falling back to defaults", e)
        emit(emptyPreferences())
    } else {
        throw e
    }
}

/** Removes the key when [value] is null, for values whose absence is meaningful. */
internal fun <T> MutablePreferences.setOrClear(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else this[key] = value
}
