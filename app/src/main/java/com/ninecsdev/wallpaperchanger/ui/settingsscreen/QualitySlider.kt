package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
internal fun QualitySlider(
    label: String,
    subtitle: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = NothingWhite,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = NothingWhite.copy(alpha = 0.4f),
                    letterSpacing = 0.5.sp
                )
            }

            Text(
                text = "$value%",
                style = MaterialTheme.typography.bodySmall,
                color = NothingWhite,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 75f..100f,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = NothingWhite,
                activeTrackColor = NothingWhite,
                inactiveTrackColor = NothingWhite.copy(alpha = 0.15f)
            )
        )

        // Min/Max labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "75",
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite.copy(alpha = 0.25f)
            )
            Text(
                text = "100",
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite.copy(alpha = 0.25f)
            )
        }
    }
}

@Preview
@Composable
fun QualitySliderPreview() {
    var quality by remember { mutableStateOf(85) }
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
