package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/** The gap between two settings rows, and the only vertical number a caller gets to assume. */
private val RowGap = 24.dp

/**
 * A labeled group of settings rows, separated from the group above it by a rule.
 *
 * The section owns its own divider so the screen body cannot forget one: the first section on a
 * screen passes [showDivider] `false`, everything below leaves the default.
 */
@Composable
internal fun SettingsSection(
    label: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        if (showDivider) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(
                color = NothingWhite.copy(alpha = 0.10f),
                thickness = 1.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        SectionLabel(text = label, alpha = 0.55f)

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(RowGap),
            content = content
        )
    }
}

/**
 * A group of rows nested inside a [SettingsSection], for settings that belong to the section's
 * subject rather than beside it.
 */
@Composable
internal fun SettingsSubsection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        SectionLabel(text = label, alpha = 0.4f)

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(RowGap),
            content = content
        )
    }
}

@Composable
private fun SectionLabel(text: String, alpha: Float) {
    Text(
        text = text,
        style = NothingType.overline,
        color = NothingWhite.copy(alpha = alpha)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SettingsSectionPreview() {
    WallpaperChangerTheme {
        Column(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            SettingsSection(label = "FIRST SECTION", showDivider = false) {
                SettingsRowHeader(title = "ROW ONE", subtitle = "Supporting subtitle text")
                SettingsRowHeader(title = "ROW TWO", subtitle = "Supporting subtitle text")
            }
            SettingsSection(label = "SECOND SECTION") {
                SettingsRowHeader(title = "ROW THREE", subtitle = "Supporting subtitle text")
                SettingsSubsection(label = "NESTED GROUP") {
                    SettingsRowHeader(title = "ROW FOUR", subtitle = "Supporting subtitle text")
                }
            }
        }
    }
}
