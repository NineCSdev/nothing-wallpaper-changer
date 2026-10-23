package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * The shared "bold all-caps title + dimmed subtitle" header used by settings rows and the
 * main-screen cards (toggle rows, selectors, sliders, the language picker, etc.).
 *
 * When [infoDialogTitle] and [infoDialogBody] are provided, a small [InfoDialogIcon] renders
 * inline at the end of the title and opens a Nothing-styled explanation dialog; callers only supply
 * the two strings. A null [subtitle] renders the title alone.
 */
private const val INFO_ICON_ID = "info"

@Composable
internal fun SettingsRowHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    infoDialogTitle: String? = null,
    infoDialogBody: String? = null,
    subtitleColor: Color = NothingWhite.copy(alpha = 0.4f)
) {
    Column(modifier = modifier) {
        if (infoDialogTitle != null && infoDialogBody != null) {
            // The icon is part of the text, so a title that wraps carries it at the end of its last line
            Text(
                text = buildAnnotatedString {
                    append(title)
                    // Word joiner, so the icon never wraps onto a line of its own
                    append("⁠")
                    appendInlineContent(INFO_ICON_ID)
                },
                inlineContent = mapOf(
                    INFO_ICON_ID to InlineTextContent(
                        Placeholder(width = 20.sp, height = 16.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                            InfoDialogIcon(dialogTitle = infoDialogTitle, dialogBody = infoDialogBody)
                        }
                    }
                ),
                style = NothingType.rowTitle,
                color = NothingWhite
            )
        } else {
            Text(
                text = title,
                style = NothingType.rowTitle,
                color = NothingWhite
            )
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = NothingType.caption,
                color = subtitleColor
            )
        }
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
