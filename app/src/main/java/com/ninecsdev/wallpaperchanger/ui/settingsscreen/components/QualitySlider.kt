package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@OptIn(ExperimentalMaterial3Api::class)
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
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = 75f..100f,
                modifier = Modifier.weight(1f),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(ThumbSize)
                            .background(NothingWhite, RoundedCornerShape(4.dp))
                    )
                },
                track = { state ->
                    val range = state.valueRange
                    val fraction = (state.value - range.start) / (range.endInclusive - range.start)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TrackHeight)
                    ) {
                        val y = size.height / 2
                        val stroke = size.height
                        drawLine(NothingWhite.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                        drawLine(NothingWhite, Offset(0f, y), Offset(size.width * fraction, y), stroke, StrokeCap.Round)
                    }
                }
            )
            RangeLabel(text = "100")
        }
    }
}

private val ThumbSize = 18.dp
private val TrackHeight = 3.dp

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
