package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.components.NothingSlider
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
internal fun QualitySlider(
    label: String,
    subtitle: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    infoDialogTitle: String? = null,
    infoDialogBody: String? = null
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            SettingsRowHeader(
                title = label,
                subtitle = subtitle,
                infoDialogTitle = infoDialogTitle,
                infoDialogBody = infoDialogBody
            )

            Text(
                text = "$value%",
                style = NothingType.sliderPercent,
                color = NothingWhite
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RangeLabel(text = "75")

            NothingSlider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = 75f..100f,
                modifier = Modifier.weight(1f)
            )

            RangeLabel(text = "100")
        }
    }
}

@Composable
private fun RangeLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = NothingWhite.copy(alpha = 0.3f)
    )
}

@Preview
@Composable
fun QualitySliderPreview() {
    var quality by remember { mutableIntStateOf(85) }
    Box(
        modifier = Modifier
            .background(NothingBlack)
            .padding(16.dp)
    ) {
        QualitySlider(
            label = "TITLE",
            subtitle = "SUBTITLE",
            value = quality,
            onValueChange = { quality = it }
        )
    }
}
