package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.ui.components.RotationPolicyEditor
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader

/** The app-wide rotation cadence. Collections follow it unless they carry their own override. */
@Composable
internal fun RotationPolicySection(
    policy: RotationPolicy,
    onPolicyChange: (RotationPolicy) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsRowHeader(
            title = stringResource(R.string.settings_rotation_policy_title),
            subtitle = stringResource(R.string.settings_rotation_policy_subtitle),
            infoDialogTitle = stringResource(R.string.settings_rotation_policy_dialog_title),
            infoDialogBody = stringResource(R.string.settings_rotation_policy_dialog_body)
        )

        Spacer(modifier = Modifier.height(8.dp))

        RotationPolicyEditor(policy = policy, onPolicyChange = onPolicyChange)
    }
}
