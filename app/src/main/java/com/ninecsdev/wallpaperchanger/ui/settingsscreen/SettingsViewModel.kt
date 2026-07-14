package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.logic.StorageUsage
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
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
    private val _selectedLanguageTag = MutableStateFlow(currentLanguageTag())

    // Separate in 2 flows as combine max is 5
    private val lockscreenSettingsFlow = combine(
        appDataStore.batterySaverPolicyFlow(),
        appDataStore.wallpaperZoomFixFlow(),
        appDataStore.wallpaperDestinationFlow()
    ) { batterySaverPolicy, wallpaperZoomFix, wallpaperDestination ->
        Triple(batterySaverPolicy, wallpaperZoomFix, wallpaperDestination)
    }

    // Nested so the outer combine (5-arg max) still has free slots for keepLocalCopies + language.
    private val lockscreenStorageLanguageFlow = combine(
        lockscreenSettingsFlow,
        appDataStore.keepLocalCopiesFlow(),
        _selectedLanguageTag
    ) { lockscreenSettings, keepLocalCopies, languageTag ->
        Triple(lockscreenSettings, keepLocalCopies, languageTag)
    }

    // Null until every settings flow has emitted; the UI renders nothing until then so no
    // fabricated default can flash or animate to the real persisted value.
    // TODO tests: see vault note tests/ui-state-loading.md
    val uiState: StateFlow<SettingsUiState?> = combine(
        appDataStore.screenOffDelayFlow(),
        appDataStore.startOnBootFlow(),
        appDataStore.compressionQualityHighFlow(),
        appDataStore.compressionQualityLowFlow(),
        lockscreenStorageLanguageFlow
    ) { delay, boot, qualityHigh, qualityLow, (lockscreenSettings, keepLocalCopies, languageTag) ->
        SettingsUiState(
            screenOffDelayMs = delay,
            startOnBoot = boot,
            batterySaverPolicy = lockscreenSettings.first,
            wallpaperZoomFix = lockscreenSettings.second,
            wallpaperDestination = lockscreenSettings.third,
            compressionQualityHigh = qualityHigh,
            compressionQualityLow = qualityLow,
            keepLocalCopies = keepLocalCopies,
            availableLanguages = availableLanguages,
            selectedLanguageTag = languageTag,
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
        viewModelScope.launch { appDataStore.setWallpaperZoomFix(zoomFix) }
    }

    override fun setKeepLocalCopies(enabled: Boolean) {
        viewModelScope.launch { appDataStore.setKeepLocalCopies(enabled) }
    }

    /**
     * Applies [tag] as the app locale (empty tag → follow the system locale). Updates the UI
     * optimistically, then hands the change to the system, which recreates the Activity; the
     * next VM instance re-seeds [_selectedLanguageTag] from [currentLanguageTag].
     */
    override fun setAppLanguage(tag: String) {
        _selectedLanguageTag.value = tag
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
