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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.model.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.LockscreenZoomFix
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onScreenOffDelayChange: (Long) -> Unit,
    onStartOnBootChange: (Boolean) -> Unit,
    onBatterySaverPolicyChange: (BatterySaverPolicy) -> Unit,
    onLockscreenZoomFixChange: (LockscreenZoomFix) -> Unit,
    onCompressionQualityHighChange: (Int) -> Unit,
    onCompressionQualityLowChange: (Int) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SETTINGS",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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

                SectionLabel("SERVICE")

                Spacer(modifier = Modifier.height(16.dp))

                ScreenOffDelayField(
                    currentDelayMs = uiState.screenOffDelayMs,
                    onDelayChange = onScreenOffDelayChange
                )

                Spacer(modifier = Modifier.height(24.dp))

                BootToggle(
                    checked = uiState.startOnBoot,
                    onCheckedChange = onStartOnBootChange
                )

                Spacer(modifier = Modifier.height(24.dp))

                SettingsSegmentedSelector(
                    title = "BATTERY SAVER POLICY",
                    subtitle = "WHAT TO DO WHEN BATTERY SAVER ACTIVATES",
                    options = BatterySaverPolicy.entries,
                    selected = uiState.batterySaverPolicy,
                    onOptionChange = onBatterySaverPolicyChange,
                    optionLabel = { policy ->
                        when (policy) {
                            BatterySaverPolicy.STOP -> "STOP"
                            BatterySaverPolicy.PAUSE -> "PAUSE"
                            BatterySaverPolicy.IGNORE -> "IGNORE"
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                LockscreenZoomFixSelector(
                    selected = uiState.lockscreenZoomFix,
                    onZoomFixChange = onLockscreenZoomFixChange
                )

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(
                    color = NothingWhite.copy(alpha = 0.10f),
                    thickness = 1.dp
                )

                Spacer(modifier = Modifier.height(16.dp))

                SectionLabel("STORAGE IMAGE QUALITY")

                Spacer(modifier = Modifier.height(16.dp))

                QualitySlider(
                    label = "STANDARD QUALITY",
                    subtitle = "FOR MOST IMAGES - RECOMMENDED 95%",
                    value = uiState.compressionQualityHigh,
                    onValueChange = onCompressionQualityHighChange
                )

                Spacer(modifier = Modifier.height(20.dp))

                QualitySlider(
                    label = "REDUCED QUALITY",
                    subtitle = "FOR HEAVY IMAGES - RECOMMENDED 80%",
                    value = uiState.compressionQualityLow,
                    onValueChange = onCompressionQualityLowChange
                )
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
                    style = MaterialTheme.typography.labelSmall,
                    color = NothingWhite.copy(alpha = 0.25f),
                    letterSpacing = 1.sp
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
        style = MaterialTheme.typography.labelSmall,
        color = NothingWhite.copy(alpha = 0.4f),
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold
    )
}

// Previews

@Preview(showSystemUi = true, name = "Settings", backgroundColor = 0xFF000000, device = "spec:width=411dp,height=1010dp,dpi=420")
@Composable
fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(
            uiState = SettingsUiState(
                screenOffDelayMs = 250,
                startOnBoot = true,
                batterySaverPolicy = BatterySaverPolicy.PAUSE,
                lockscreenZoomFix = LockscreenZoomFix.BLURRED,
                compressionQualityHigh = 95,
                compressionQualityLow = 80,
                appVersion = "0.3.0-beta"
            ),
            onBackClick = {},
            onScreenOffDelayChange = {},
            onStartOnBootChange = {},
            onBatterySaverPolicyChange = {},
            onLockscreenZoomFixChange = {},
            onCompressionQualityHighChange = {},
            onCompressionQualityLowChange = {}
        )
    }
}
