package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
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
                    .weight(1f)
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

                ToggleRow(
                    title = "AUTOSTART ON BOOT",
                    subtitle = "RESUME SERVICE AFTER REBOOT",
                    checked = uiState.startOnBoot,
                    onCheckedChange = onStartOnBootChange
                )

                //TODO: add what to do with battery save mode, stop the app, pause it, ignore it?

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
                    .padding(bottom = 32.dp),
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

@Composable
private fun ScreenOffDelayField(
    currentDelayMs: Long,
    onDelayChange: (Long) -> Unit
) {
    var text by remember(currentDelayMs) { mutableStateOf(currentDelayMs.toString()) }

    Column {
        Text(
            text = "SCREEN OFF DELAY",
            style = MaterialTheme.typography.bodySmall,
            color = NothingWhite,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = "DELAY FOR SCREEN OFF ANIMATION - 250ms BY DEFAULT",
            style = MaterialTheme.typography.labelSmall,
            color = NothingWhite.copy(alpha = 0.4f),
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { newValue ->
                // Only allow digits
                val filtered = newValue.filter { it.isDigit() }
                text = filtered
                filtered.toLongOrNull()?.let { onDelayChange(it) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            suffix = {
                Text(
                    text = "ms",
                    color = NothingWhite.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NothingWhite,
                unfocusedTextColor = NothingWhite,
                cursorColor = NothingWhite,
                focusedBorderColor = NothingWhite,
                unfocusedBorderColor = NothingWhite.copy(alpha = 0.3f),
                focusedContainerColor = NothingGray,
                unfocusedContainerColor = NothingGray
            ),
            modifier = Modifier.width(160.dp)
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = NothingWhite,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite.copy(alpha = 0.4f),
                letterSpacing = 0.5.sp
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = NothingBlack,
                checkedTrackColor = NothingWhite,
                uncheckedThumbColor = NothingWhite,
                uncheckedTrackColor = NothingBlack,
                uncheckedBorderColor = NothingWhite.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun QualitySlider(
    label: String,
    subtitle: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = NothingWhite,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = NothingWhite.copy(alpha = 0.4f),
                    letterSpacing = 0.5.sp
                )
            }

            Text(
                text = "$value%",
                style = MaterialTheme.typography.bodySmall,
                color = NothingWhite,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 75f..100f,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = NothingWhite,
                activeTrackColor = NothingWhite,
                inactiveTrackColor = NothingWhite.copy(alpha = 0.15f)
            )
        )

        // Min/Max labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "75",
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite.copy(alpha = 0.25f)
            )
            Text(
                text = "100",
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite.copy(alpha = 0.25f)
            )
        }
    }
}

// Previews

@Preview(showSystemUi = true, name = "Settings", backgroundColor = 0xFF000000)
@Composable
fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(
            uiState = SettingsUiState(
                screenOffDelayMs = 250,
                startOnBoot = true,
                compressionQualityHigh = 95,
                compressionQualityLow = 80,
                appVersion = "0.2.2-beta"
            ),
            onBackClick = {},
            onScreenOffDelayChange = {},
            onStartOnBootChange = {},
            onCompressionQualityHighChange = {},
            onCompressionQualityLowChange = {}
        )
    }
}
