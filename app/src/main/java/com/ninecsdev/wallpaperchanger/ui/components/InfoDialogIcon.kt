package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * The small inline info icon that opens a Nothing-styled explanation dialog. It owns the
 * dialog's visibility state, so callers only supply the two strings. Extracted from
 * [SettingsRowHeader] so labels outside settings rows (e.g. the edit-collection card's
 * crop-rule section) can reuse the same teaching mechanism.
 */
@Composable
internal fun InfoDialogIcon(
    dialogTitle: String,
    dialogBody: String,
    modifier: Modifier = Modifier
) {
    var showInfo by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showInfo = true },
        modifier = modifier.size(16.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = stringResource(R.string.cd_setting_information),
            tint = NothingWhite.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp)
        )
    }

    if (showInfo) {
        val dialogShape = AlertDialogDefaults.shape
        AlertDialog(
            onDismissRequest = { showInfo = false },
            modifier = Modifier.border(
                width = 1.dp,
                color = NothingWhite.copy(alpha = 0.2f),
                shape = dialogShape
            ),
            shape = dialogShape,
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(text = stringResource(R.string.settings_info_dialog_ok))
                }
            },
            title = {
                Text(text = dialogTitle)
            },
            text = {
                Text(text = dialogBody)
            },
            containerColor = NothingBlack,
            titleContentColor = NothingWhite,
            textContentColor = NothingWhite.copy(alpha = 0.75f)
        )
    }
}
