package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereDelivery
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereSourceProvisioner
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.logic.StorageUsage
import com.ninecsdev.wallpaperchanger.logic.WallpaperApplier
import com.ninecsdev.wallpaperchanger.logic.WallpaperApplyOutcome
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Reads the app version from PackageInfo and combines all
 * settings flows into a single [SettingsUiState].
 *
 * Reads and writes settings directly through [AppDataStore]
 * Note: maybe in the future add a repository for this.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appDataStore: AppDataStore,
    private val imageInternalizer: ImageInternalizer,
    private val wallpaperSources: WallpaperSources,
    private val repository: WallpaperRepository,
    private val wallpaperApplier: WallpaperApplier,
    private val wallpaperModeResolver: WallpaperModeResolver,
    private val atmosphereDelivery: AtmosphereDelivery,
    private val atmosphereSourceProvisioner: AtmosphereSourceProvisioner,
    private val rotationEngine: RotationEngine,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SettingsActions {

    private companion object {
        const val TAG = "SettingsViewModel"
    }

    private val appVersion: String = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: ""
    } catch (_: Exception) { "" }

    // App locale lives in the system per-app locale store (not AppDataStore). The list of
    // supported languages is fixed (read once from locales_config); only the selected tag varies.
    private val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager
    private val availableLanguages: List<LanguageOption> = buildLanguageList(context)
    private val selectedLanguageTag = MutableStateFlow(currentLanguageTag())

    /**
     * Named intermediate groups. `combine` takes at most 5 flows, so the settings are folded in two
     * stages; carrying the partials as small data classes instead of nested `Pair`/`Triple` keeps
     * the fold one level deep and the fields readable at the point of use.
     */
    private data class LockscreenSettings(
        val batterySaverPolicy: BatterySaverPolicy,
        val wallpaperZoomFix: WallpaperZoomFix,
        val wallpaperDestination: WallpaperDestination
    )

    private data class AtmosphereSettings(
        val mode: WallpaperMode,
        val engineActive: Boolean,
        val hasSource: Boolean
    )

    private data class SettingsBundle(
        val lockscreen: LockscreenSettings,
        val keepLocalCopies: Boolean,
        val hasMediaAccess: Boolean,
        val hasPartialMediaAccess: Boolean,
        val languageTag: String,
        val atmosphere: AtmosphereSettings
    )

    private val lockscreenSettingsFlow = combine(
        appDataStore.batterySaverPolicyFlow(),
        appDataStore.wallpaperZoomFixFlow(),
        appDataStore.wallpaperDestinationFlow(),
        ::LockscreenSettings
    )

    // Permission state has no system callback, so it's snapshot here (full to partial) and
    // refreshed by the Route on every resume (the user may grant/revoke in system settings
    // and come back).
    private val mediaAccess = MutableStateFlow(snapshotMediaAccess())

    private fun snapshotMediaAccess(): Pair<Boolean, Boolean> =
        wallpaperSources.hasMediaAccess() to wallpaperSources.hasPartialMediaAccess()

    // Whether NWC's live wallpaper is actually the system wallpaper. Like [hasMediaAccess] this has
    // no system callback (the user sets/replaces it via the system picker or another launcher), so
    // it's a snapshot the Route re-checks on every resume.
    private val atmosphereEngineActive = MutableStateFlow(wallpaperModeResolver.isAtmosphereEngineActive())

    // Whether any image exists to feed the atmosphere renderer: an available image of the active
    // collection, or the default wallpaper as fallback. Drives the set-button's disabled state.
    private val hasAtmosphereSourceFlow = combine(
        repository.activeCollectionImagesFlow(),
        appDataStore.defaultWallpaperUriFlow()
    ) { activeSnapshot, defaultUri ->
        (activeSnapshot?.second?.isNotEmpty() == true) || defaultUri != null
    }

    private val atmosphereFlow = combine(
        appDataStore.wallpaperModeFlow(),
        atmosphereEngineActive,
        hasAtmosphereSourceFlow,
        ::AtmosphereSettings
    )

    private val settingsBundleFlow = combine(
        lockscreenSettingsFlow,
        appDataStore.keepLocalCopiesFlow(),
        mediaAccess,
        selectedLanguageTag,
        atmosphereFlow
    ) { lockscreen, keepLocalCopies, access, languageTag, atmosphere ->
        SettingsBundle(
            lockscreen = lockscreen,
            keepLocalCopies = keepLocalCopies,
            hasMediaAccess = access.first,
            hasPartialMediaAccess = access.second,
            languageTag = languageTag,
            atmosphere = atmosphere
        )
    }

    // Null until every settings flow has emitted; the UI renders nothing until then so no
    // fabricated default can flash or animate to the real persisted value.
    // TODO tests: see vault note tests/ui-state-loading.md
    val uiState: StateFlow<SettingsUiState?> = combine(
        appDataStore.screenOffDelayFlow(),
        appDataStore.startOnBootFlow(),
        appDataStore.compressionQualityHighFlow(),
        appDataStore.compressionQualityLowFlow(),
        settingsBundleFlow
    ) { delay, boot, qualityHigh, qualityLow, bundle ->
        SettingsUiState(
            screenOffDelayMs = delay,
            startOnBoot = boot,
            batterySaverPolicy = bundle.lockscreen.batterySaverPolicy,
            wallpaperZoomFix = bundle.lockscreen.wallpaperZoomFix,
            wallpaperDestination = bundle.lockscreen.wallpaperDestination,
            wallpaperMode = bundle.atmosphere.mode,
            atmosphereEngineActive = bundle.atmosphere.engineActive,
            hasAtmosphereSource = bundle.atmosphere.hasSource,
            compressionQualityHigh = qualityHigh,
            compressionQualityLow = qualityLow,
            keepLocalCopies = bundle.keepLocalCopies,
            hasMediaAccess = bundle.hasMediaAccess,
            hasPartialMediaAccess = bundle.hasPartialMediaAccess,
            availableLanguages = availableLanguages,
            selectedLanguageTag = bundle.languageTag,
            appVersion = appVersion
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    // Kept out of [uiState] on purpose: it's a filesystem-derived display value, not a setting,
    // and folding it into the combine would gate the whole screen's render on a directory walk.
    val storageUsage: StateFlow<StorageUsage?> = flow {
        emit(imageInternalizer.getStorageUsage(context))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    // Actions

    override fun setScreenOffDelay(delayMs: Long) {
        viewModelScope.launch { appDataStore.setScreenOffDelay(delayMs) }
    }

    override fun setStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { appDataStore.setStartOnBoot(enabled) }
    }

    override fun setCompressionQualityHigh(quality: Int) {
        viewModelScope.launch { appDataStore.setCompressionQualityHigh(quality) }
    }

    override fun setCompressionQualityLow(quality: Int) {
        viewModelScope.launch { appDataStore.setCompressionQualityLow(quality) }
    }

    override fun setBatterySaverPolicy(policy: BatterySaverPolicy) {
        viewModelScope.launch { appDataStore.setBatterySaverPolicy(policy) }
    }

    override fun setWallpaperDestination(destination: WallpaperDestination) {
        viewModelScope.launch { appDataStore.setWallpaperDestination(destination) }
    }

    override fun setWallpaperZoomFix(zoomFix: WallpaperZoomFix) {
        viewModelScope.launch {
            appDataStore.setWallpaperZoomFix(zoomFix)
            // The zoom fix now applies in atmosphere mode too, so a change to it has to reach the engine.
            refreshAtmosphereSourceIfLive()
        }
    }

    /**
     * Persists the desired [mode]. Switching ATMOSPHERE → STATIC while the live engine is set is the
     * deliberate exit-live-wallpaper path: a static wallpaper is applied immediately to replace it
     * (entering atmosphere, by contrast, needs the user to confirm on the system picker).
     *
     * The setting is written first so everything downstream sees the new effective mode, but it is
     * **rolled back** if the exit fails to actually replace the live wallpaper. A stored STATIC with
     * the atmosphere engine still running is a state [SettingsUiState] cannot describe (the engine
     * reads as active while the mode says static, so the UI shows neither the mismatch prompt nor a
     * plain static screen), so the switch is reported as not having happened.
     *
     * Note there is no buffer refill on the *entry* path. Entry only records a desire — the engine
     * is not live until the user confirms on the system picker, so `effectiveMode()` still reports
     * STATIC here and a refill would render the zoom-fix-padded buffer that
     * [refreshAtmosphereEngineActive] has to throw away and re-render anyway once the engine
     * actually goes live.
     */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md (mode-switch refill + exit)
    override fun setWallpaperMode(mode: WallpaperMode) {
        viewModelScope.launch {
            val previous = appDataStore.getWallpaperMode()
            appDataStore.setWallpaperMode(mode)

            val leavingActiveAtmosphere = previous == WallpaperMode.ATMOSPHERE &&
                mode == WallpaperMode.STATIC &&
                wallpaperModeResolver.isAtmosphereEngineActive()

            if (leavingActiveAtmosphere) {
                // Effective mode is STATIC from here on, so this re-renders the buffer *without* the
                // atmosphere zoom-fix bypass — which is exactly what the static apply below needs.
                val refilled = rotationEngine.refillDiskBuffer()
                val replaced = (refilled &&
                    wallpaperApplier.applyBufferWallpaper() == WallpaperApplyOutcome.SHOWN) ||
                    wallpaperApplier.applyDefaultWallpaper()
                if (!replaced) {
                    Log.w(TAG, "Could not replace the live wallpaper; reverting mode to ATMOSPHERE.")
                    appDataStore.setWallpaperMode(WallpaperMode.ATMOSPHERE)
                }
                refreshAtmosphereEngineActive()
                // Reclaim the source file, but only once the engine is confirmed gone — a still-live
                // engine re-reads it on every cold start.
                if (replaced && !atmosphereEngineActive.value) atmosphereDelivery.clearSource()
            } else if (mode == WallpaperMode.ATMOSPHERE) {
                // No-op unless the engine is somehow already live (re-entering after an external
                // wallpaper change); the normal entry path renders via the set button instead.
                refreshAtmosphereSourceIfLive()
            }
        }
    }

    override fun setKeepLocalCopies(enabled: Boolean) {
        viewModelScope.launch { appDataStore.setKeepLocalCopies(enabled) }
    }

    override fun refreshMediaAccess() {
        mediaAccess.value = snapshotMediaAccess()
    }

    /**
     * Re-snapshots engine liveness and, on the false -> true edge, brings everything that was
     * rendered for the static path up to the framing atmosphere actually wants.
     *
     * That edge is the moment [WallpaperModeResolver.effectiveMode] starts answering ATMOSPHERE, so
     * it is the first moment the *buffer* can be rendered correctly for it; that is what the refill
     * below fixes, for the next rotation. The source re-render is a safety net.
     */
    override fun refreshAtmosphereEngineActive() {
        val wasActive = atmosphereEngineActive.value
        val isActive = wallpaperModeResolver.isAtmosphereEngineActive()
        atmosphereEngineActive.value = isActive

        if (isActive && !wasActive) {
            // Ensure the engine owns both screens so atmosphere works correctly
            wallpaperModeResolver.ensureEngineOwnsLockScreen()
            viewModelScope.launch {
                // Already correct when the set button ran; re-rendered for the paths that skipped
                // it. Ungated by the desired mode
                atmosphereSourceProvisioner.provision()
                if (appDataStore.getWallpaperMode() == WallpaperMode.ATMOSPHERE) {
                    rotationEngine.refillDiskBuffer()
                }
            }
        }
    }

    /**
     * Renders the atmosphere source image to disk and delivers it, returning true once it's ready.
     *
     * Not part of [SettingsActions]: it's a suspend call the Route awaits before firing the
     * activity intent (mirrors how `onRequestMediaAccess` is a plain Route-level callback).
     */
    suspend fun prepareAtmosphereSource(): Boolean = atmosphereSourceProvisioner.provision()

    /**
     * Re-renders the atmosphere source and signals the engine, but only while the engine is
     * actually live and atmosphere is desired.
     */
    private suspend fun refreshAtmosphereSourceIfLive() {
        if (appDataStore.getWallpaperMode() != WallpaperMode.ATMOSPHERE) return
        if (!wallpaperModeResolver.isAtmosphereEngineActive()) return
        atmosphereSourceProvisioner.provision()
    }

    /**
     * Applies [tag] as the app locale (empty tag → follow the system locale). Updates the UI
     * optimistically, then hands the change to the system, which recreates the Activity; the
     * next VM instance re-seeds [selectedLanguageTag] from [currentLanguageTag].
     */
    override fun setAppLanguage(tag: String) {
        selectedLanguageTag.value = tag
        val localeList = if (tag.isEmpty()) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(tag)
        }
        localeManager?.applicationLocales = localeList
    }

    /** Current app-locale tag from the system store, or "" when following the system default. */
    private fun currentLanguageTag(): String {
        val locales = localeManager?.applicationLocales
        return if (locales == null || locales.isEmpty) "" else locales.get(0)?.toLanguageTag() ?: ""
    }

    /**
     * Reads locales_config, builds a [LanguageOption] for each declared locale, and prepends a
     * "System default" entry (empty tag).
     */
    private fun buildLanguageList(context: Context): List<LanguageOption> {
        val result = mutableListOf<LanguageOption>()

        val parser = context.resources.getXml(R.xml.locales_config)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i) == "name") {
                        val tag = parser.getAttributeValue(i)
                        val locale = Locale.forLanguageTag(tag)
                        val nativeName = locale.getDisplayName(locale).replaceFirstChar { it.uppercase() }
                        val displayName = locale.getDisplayName(Locale.getDefault()).replaceFirstChar { it.uppercase() }
                        if (nativeName.isNotEmpty()) {
                            result.add(LanguageOption(tag, nativeName, displayName))
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        result.sortBy { it.nativeName }
        result.add(0, LanguageOption("", context.getString(R.string.settings_language_system), null))
        return result
    }
}
