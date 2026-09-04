package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.logic.StorageUsage
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import com.ninecsdev.wallpaperchanger.ui.components.SettingsToggleRow
import com.ninecsdev.wallpaperchanger.ui.components.overlay.NothingSnackbarHost
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.components.AtmosphereModeSection
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.components.LanguageSelector
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.components.LocalCopiesRow
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.components.QualitySlider
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.components.RotationPolicySection
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.components.ScreenOffDelayField
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.components.SettingsSection
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.components.SettingsSegmentedSelector
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.components.SettingsSubsection
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.components.WallpaperZoomFixSelector
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    actions: SettingsActions,
    onBackClick: () -> Unit,
    onRequestMediaAccess: () -> Unit,
    onSetAtmosphere: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // A mode change can owe the user a word: it cleared their wallpaper, it could not leave, or
    // entering cost them their separate lock-screen wallpaper.
    val exitClearedMessage = stringResource(R.string.settings_atmosphere_exit_cleared_snackbar)
    val exitFailedMessage = stringResource(R.string.settings_atmosphere_exit_failed_snackbar)
    val lockRemovedMessage = stringResource(R.string.settings_atmosphere_lock_removed_snackbar)
    LaunchedEffect(uiState.atmosphereNotice) {
        val message = when (uiState.atmosphereNotice) {
            AtmosphereNotice.EXIT_CLEARED -> exitClearedMessage
            AtmosphereNotice.EXIT_FAILED -> exitFailedMessage
            AtmosphereNotice.LOCK_WALLPAPER_REMOVED -> lockRemovedMessage
            null -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            actions.clearAtmosphereNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = NothingType.titleCaps
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = NothingWhite
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NothingBlack,
                    titleContentColor = NothingWhite
                )
            )
        },
        snackbarHost = { NothingSnackbarHost(snackbarHostState) },
        containerColor = NothingBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                SettingsSection(
                    label = stringResource(R.string.settings_section_service),
                    showDivider = false
                ) {
                    RotationPolicySection(
                        policy = uiState.rotationPolicy,
                        onPolicyChange = actions::setRotationPolicy
                    )

                    ScreenOffDelayField(
                        currentDelayMs = uiState.screenOffDelayMs,
                        onDelayChange = actions::setScreenOffDelay
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_autostart_title),
                        subtitle = stringResource(R.string.settings_autostart_subtitle),
                        checked = uiState.startOnBoot,
                        onCheckedChange = actions::setStartOnBoot,
                        infoDialogTitle = stringResource(R.string.settings_autostart_dialog_title),
                        infoDialogBody = stringResource(R.string.settings_autostart_dialog_body)
                    )

                    SettingsSegmentedSelector(
                        title = stringResource(R.string.settings_battery_saver_title),
                        subtitle = stringResource(R.string.settings_battery_saver_subtitle),
                        options = BatterySaverPolicy.entries,
                        selected = uiState.batterySaverPolicy,
                        onOptionChange = actions::setBatterySaverPolicy,
                        optionLabel = { policy ->
                            when (policy) {
                                BatterySaverPolicy.STOP -> stringResource(R.string.settings_battery_stop)
                                BatterySaverPolicy.PAUSE -> stringResource(R.string.settings_battery_pause)
                                BatterySaverPolicy.IGNORE -> stringResource(R.string.settings_battery_ignore)
                            }
                        },
                        infoDialogTitle = stringResource(R.string.settings_battery_saver_dialog_title),
                        infoDialogBody = stringResource(R.string.settings_battery_saver_dialog_body)
                    )
                }

                SettingsSection(label = stringResource(R.string.settings_section_appearance)) {
                    AtmosphereModeSection(
                        selectedMode = uiState.wallpaperMode,
                        engineActive = uiState.atmosphereEngineActive,
                        hasSource = uiState.hasAtmosphereSource,
                        onModeChange = actions::setWallpaperMode,
                        onSetAtmosphere = onSetAtmosphere
                    )

                    SettingsSegmentedSelector(
                        title = stringResource(R.string.settings_destination_title),
                        subtitle = stringResource(
                            if (uiState.isDestinationEnabled) R.string.settings_destination_subtitle
                            else R.string.settings_destination_subtitle_atmosphere
                        ),
                        options = WallpaperDestination.entries,
                        selected = uiState.wallpaperDestination,
                        onOptionChange = actions::setWallpaperDestination,
                        optionLabel = { destination ->
                            when (destination) {
                                WallpaperDestination.LOCK -> stringResource(R.string.settings_destination_lock)
                                WallpaperDestination.HOME -> stringResource(R.string.settings_destination_home)
                                WallpaperDestination.BOTH -> stringResource(R.string.settings_destination_both)
                            }
                        },
                        infoDialogTitle = stringResource(R.string.settings_destination_dialog_title),
                        infoDialogBody = stringResource(R.string.settings_destination_dialog_body),
                        // Destination only governs static delivery; greyed while atmosphere is desired.
                        enabled = uiState.isDestinationEnabled
                    )

                    WallpaperZoomFixSelector(
                        selected = uiState.wallpaperZoomFix,
                        onZoomFixChange = actions::setWallpaperZoomFix
                    )
                }

                SettingsSection(label = stringResource(R.string.settings_section_storage)) {
                    LocalCopiesRow(
                        checked = uiState.effectiveKeepLocalCopies,
                        hasMediaAccess = uiState.hasMediaAccess,
                        hasPartialMediaAccess = uiState.hasPartialMediaAccess,
                        usage = uiState.storageUsage,
                        onCheckedChange = actions::setKeepLocalCopies,
                        onRequestMediaAccess = onRequestMediaAccess
                    )

                    SettingsSubsection(label = stringResource(R.string.settings_section_quality)) {
                        QualitySlider(
                            label = stringResource(R.string.settings_quality_high_label),
                            subtitle = stringResource(R.string.settings_quality_high_subtitle),
                            value = uiState.compressionQualityHigh,
                            onValueChange = actions::setCompressionQualityHigh,
                            infoDialogTitle = stringResource(R.string.settings_quality_high_dialog_title),
                            infoDialogBody = stringResource(R.string.settings_quality_high_dialog_body)
                        )

                        QualitySlider(
                            label = stringResource(R.string.settings_quality_low_label),
                            subtitle = stringResource(R.string.settings_quality_low_subtitle),
                            value = uiState.compressionQualityLow,
                            onValueChange = actions::setCompressionQualityLow,
                            infoDialogTitle = stringResource(R.string.settings_quality_low_dialog_title),
                            infoDialogBody = stringResource(R.string.settings_quality_low_dialog_body)
                        )
                    }
                }

                SettingsSection(label = stringResource(R.string.settings_section_app)) {
                    LanguageSelector(
                        languages = uiState.availableLanguages,
                        selectedTag = uiState.selectedLanguageTag,
                        onLanguageSelected = actions::setAppLanguage
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // App version footer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "v${uiState.appVersion}",
                    style = NothingType.metaLabel,
                    color = NothingWhite.copy(alpha = 0.25f)
                )
            }
        }
    }
}

// Previews

@Preview(showSystemUi = true, name = "Settings", backgroundColor = 0xFF000000, device = "spec:width=411dp,height=1350dp,dpi=420")
@Composable
fun SettingsScreenPreview() {
    WallpaperChangerTheme {
        SettingsScreen(
            uiState = SettingsUiState(
                screenOffDelayMs = 250,
                rotationPolicy = RotationPolicy.PerLock,
                startOnBoot = true,
                batterySaverPolicy = BatterySaverPolicy.PAUSE,
                wallpaperZoomFix = WallpaperZoomFix.BLURRED,
                compressionQualityHigh = 95,
                compressionQualityLow = 80,
                availableLanguages = listOf(
                    LanguageOption("", "System default", null),
                    LanguageOption("en", "English", "English"),
                    LanguageOption("es", "Español", "Spanish")
                ),
                selectedLanguageTag = "",
                appVersion = "0.3.3-beta",
                storageUsage = StorageUsage(totalBytes = 148_897_792, fileCount = 87)
            ),
            actions = PreviewSettingsActions,
            onBackClick = {},
            onRequestMediaAccess = {},
            onSetAtmosphere = {}
        )
    }
}

/** No-op actions for previews. */
private object PreviewSettingsActions : SettingsActions {
    override fun setScreenOffDelay(delayMs: Long) {}
    override fun setRotationPolicy(policy: RotationPolicy) {}
    override fun setStartOnBoot(enabled: Boolean) {}
    override fun setBatterySaverPolicy(policy: BatterySaverPolicy) {}
    override fun setWallpaperDestination(destination: WallpaperDestination) {}
    override fun setWallpaperMode(mode: WallpaperMode) {}
    override fun setWallpaperZoomFix(zoomFix: WallpaperZoomFix) {}
    override fun setCompressionQualityHigh(quality: Int) {}
    override fun setCompressionQualityLow(quality: Int) {}
    override fun setKeepLocalCopies(enabled: Boolean) {}
    override fun setAppLanguage(tag: String) {}
    override fun refreshMediaAccess() {}
    override fun refreshAtmosphereEngineActive() {}
    override fun clearAtmosphereNotice() {}
}
