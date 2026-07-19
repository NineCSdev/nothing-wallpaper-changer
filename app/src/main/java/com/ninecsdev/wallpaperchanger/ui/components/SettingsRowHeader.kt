package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * The shared "bold all-caps title + dimmed subtitle" header used by settings rows and the
 * main-screen cards (toggle rows, selectors, sliders, the language picker, etc.).
 *
 * When [infoDialogTitle] and [infoDialogBody] are provided, a small info icon renders inline
 * after the title and opens a Nothing-styled explanation dialog; the header owns the dialog's
 * visibility state, so callers only supply the two strings.
 */
@Composable
internal fun SettingsRowHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    infoDialogTitle: String? = null,
    infoDialogBody: String? = null
) {
    var showInfo by remember { mutableStateOf(false) }
    val hasInfo = infoDialogTitle != null && infoDialogBody != null

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = NothingType.rowTitle,
                color = NothingWhite
            )
            if (hasInfo) {
                IconButton(
                    onClick = { showInfo = true },
                    modifier = Modifier.size(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.cd_setting_information),
                        tint = NothingWhite.copy(alpha = 0.55f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Text(
            text = subtitle,
            style = NothingType.caption,
            color = NothingWhite.copy(alpha = 0.4f)
        )
    }

    if (showInfo && infoDialogTitle != null && infoDialogBody != null) {
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
                Text(text = infoDialogTitle)
            },
            text = {
                Text(text = infoDialogBody)
            },
            containerColor = NothingBlack,
            titleContentColor = NothingWhite,
            textContentColor = NothingWhite.copy(alpha = 0.75f)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SettingsRowHeaderPreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            SettingsRowHeader(
                title = "ROW TITLE",
                subtitle = "Supporting subtitle text"
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SettingsRowHeaderWithInfoPreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            SettingsRowHeader(
                title = "ROW TITLE",
                subtitle = "Supporting subtitle text",
                infoDialogTitle = "Title",
                infoDialogBody = "Body"
            )
        }
    }
}