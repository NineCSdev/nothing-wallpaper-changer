package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.app.LocaleManager
import android.content.Context
import android.net.Uri
import android.os.LocaleList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.backup.BackupExporter
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereExitOutcome
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereTransition
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import com.ninecsdev.wallpaperchanger.logic.AppCache
import com.ninecsdev.wallpaperchanger.logic.DiagnosticReport
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appDataStore: AppDataStore,
    private val repository: WallpaperRepository,
    private val imageInternalizer: ImageInternalizer,
    private val appCache: AppCache,
    private val backupExporter: BackupExporter,
    private val wallpaperSources: WallpaperSources,
    private val atmosphereTransition: AtmosphereTransition,
    private val wallpaperModeResolver: WallpaperModeResolver,
    private val diagnosticReport: DiagnosticReport,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SettingsActions {

    private val appVersion: String = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: ""
    } catch (_: Exception) { "" }

    // App locale lives in the system per-app locale store, not AppDataStore.
    private val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager
    private val availableLanguages: List<LanguageOption> = buildLanguageList(context)
    private val selectedLanguageTag = MutableStateFlow(currentLanguageTag())

    // `combine` takes at most 5 flows, so the settings are folded in two stages through small data classes.
    private data class LockscreenSettings(
        val batterySaverPolicy: BatterySaverPolicy,
        val wallpaperZoomFix: WallpaperZoomFix,
        val wallpaperDestination: WallpaperDestination,
        val rotationPolicy: RotationPolicy,
        val skipOnDnd: Boolean
    )

    private data class AtmosphereSettings(
        val mode: WallpaperMode,
        val engineActive: Boolean?,
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
        appDataStore.rotationPolicyFlow(),
        appDataStore.skipOnDndFlow(),
        ::LockscreenSettings
    )

    // Permission state has no system callback: snapshot here, refreshed by the Route on every resume.
    private val mediaAccess = MutableStateFlow(snapshotMediaAccess())

    private fun snapshotMediaAccess(): Pair<Boolean, Boolean> =
        wallpaperSources.hasMediaAccess() to wallpaperSources.hasPartialMediaAccess()

    // No replay: a notice raised while the screen is off-view is dropped.
    // TODO tests: see vault note tests/One-Shot Event Delivery Tests
    private val _notices = MutableSharedFlow<AtmosphereNotice>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val notices: SharedFlow<AtmosphereNotice> = _notices.asSharedFlow()

    // Held back because entry hands off to the system picker; raised on return instead.
    private var lockRemovalPending = false

    private val atmosphereFlow = combine(
        appDataStore.wallpaperModeFlow(),
        atmosphereTransition.liveness,
        atmosphereTransition.canEnter,
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

    // Null until every settings flow has emitted, so no default flashes or animates to the persisted value.
    // TODO tests: see vault note tests/ui-state-loading.md
    private val settingsState: Flow<SettingsUiState> = combine(
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
            skipOnDnd = bundle.lockscreen.skipOnDnd,
            wallpaperZoomFix = bundle.lockscreen.wallpaperZoomFix,
            wallpaperDestination = bundle.lockscreen.wallpaperDestination,
            rotationPolicy = bundle.lockscreen.rotationPolicy,
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
    }

    // Merged into the state after it has its own initial value, never folded into the combine above
    private val storage = MutableStateFlow<StorageBreakdown?>(null)
    private val clearingCache = MutableStateFlow(false)

    init {
        viewModelScope.launch { loadStorage() }
    }

    private suspend fun loadStorage() {
        storage.value = StorageBreakdown(
            // Defaults aren't counted as they are always internalized (no matter user setting)
            localCopies = imageInternalizer.getStorageUsage(repository.getDefaultOnlyFileUris()),
            cacheBytes = appCache.sizeBytes(),
            device = appCache.deviceStorage()
        )
    }

    // Watched, not driven: the export itself is started and canceled from the Backup screen
    val uiState: StateFlow<SettingsUiState?> =
        combine(
            settingsState,
            storage,
            clearingCache,
            backupExporter.state
        ) { state, storage, clearing, export ->
            state.copy(storage = storage, isClearingCache = clearing, backupExport = export)
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = null
            )

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

    override fun setSkipOnDnd(skip: Boolean) {
        viewModelScope.launch { appDataStore.setSkipOnDnd(skip) }
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
            atmosphereTransition.reconcile()
        }
    }

    /**
     * Entering only records a desire; the engine is live once the user confirms in the system picker.
     * Leaving a live engine rolls the write back if the live wallpaper cannot be replaced.
     */
    override fun setWallpaperMode(mode: WallpaperMode) {
        viewModelScope.launch {
            // The resolver, not the rendered liveness snapshot
            val leavingActiveAtmosphere = mode == WallpaperMode.STATIC && wallpaperModeResolver.effectiveMode() == WallpaperMode.ATMOSPHERE

            if (leavingActiveAtmosphere) {
                atmosphereTransition.exitAtmosphere().toNotice()?.let { _notices.tryEmit(it) }
            } else {
                appDataStore.setWallpaperMode(mode)
                // No-op unless the engine is somehow already live (re-entering after an external
                // wallpaper change); the normal entry path renders via the set button instead.
                if (mode == WallpaperMode.ATMOSPHERE) atmosphereTransition.reconcile()
            }
        }
    }

    private fun AtmosphereExitOutcome.toNotice(): AtmosphereNotice? = when (this) {
        AtmosphereExitOutcome.REPLACED -> null // User asked for this outcome so we don't notice anything
        AtmosphereExitOutcome.CLEARED -> AtmosphereNotice.EXIT_CLEARED
        AtmosphereExitOutcome.FAILED -> AtmosphereNotice.EXIT_FAILED
    }

    override fun setKeepLocalCopies(enabled: Boolean) {
        viewModelScope.launch { appDataStore.setKeepLocalCopies(enabled) }
    }

    override fun clearCache() {
        if (clearingCache.value) return
        clearingCache.value = true
        viewModelScope.launch {
            try {
                appCache.clear()
                loadStorage()
            } finally {
                clearingCache.value = false
            }
        }
    }

    override fun refreshMediaAccess() {
        mediaAccess.value = snapshotMediaAccess()
    }

    /** The engine has no liveness callback, so the Route calls this on every start. */
    override fun refreshAtmosphereEngineActive() {
        viewModelScope.launch {
            atmosphereTransition.reconcile()
            if (lockRemovalPending) {
                lockRemovalPending = false
                // Raised whether or not they went through with it as the wallpaper is gone either way.
                _notices.tryEmit(AtmosphereNotice.LOCK_WALLPAPER_REMOVED)
            }
        }
    }

    /** Stages the atmosphere source; returns whether the live-wallpaper picker has one. */
    suspend fun enterAtmosphere(): Boolean {
        val entry = atmosphereTransition.enterAtmosphere()
        if (entry.lockWallpaperRemoved) lockRemovalPending = true
        return entry.sourceReady
    }

    /** Returns whether the report reached [target]. */
    suspend fun saveDiagnostics(target: Uri): Boolean = diagnosticReport.write(target)

    /**
     * Applies [tag] (empty = follow the system) optimistically; the system recreates the Activity,
     * and the next instance re-seeds [selectedLanguageTag] from [currentLanguageTag].
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

    private fun currentLanguageTag(): String {
        val locales = localeManager?.applicationLocales
        return if (locales == null || locales.isEmpty) "" else locales.get(0)?.toLanguageTag() ?: ""
    }

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
