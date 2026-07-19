package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * The shared "bold all-caps title + dimmed subtitle" header used by settings rows and the
 * main-screen cards (toggle rows, selectors, sliders, the language picker, etc.).
 *
 * When [infoDialogTitle] and [infoDialogBody] are provided, a small [InfoDialogIcon] renders
 * inline after the title and opens a Nothing-styled explanation dialog; callers only supply
 * the two strings.
 */
@Composable
internal fun SettingsRowHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    infoDialogTitle: String? = null,
    infoDialogBody: String? = null
) {
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
            if (infoDialogTitle != null && infoDialogBody != null) {
                InfoDialogIcon(
                    dialogTitle = infoDialogTitle,
                    dialogBody = infoDialogBody
                )
            }
        }
        Text(
            text = subtitle,
            style = NothingType.caption,
            color = NothingWhite.copy(alpha = 0.4f)
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
