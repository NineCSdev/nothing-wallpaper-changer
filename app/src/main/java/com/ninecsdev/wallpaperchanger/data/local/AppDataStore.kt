package com.ninecsdev.wallpaperchanger.data.local

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.ninecsdev.wallpaperchanger.model.BatterySaverPolicy

/**
 * Manages simple key-value pairs for global application settings using
 * Jetpack DataStore. Replaces the legacy SharedPreferences-based AppPreferences.
 *
 * On first launch after migration, existing SharedPreferences values are
 * automatically imported and the old file is deleted.
 */

private const val OLD_PREFS_NAME = "smart_wallpaper_prefs"

private val Context.dataStore by preferencesDataStore(
    name = "app_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, OLD_PREFS_NAME))
    }
)

object AppDataStore {

    private val KEY_DEFAULT_WALLPAPER_URI = stringPreferencesKey("default_wallpaper_uri")
    private val KEY_REVERT_TO_DEFAULT = booleanPreferencesKey("revert_to_default_on_stop")
    private val KEY_SERVICE_RUNNING = booleanPreferencesKey("service_running")
    private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    private val KEY_SCREEN_OFF_DELAY = longPreferencesKey("screen_off_delay_ms")
    private val KEY_COMPRESSION_QUALITY_HIGH = intPreferencesKey("compression_quality_high")
    private val KEY_COMPRESSION_QUALITY_LOW = intPreferencesKey("compression_quality_low")
    private val KEY_BATTERY_SAVER_POLICY = stringPreferencesKey("battery_saver_policy")

    // Flows (reactive reads)

    fun defaultWallpaperUriFlow(context: Context): Flow<Uri?> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_DEFAULT_WALLPAPER_URI]?.toUri()
        }

    fun revertToDefaultFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_REVERT_TO_DEFAULT] ?: true
        }

    fun serviceRunningFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_SERVICE_RUNNING] ?: false
        }

    fun startOnBootFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_START_ON_BOOT] ?: true
        }

    fun screenOffDelayFlow(context: Context): Flow<Long> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_SCREEN_OFF_DELAY] ?: 250L
        }

    fun compressionQualityHighFlow(context: Context): Flow<Int> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_COMPRESSION_QUALITY_HIGH] ?: 95
        }

    fun compressionQualityLowFlow(context: Context): Flow<Int> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_COMPRESSION_QUALITY_LOW] ?: 80
        }

    fun batterySaverPolicyFlow(context: Context): Flow<BatterySaverPolicy> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[KEY_BATTERY_SAVER_POLICY]
            // For first install we default to PAUSE
            if (raw != null) {
                // In case the data has been corrupted we fix to PAUSE
                try { BatterySaverPolicy.valueOf(raw) } catch (_: Exception) { BatterySaverPolicy.PAUSE }
            } else {
                BatterySaverPolicy.PAUSE
            }
        }

    // Suspend reads (suspend, one-shot)

    suspend fun getDefaultWallpaperUri(context: Context): Uri? =
        defaultWallpaperUriFlow(context).first()

    suspend fun shouldRevertToDefault(context: Context): Boolean =
        revertToDefaultFlow(context).first()

    suspend fun isServiceRunning(context: Context): Boolean =
        serviceRunningFlow(context).first()

    suspend fun shouldStartOnBoot(context: Context): Boolean =
        startOnBootFlow(context).first()

    suspend fun getScreenOffDelay(context: Context): Long =
        screenOffDelayFlow(context).first()

    suspend fun getCompressionQualityHigh(context: Context): Int =
        compressionQualityHighFlow(context).first()

    suspend fun getCompressionQualityLow(context: Context): Int =
        compressionQualityLowFlow(context).first()

    suspend fun getBatterySaverPolicy(context: Context): BatterySaverPolicy =
        batterySaverPolicyFlow(context).first()

    // Writes (suspend)

    suspend fun saveDefaultWallpaperUri(context: Context, uri: Uri) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_WALLPAPER_URI] = uri.toString()
        }
    }

    suspend fun setRevertToDefault(context: Context, revert: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_REVERT_TO_DEFAULT] = revert
        }
    }

    suspend fun setServiceRunning(context: Context, isRunning: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVICE_RUNNING] = isRunning
        }
    }

    suspend fun setStartOnBoot(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_START_ON_BOOT] = enabled
        }
    }

    suspend fun setScreenOffDelay(context: Context, delayMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCREEN_OFF_DELAY] = delayMs
        }
    }

    suspend fun setCompressionQualityHigh(context: Context, quality: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COMPRESSION_QUALITY_HIGH] = quality
        }
    }

    suspend fun setCompressionQualityLow(context: Context, quality: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COMPRESSION_QUALITY_LOW] = quality
        }
    }

    suspend fun setBatterySaverPolicy(context: Context, policy: BatterySaverPolicy) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BATTERY_SAVER_POLICY] = policy.name
        }
    }
}
