package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingPanel
import com.ninecsdev.wallpaperchanger.ui.theme.NothingPanelBorder
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/** Space above and below every row, either side of the rule between two rows. */
private val RowPadding = 14.dp

/**
 * A labeled group of settings rows on its own panel, with a rule between each pair of rows.
 *
 * Every top-level composable in [content] is one row, and the panel draws the rules itself so the
 * screen body cannot forget one.
 */
@Composable
internal fun SettingsPanel(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(CardCornerRadius)
    Column(modifier = modifier) {
        Text(
            text = label,
            style = NothingType.overline,
            color = NothingWhite.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        RuledColumn(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(NothingPanel)
                .border(1.dp, NothingPanelBorder, shape)
                .padding(horizontal = 16.dp),
            content = content
        )
    }
}

/**
 * A labeled run of rows inside a [SettingsPanel], for settings that belong to one subject within
 * the panel's. Ruled the same way, so it reads as part of the panel.
 */
@Composable
internal fun SettingsPanelGroup(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = NothingType.overline,
            color = NothingWhite.copy(alpha = 0.4f)
        )

        RuledColumn(
            // The enclosing panel row already pads the group's own edges
            padEdges = false,
            modifier = Modifier.padding(top = 12.dp),
            content = content
        )
    }
}

/**
 * Stacks its children with [RowPadding] either side of a hairline between each pair, and around
 * the outer edges too unless [padEdges] is false. Empty children get neither.
 */
@Composable
private fun RuledColumn(
    modifier: Modifier = Modifier,
    padEdges: Boolean = true,
    content: @Composable () -> Unit
) {
    // Written at placement, read at draw, so the rules follow rows that change height
    var ruleOffsets by remember { mutableStateOf(emptyList<Int>()) }

    Layout(
        content = content,
        modifier = modifier.drawBehind {
            ruleOffsets.forEach { y ->
                val center = y + 0.5f
                drawLine(NothingGray, Offset(0f, center), Offset(size.width, center), strokeWidth = 1.dp.toPx())
            }
        }
    ) { measurables, constraints ->
        val padding = RowPadding.roundToPx()
        val rule = 1.dp.roundToPx()
        val rows = measurables
            .map { it.measure(constraints.copy(minHeight = 0)) }
            .filter { it.height > 0 }
        val edge = if (padEdges) padding else 0
        val gaps = (rows.size - 1).coerceAtLeast(0)
        val height = if (rows.isEmpty()) 0 else rows.sumOf { it.height } + gaps * (2 * padding + rule) + 2 * edge

        layout(constraints.maxWidth, height) {
            val offsets = mutableListOf<Int>()
            var y = edge
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    y += padding
                    offsets += y
                    y += rule + padding
                }
                row.placeRelative(0, y)
                y += row.height
            }
            if (offsets != ruleOffsets) ruleOffsets = offsets
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SettingsPanelPreview() {
    WallpaperChangerTheme {
        Column(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            SettingsPanel(label = "SECTION") {
                SettingsRowHeader(title = "ROW ONE", subtitle = "Supporting subtitle text")
                SettingsRowHeader(title = "ROW TWO", subtitle = "Supporting subtitle text")
                SettingsPanelGroup(label = "NESTED GROUP") {
                    SettingsRowHeader(title = "ROW THREE", subtitle = "Supporting subtitle text")
                    SettingsRowHeader(title = "ROW FOUR", subtitle = "Supporting subtitle text")
                }
            }
        }
    }
}
