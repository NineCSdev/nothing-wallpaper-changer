package com.ninecsdev.wallpaperchanger.data.local

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_DEFAULT_WALLPAPER_URI = stringPreferencesKey("default_wallpaper_uri")
private val KEY_REVERT_TO_DEFAULT = booleanPreferencesKey("revert_to_default_on_stop")
private val KEY_SERVICE_RUNNING = booleanPreferencesKey("service_running")
private val KEY_SERVICE_DESIRED = booleanPreferencesKey("service_desired")
private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
private val KEY_SCREEN_OFF_DELAY = longPreferencesKey("screen_off_delay_ms")
private val KEY_COMPRESSION_QUALITY_HIGH = intPreferencesKey("compression_quality_high")
private val KEY_COMPRESSION_QUALITY_LOW = intPreferencesKey("compression_quality_low")
private val KEY_BATTERY_SAVER_POLICY = stringPreferencesKey("battery_saver_policy")
private val KEY_WALLPAPER_ZOOM_FIX = intPreferencesKey("lockscreen_zoom_fix")
private val KEY_WALLPAPER_DESTINATION = stringPreferencesKey("wallpaper_destination")
private val KEY_WALLPAPER_MODE = stringPreferencesKey("wallpaper_mode")
private val KEY_KEEP_LOCAL_COPIES = booleanPreferencesKey("keep_local_copies")
private val KEY_ROTATION_POLICY = stringPreferencesKey("rotation_policy")
private val KEY_SKIP_ON_DND = booleanPreferencesKey("skip_on_dnd")

/** The user's settings: what they chose, as distinct from the [WallpaperRecordStore]'s record of what happened. */
@Singleton
class AppDataStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore: DataStore<Preferences> = context.appPreferences

    private val safeData: Flow<Preferences> = dataStore.safeData()

    private fun <T> settingFlow(key: Preferences.Key<T>, default: T): Flow<T> =
        safeData.map { prefs -> prefs[key] ?: default }.distinctUntilChanged()

    private fun <S, T> mappedSettingFlow(
        key: Preferences.Key<S>,
        default: T,
        read: (S) -> T
    ): Flow<T> =
        safeData.map { prefs -> prefs[key]?.let(read) ?: default }.distinctUntilChanged()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    /** Removes the key when [value] is null, for settings whose absence is meaningful. */
    private suspend fun <T> setOrClear(key: Preferences.Key<T>, value: T?) {
        dataStore.edit { prefs -> prefs.setOrClear(key, value) }
    }

    /** An unknown stored name falls back to [default]. */
    private inline fun <reified T : Enum<T>> enumByName(raw: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default

    fun revertToDefaultFlow(): Flow<Boolean> =
        settingFlow(KEY_REVERT_TO_DEFAULT, true)

    fun serviceRunningFlow(): Flow<Boolean> =
        settingFlow(KEY_SERVICE_RUNNING, false)

    /**
     * "The service was last left on." Unlike [serviceRunningFlow] it is never cleared by the stale-flag
     * self-heal, so it survives an ungraceful kill (package replace never runs `onDestroy`) and drives restart.
     * Installs predating this key fall back to `service_running`.
     */
    fun serviceDesiredFlow(): Flow<Boolean> =
        safeData.map { prefs -> prefs[KEY_SERVICE_DESIRED] ?: prefs[KEY_SERVICE_RUNNING] ?: false }
            .distinctUntilChanged()

    fun startOnBootFlow(): Flow<Boolean> =
        settingFlow(KEY_START_ON_BOOT, true)

    fun screenOffDelayFlow(): Flow<Long> =
        settingFlow(KEY_SCREEN_OFF_DELAY, DeviceDefaults.forThisDevice())

    fun compressionQualityHighFlow(): Flow<Int> =
        settingFlow(KEY_COMPRESSION_QUALITY_HIGH, 95)

    fun compressionQualityLowFlow(): Flow<Int> =
        settingFlow(KEY_COMPRESSION_QUALITY_LOW, 80)

    fun batterySaverPolicyFlow(): Flow<BatterySaverPolicy> =
        mappedSettingFlow(KEY_BATTERY_SAVER_POLICY, BatterySaverPolicy.PAUSE) {
            enumByName(it, BatterySaverPolicy.PAUSE)
        }

    fun wallpaperZoomFixFlow(): Flow<WallpaperZoomFix> =
        mappedSettingFlow(KEY_WALLPAPER_ZOOM_FIX, WallpaperZoomFix.OFF) {
            WallpaperZoomFix.fromStoredValue(it)
        }

    fun wallpaperDestinationFlow(): Flow<WallpaperDestination> =
        mappedSettingFlow(KEY_WALLPAPER_DESTINATION, WallpaperDestination.LOCK) {
            enumByName(it, WallpaperDestination.LOCK)
        }

    fun wallpaperModeFlow(): Flow<WallpaperMode> =
        mappedSettingFlow(KEY_WALLPAPER_MODE, WallpaperMode.STATIC) {
            enumByName(it, WallpaperMode.STATIC)
        }

    fun keepLocalCopiesFlow(): Flow<Boolean> =
        settingFlow(KEY_KEEP_LOCAL_COPIES, false)

    fun rotationPolicyFlow(): Flow<RotationPolicy> =
        mappedSettingFlow(KEY_ROTATION_POLICY, RotationPolicy.PerLock) { RotationPolicy.decode(it) }

    fun skipOnDndFlow(): Flow<Boolean> =
        settingFlow(KEY_SKIP_ON_DND, false)

    /** The default wallpaper as stored before it became a row; read only by the [StartupMaintenance] backfill. */
    // TODO: remove with the backfill in v0.4.1, along with KEY_DEFAULT_WALLPAPER_URI
    suspend fun getLegacyDefaultWallpaperUri(): Uri? =
        mappedSettingFlow(KEY_DEFAULT_WALLPAPER_URI, null) { it.toUri() }.first()

    suspend fun shouldRevertToDefault(): Boolean =
        revertToDefaultFlow().first()

    suspend fun isServiceDesired(): Boolean =
        serviceDesiredFlow().first()

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

    suspend fun getWallpaperZoomFix(): WallpaperZoomFix =
        wallpaperZoomFixFlow().first()

    suspend fun getWallpaperDestination(): WallpaperDestination =
        wallpaperDestinationFlow().first()

    suspend fun getWallpaperMode(): WallpaperMode =
        wallpaperModeFlow().first()

    suspend fun getKeepLocalCopies(): Boolean =
        keepLocalCopiesFlow().first()

    suspend fun getRotationPolicy(): RotationPolicy =
        rotationPolicyFlow().first()

    suspend fun getSkipOnDnd(): Boolean =
        skipOnDndFlow().first()

    // TODO: remove with the backfill in v0.4.1, along with KEY_DEFAULT_WALLPAPER_URI
    suspend fun clearLegacyDefaultWallpaperUri() =
        setOrClear(KEY_DEFAULT_WALLPAPER_URI, null)

    suspend fun setRevertToDefault(revert: Boolean) =
        set(KEY_REVERT_TO_DEFAULT, revert)

    suspend fun setSkipOnDnd(skip: Boolean) =
        set(KEY_SKIP_ON_DND, skip)

    suspend fun setServiceRunning(isRunning: Boolean) =
        set(KEY_SERVICE_RUNNING, isRunning)

    suspend fun setServiceDesired(desired: Boolean) =
        set(KEY_SERVICE_DESIRED, desired)

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

    suspend fun setWallpaperZoomFix(zoomFix: WallpaperZoomFix) =
        set(KEY_WALLPAPER_ZOOM_FIX, zoomFix.storedValue)

    suspend fun setWallpaperDestination(target: WallpaperDestination) =
        set(KEY_WALLPAPER_DESTINATION, target.name)

    suspend fun setWallpaperMode(mode: WallpaperMode) =
        set(KEY_WALLPAPER_MODE, mode.name)

    suspend fun setKeepLocalCopies(enabled: Boolean) =
        set(KEY_KEEP_LOCAL_COPIES, enabled)

    suspend fun setRotationPolicy(policy: RotationPolicy) =
        set(KEY_ROTATION_POLICY, policy.encode())
}
