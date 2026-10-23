package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.ui.components.NothingBottomSheet
import com.ninecsdev.wallpaperchanger.ui.components.RotationPickerContent
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.components.rotationSummary
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * The app-wide rotation cadence. Collections follow it unless they carry their own override.
 *
 * The row describes the setting, states the cadence in force on its trailing edge and opens the picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RotationPolicySection(
    policy: RotationPolicy,
    onPolicyChange: (RotationPolicy) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val title = stringResource(R.string.settings_rotation_policy_title)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showPicker = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowHeader(
            title = title,
            subtitle = stringResource(R.string.settings_rotation_policy_subtitle),
            modifier = Modifier.weight(1f),
            infoDialogTitle = stringResource(R.string.settings_rotation_policy_dialog_title),
            infoDialogBody = stringResource(R.string.settings_rotation_policy_dialog_body)
        )

        Text(
            text = rotationSummary(policy).uppercase(LocalConfiguration.current.locales[0]),
            style = NothingType.rowLabel,
            color = NothingWhite,
            textAlign = TextAlign.End,
            modifier = Modifier
                .padding(start = 12.dp)
                .widthIn(max = 140.dp)
        )

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = NothingWhite.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }

    if (showPicker) {
        NothingBottomSheet(onDismiss = { showPicker = false }) { hideThen ->
            RotationPickerContent(
                title = title,
                policy = policy,
                onPolicyChange = onPolicyChange,
                onDone = { hideThen { showPicker = false } },
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
