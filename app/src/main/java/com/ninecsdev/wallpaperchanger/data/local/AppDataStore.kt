package com.ninecsdev.wallpaperchanger.data.local

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.LockscreenZoomFix
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Migration things
private const val OLD_PREFS_NAME = "smart_wallpaper_prefs"
private val Context.dataStore by preferencesDataStore(
    name = "app_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, OLD_PREFS_NAME))
    }
)

private val KEY_DEFAULT_WALLPAPER_URI = stringPreferencesKey("default_wallpaper_uri")
private val KEY_REVERT_TO_DEFAULT = booleanPreferencesKey("revert_to_default_on_stop")
private val KEY_SERVICE_RUNNING = booleanPreferencesKey("service_running")
private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
private val KEY_SCREEN_OFF_DELAY = longPreferencesKey("screen_off_delay_ms")
private val KEY_COMPRESSION_QUALITY_HIGH = intPreferencesKey("compression_quality_high")
private val KEY_COMPRESSION_QUALITY_LOW = intPreferencesKey("compression_quality_low")
private val KEY_BATTERY_SAVER_POLICY = stringPreferencesKey("battery_saver_policy")
private val KEY_LOCKSCREEN_ZOOM_FIX = intPreferencesKey("lockscreen_zoom_fix")
private val KEY_WALLPAPER_DESTINATION = stringPreferencesKey("wallpaper_destination")

/**
 * Manages simple key-value pairs for global application settings using
 * Jetpack DataStore. Replaces the legacy SharedPreferences-based AppPreferences.
 *
 * On first launch after migration, existing SharedPreferences values are
 * automatically imported and the old file is deleted.
 */
@Singleton
class AppDataStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private companion object {
        const val TAG = "AppDataStore"
    }

    private val dataStore: DataStore<Preferences> = context.dataStore

    /**
     * Shared safe data flow. Catches IO and corruption errors from the DataStore file
     * (CorruptionException is an IOException) and falls back to empty preferences,
     * returning defaults instead of crashing. Any other error propagates.
     */
    private val safeData: Flow<Preferences> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "DataStore read failed, falling back to defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }

    // Generic helpers, every setting below is one of these

    private fun <T> settingFlow(key: Preferences.Key<T>, default: T): Flow<T> =
        safeData.map { prefs -> prefs[key] ?: default }

    /** For settings whose stored type differs from the exposed type (enums, Uri). */
    private fun <S, T> mappedSettingFlow(
        key: Preferences.Key<S>,
        default: T,
        read: (S) -> T
    ): Flow<T> =
        safeData.map { prefs -> prefs[key]?.let(read) ?: default }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    /** Enum-by-name parse that treats a corrupted/unknown stored value as the default. */
    private inline fun <reified T : Enum<T>> enumByName(raw: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default

    // Flows (reactive reads)

    fun defaultWallpaperUriFlow(): Flow<Uri?> =
        mappedSettingFlow(KEY_DEFAULT_WALLPAPER_URI, null) { it.toUri() }

    fun revertToDefaultFlow(): Flow<Boolean> =
        settingFlow(KEY_REVERT_TO_DEFAULT, true)

    fun serviceRunningFlow(): Flow<Boolean> =
        settingFlow(KEY_SERVICE_RUNNING, false)

    fun startOnBootFlow(): Flow<Boolean> =
        settingFlow(KEY_START_ON_BOOT, true)

    fun screenOffDelayFlow(): Flow<Long> =
        settingFlow(KEY_SCREEN_OFF_DELAY, 250L)

    fun compressionQualityHighFlow(): Flow<Int> =
        settingFlow(KEY_COMPRESSION_QUALITY_HIGH, 95)

    fun compressionQualityLowFlow(): Flow<Int> =
        settingFlow(KEY_COMPRESSION_QUALITY_LOW, 80)

    fun batterySaverPolicyFlow(): Flow<BatterySaverPolicy> =
        mappedSettingFlow(KEY_BATTERY_SAVER_POLICY, BatterySaverPolicy.PAUSE) {
            enumByName(it, BatterySaverPolicy.PAUSE)
        }

    fun lockscreenZoomFixFlow(): Flow<LockscreenZoomFix> =
        mappedSettingFlow(KEY_LOCKSCREEN_ZOOM_FIX, LockscreenZoomFix.OFF) {
            LockscreenZoomFix.fromStoredValue(it)
        }

    fun wallpaperDestinationFlow(): Flow<WallpaperDestination> =
        mappedSettingFlow(KEY_WALLPAPER_DESTINATION, WallpaperDestination.LOCK) {
            enumByName(it, WallpaperDestination.LOCK)
        }

    // Suspend reads (suspend, one-shot)

    suspend fun getDefaultWallpaperUri(): Uri? =
        defaultWallpaperUriFlow().first()

    suspend fun shouldRevertToDefault(): Boolean =
        revertToDefaultFlow().first()

    suspend fun isServiceRunning(): Boolean =
        serviceRunningFlow().first()

    suspend fun shouldStartOnBoot(): Boolean =
        startOnBootFlow().first()

    suspend fun getScreenOffDelay(): Long =
        screenOffDelayFlow().first()

    suspend fun getCompressionQualityHigh(): Int =
        compressionQualityHighFlow().first()

    suspend fun getCompressionQualityLow(): Int =
        compressionQualityLowFlow().first()

    suspend fun getBatterySaverPolicy(): BatterySaverPolicy =
        batterySaverPolicyFlow().first()

    suspend fun getLockscreenZoomFix(): LockscreenZoomFix =
        lockscreenZoomFixFlow().first()

    suspend fun getWallpaperDestination(): WallpaperDestination =
        wallpaperDestinationFlow().first()

    // Writes (suspend)

    suspend fun saveDefaultWallpaperUri(uri: Uri) =
        set(KEY_DEFAULT_WALLPAPER_URI, uri.toString())

    suspend fun setRevertToDefault(revert: Boolean) =
        set(KEY_REVERT_TO_DEFAULT, revert)

    suspend fun setServiceRunning(isRunning: Boolean) =
        set(KEY_SERVICE_RUNNING, isRunning)

    suspend fun setStartOnBoot(enabled: Boolean) =
        set(KEY_START_ON_BOOT, enabled)

    suspend fun setScreenOffDelay(delayMs: Long) =
        set(KEY_SCREEN_OFF_DELAY, delayMs)

    suspend fun setCompressionQualityHigh(quality: Int) =
        set(KEY_COMPRESSION_QUALITY_HIGH, quality)

    suspend fun setCompressionQualityLow(quality: Int) =
        set(KEY_COMPRESSION_QUALITY_LOW, quality)

    suspend fun setBatterySaverPolicy(policy: BatterySaverPolicy) =
        set(KEY_BATTERY_SAVER_POLICY, policy.name)

    suspend fun setLockscreenZoomFix(zoomFix: LockscreenZoomFix) =
        set(KEY_LOCKSCREEN_ZOOM_FIX, zoomFix.storedValue)

    suspend fun setWallpaperDestination(target: WallpaperDestination) =
        set(KEY_WALLPAPER_DESTINATION, target.name)
}
