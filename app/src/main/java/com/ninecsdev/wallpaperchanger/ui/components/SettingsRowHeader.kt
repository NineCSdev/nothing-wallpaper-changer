package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * The shared "bold all-caps title + dimmed subtitle" header used by settings rows and the
 * main-screen cards (toggle rows, selectors, sliders, the language picker, etc.).
 *
 * [titleTrailingContent] renders inline after the title (pass e.g. an info IconButton for
 * rows that need one); it's empty (and free) for the common title-only case.
 */
@Composable
internal fun SettingsRowHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    titleTrailingContent: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = NothingWhite,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            titleTrailingContent()
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = NothingWhite.copy(alpha = 0.4f),
            letterSpacing = 0.5.sp
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SettingsRowHeaderPreview() {
    MaterialTheme {
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
