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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.logic.StorageUsage
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import com.ninecsdev.wallpaperchanger.ui.components.SettingsToggleRow
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
    storageUsage: StorageUsage?,
    actions: SettingsActions,
    onBackClick: () -> Unit
) {
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

                SectionLabel(stringResource(R.string.settings_section_service))

                Spacer(modifier = Modifier.height(16.dp))

                ScreenOffDelayField(
                    currentDelayMs = uiState.screenOffDelayMs,
                    onDelayChange = actions::setScreenOffDelay
                )

                Spacer(modifier = Modifier.height(24.dp))

                SettingsToggleRow(
                    title = stringResource(R.string.settings_autostart_title),
                    subtitle = stringResource(R.string.settings_autostart_subtitle),
                    checked = uiState.startOnBoot,
                    onCheckedChange = actions::setStartOnBoot
                )

                Spacer(modifier = Modifier.height(24.dp))

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
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                SettingsSegmentedSelector(
                    title = stringResource(R.string.settings_destination_title),
                    subtitle = stringResource(R.string.settings_destination_subtitle),
                    options = WallpaperDestination.entries,
                    selected = uiState.wallpaperDestination,
                    onOptionChange = actions::setWallpaperDestination,
                    optionLabel = { destination ->
                        when (destination) {
                            WallpaperDestination.LOCK -> stringResource(R.string.settings_destination_lock)
                            WallpaperDestination.HOME -> stringResource(R.string.settings_destination_home)
                            WallpaperDestination.BOTH -> stringResource(R.string.settings_destination_both)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                WallpaperZoomFixSelector(
                    selected = uiState.wallpaperZoomFix,
                    onZoomFixChange = actions::setWallpaperZoomFix
                )

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(
                    color = NothingWhite.copy(alpha = 0.10f),
                    thickness = 1.dp
                )

                Spacer(modifier = Modifier.height(16.dp))

                SectionLabel(stringResource(R.string.settings_section_general))

                Spacer(modifier = Modifier.height(16.dp))

                LanguageSelector(
                    languages = uiState.availableLanguages,
                    selectedTag = uiState.selectedLanguageTag,
                    onLanguageSelected = actions::setAppLanguage
                )

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(
                    color = NothingWhite.copy(alpha = 0.10f),
                    thickness = 1.dp
                )

                Spacer(modifier = Modifier.height(16.dp))

                SectionLabel(stringResource(R.string.settings_section_storage))

                Spacer(modifier = Modifier.height(16.dp))

                SettingsToggleRow(
                    title = stringResource(R.string.settings_keep_local_copies_title),
                    subtitle = stringResource(R.string.settings_keep_local_copies_subtitle),
                    checked = uiState.keepLocalCopies,
                    onCheckedChange = actions::setKeepLocalCopies
                )

                StorageUsageRow(usage = storageUsage)

                Spacer(modifier = Modifier.height(16.dp))

                SectionLabel(stringResource(R.string.settings_section_quality))

                Spacer(modifier = Modifier.height(16.dp))

                QualitySlider(
                    label = stringResource(R.string.settings_quality_high_label),
                    subtitle = stringResource(R.string.settings_quality_high_subtitle),
                    value = uiState.compressionQualityHigh,
                    onValueChange = actions::setCompressionQualityHigh
                )

                Spacer(modifier = Modifier.height(20.dp))

                QualitySlider(
                    label = stringResource(R.string.settings_quality_low_label),
                    subtitle = stringResource(R.string.settings_quality_low_subtitle),
                    value = uiState.compressionQualityLow,
                    onValueChange = actions::setCompressionQualityLow
                )

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

// Private components

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = NothingType.overline,
        color = NothingWhite.copy(alpha = 0.4f)
    )
}

// Previews

@Preview(showSystemUi = true, name = "Settings", backgroundColor = 0xFF000000, device = "spec:width=411dp,height=1210dp,dpi=420")
@Composable
fun SettingsScreenPreview() {
    WallpaperChangerTheme {
        SettingsScreen(
            uiState = SettingsUiState(
                screenOffDelayMs = 250,
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
                appVersion = "0.3.3-beta"
            ),
            storageUsage = StorageUsage(totalBytes = 148_897_792, fileCount = 87),
            actions = PreviewSettingsActions,
            onBackClick = {}
        )
    }
}

/** No-op actions for previews. */
private object PreviewSettingsActions : SettingsActions {
    override fun setScreenOffDelay(delayMs: Long) {}
    override fun setStartOnBoot(enabled: Boolean) {}
    override fun setBatterySaverPolicy(policy: BatterySaverPolicy) {}
    override fun setWallpaperDestination(destination: WallpaperDestination) {}
    override fun setWallpaperZoomFix(zoomFix: WallpaperZoomFix) {}
    override fun setCompressionQualityHigh(quality: Int) {}
    override fun setCompressionQualityLow(quality: Int) {}
    override fun setKeepLocalCopies(enabled: Boolean) {}
    override fun setAppLanguage(tag: String) {}
}
