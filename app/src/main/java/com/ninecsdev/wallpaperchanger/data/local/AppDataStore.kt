package com.ninecsdev.wallpaperchanger.data.local

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ninecsdev.wallpaperchanger.model.BatterySaverPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

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

@Singleton
class AppDataStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore: DataStore<Preferences> = context.dataStore

    private val KEY_DEFAULT_WALLPAPER_URI = stringPreferencesKey("default_wallpaper_uri")
    private val KEY_REVERT_TO_DEFAULT = booleanPreferencesKey("revert_to_default_on_stop")
    private val KEY_SERVICE_RUNNING = booleanPreferencesKey("service_running")
    private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    private val KEY_SCREEN_OFF_DELAY = longPreferencesKey("screen_off_delay_ms")
    private val KEY_COMPRESSION_QUALITY_HIGH = intPreferencesKey("compression_quality_high")
    private val KEY_COMPRESSION_QUALITY_LOW = intPreferencesKey("compression_quality_low")
    private val KEY_BATTERY_SAVER_POLICY = stringPreferencesKey("battery_saver_policy")

    // Flows (reactive reads)

    fun defaultWallpaperUriFlow(): Flow<Uri?> =
        dataStore.data.map { prefs ->
            prefs[KEY_DEFAULT_WALLPAPER_URI]?.toUri()
        }

    fun revertToDefaultFlow(): Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[KEY_REVERT_TO_DEFAULT] ?: true
        }

    fun serviceRunningFlow(): Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[KEY_SERVICE_RUNNING] ?: false
        }

    fun startOnBootFlow(): Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[KEY_START_ON_BOOT] ?: true
        }

    fun screenOffDelayFlow(): Flow<Long> =
        dataStore.data.map { prefs ->
            prefs[KEY_SCREEN_OFF_DELAY] ?: 250L
        }

    fun compressionQualityHighFlow(): Flow<Int> =
        dataStore.data.map { prefs ->
            prefs[KEY_COMPRESSION_QUALITY_HIGH] ?: 95
        }

    fun compressionQualityLowFlow(): Flow<Int> =
        dataStore.data.map { prefs ->
            prefs[KEY_COMPRESSION_QUALITY_LOW] ?: 80
        }

    fun batterySaverPolicyFlow(): Flow<BatterySaverPolicy> =
        dataStore.data.map { prefs ->
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

    // Writes (suspend)

    suspend fun saveDefaultWallpaperUri(uri: Uri) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_WALLPAPER_URI] = uri.toString()
        }
    }

    suspend fun setRevertToDefault(revert: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_REVERT_TO_DEFAULT] = revert
        }
    }

    suspend fun setServiceRunning(isRunning: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SERVICE_RUNNING] = isRunning
        }
    }

    suspend fun setStartOnBoot(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_START_ON_BOOT] = enabled
        }
    }

    suspend fun setScreenOffDelay(delayMs: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_SCREEN_OFF_DELAY] = delayMs
        }
    }

    suspend fun setCompressionQualityHigh(quality: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_COMPRESSION_QUALITY_HIGH] = quality
        }
    }

    suspend fun setCompressionQualityLow(quality: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_COMPRESSION_QUALITY_LOW] = quality
        }
    }

    suspend fun setBatterySaverPolicy(policy: BatterySaverPolicy) {
        dataStore.edit { prefs ->
            prefs[KEY_BATTERY_SAVER_POLICY] = policy.name
        }
    }
}
