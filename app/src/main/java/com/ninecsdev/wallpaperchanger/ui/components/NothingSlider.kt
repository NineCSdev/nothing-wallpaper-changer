package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

private val ThumbSize = 14.dp
private val TrackHeight = 3.dp
private val SliderHeight = 28.dp

/** Slider with a rounded-square thumb on a thin track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NothingSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val activeColor = NothingWhite.copy(alpha = if (enabled) 1f else 0.35f)
    val inactiveColor = NothingWhite.copy(alpha = if (enabled) 0.18f else 0.1f)

    // Material's 48dp minimum would make every slider row that tall; SliderHeight is the touch area instead
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            modifier = modifier.height(SliderHeight),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(ThumbSize)
                        .background(activeColor, RoundedCornerShape(4.dp))
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
                    val thumbX = size.width * fraction
                    val halfThumb = ThumbSize.toPx() / 2
                    // The disabled thumb is translucent, so the track is cut out beneath it rather than covered
                    clipRect(left = thumbX - halfThumb, right = thumbX + halfThumb, clipOp = ClipOp.Difference) {
                        drawLine(inactiveColor, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                        drawLine(activeColor, Offset(0f, y), Offset(thumbX, y), stroke, StrokeCap.Round)
                    }
                }
            }
        )
    }
}

@Preview
@Composable
private fun NothingSliderPreview() {
    var value by remember { mutableFloatStateOf(0.4f) }
    Column(
        modifier = Modifier
            .background(NothingBlack)
            .padding(16.dp)
    ) {
        NothingSlider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)
        NothingSlider(value = value, onValueChange = { value = it }, valueRange = 0f..1f, enabled = false)
    }
}
