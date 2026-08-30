package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereExitOutcome
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereTransition
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.logic.StorageUsage
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
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
    private val atmosphereTransition: AtmosphereTransition,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SettingsActions {

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
        val wallpaperDestination: WallpaperDestination,
        // Rides along here rather than in the outer combine, which is at its five-flow limit.
        val rotationPolicy: RotationPolicy
    )

    private data class AtmosphereSettings(
        val mode: WallpaperMode,
        val engineActive: Boolean,
        val hasSource: Boolean,
        val notice: AtmosphereNotice?
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
        appDataStore.rotationPolicyFlow(),
        ::LockscreenSettings
    )

    // Permission state has no system callback, so it's snapshot here (full to partial) and
    // refreshed by the Route on every resume (the user may grant/revoke in system settings
    // and come back).
    private val mediaAccess = MutableStateFlow(snapshotMediaAccess())

    private fun snapshotMediaAccess(): Pair<Boolean, Boolean> =
        wallpaperSources.hasMediaAccess() to wallpaperSources.hasPartialMediaAccess()

    // One-shot: what the last mode change did that the user needs telling about.
    private val atmosphereNotice = MutableStateFlow<AtmosphereNotice?>(null)

    // Held back because entry hands straight off to the system picker. Raised on the resume instead
    private var lockRemovalPending = false

    private val atmosphereFlow = combine(
        appDataStore.wallpaperModeFlow(),
        atmosphereTransition.liveness,
        atmosphereTransition.canEnter,
        atmosphereNotice,
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
            rotationPolicy = bundle.lockscreen.rotationPolicy,
            wallpaperMode = bundle.atmosphere.mode,
            atmosphereEngineActive = bundle.atmosphere.engineActive,
            hasAtmosphereSource = bundle.atmosphere.hasSource,
            atmosphereNotice = bundle.atmosphere.notice,
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

    override fun setRotationPolicy(policy: RotationPolicy) {
        viewModelScope.launch { appDataStore.setRotationPolicy(policy) }
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
            // Reconcile notices the delivered source no longer matches the settings
            atmosphereTransition.reconcile()
        }
    }

    /**
     * Persists the desired [mode], delegating the transition itself to [AtmosphereTransition].
     *
     * Switching ATMOSPHERE -> STATIC while the live engine is set is the deliberate exit path, it
     * is a compensating transaction that rolls the write back when the live wallpaper cannot be replaced.
     *
     * Entering only records a desire. The engine is not live until the user confirms on the system
     * picker, the set-atmosphere button drives [enterAtmosphere].
     */
    override fun setWallpaperMode(mode: WallpaperMode) {
        viewModelScope.launch {
            val previous = appDataStore.getWallpaperMode()
            val leavingActiveAtmosphere = previous == WallpaperMode.ATMOSPHERE &&
                mode == WallpaperMode.STATIC &&
                atmosphereTransition.liveness.value

            if (leavingActiveAtmosphere) {
                atmosphereNotice.value = atmosphereTransition.exitAtmosphere().toNotice()
            } else {
                appDataStore.setWallpaperMode(mode)
                // No-op unless the engine is somehow already live (re-entering after an external
                // wallpaper change); the normal entry path renders via the set button instead.
                if (mode == WallpaperMode.ATMOSPHERE) atmosphereTransition.reconcile()
            }
        }
    }

    /** Which exit outcomes are worth a snackbar. This is a presentation judgment */
    private fun AtmosphereExitOutcome.toNotice(): AtmosphereNotice? = when (this) {
        AtmosphereExitOutcome.REPLACED -> null // User asked for this outcome so we don't notice anything
        AtmosphereExitOutcome.CLEARED -> AtmosphereNotice.EXIT_CLEARED
        AtmosphereExitOutcome.FAILED -> AtmosphereNotice.EXIT_FAILED
    }

    /** Clears the notice once the UI has shown the snackbar. */
    override fun clearAtmosphereNotice() {
        atmosphereNotice.value = null
    }

    override fun setKeepLocalCopies(enabled: Boolean) {
        viewModelScope.launch { appDataStore.setKeepLocalCopies(enabled) }
    }

    override fun refreshMediaAccess() {
        mediaAccess.value = snapshotMediaAccess()
    }

    /**
     * Re-checks whether the engine is live and brings the world into line with it.
     *
     * Engine liveness has no system callback so the Route calls this on every resume (as with media access)
     */
    override fun refreshAtmosphereEngineActive() {
        viewModelScope.launch {
            atmosphereTransition.reconcile()
            if (lockRemovalPending) {
                lockRemovalPending = false
                // Raised whether or not they went through with it as the wallpaper is gone either way.
                atmosphereNotice.value = AtmosphereNotice.LOCK_WALLPAPER_REMOVED
            }
        }
    }

    /**
     * Stages the atmosphere source and takes both screens, returning true once the source is ready
     * for the system live-wallpaper picker.
     *
     * Not part of [SettingsActions]: it's a suspend call the Route awaits before firing the activity
     * intent (mirrors how `onRequestMediaAccess` is a plain Route-level callback).
     */
    suspend fun enterAtmosphere(): Boolean {
        val entry = atmosphereTransition.enterAtmosphere()
        if (entry.lockWallpaperRemoved) lockRemovalPending = true
        return entry.sourceReady
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
