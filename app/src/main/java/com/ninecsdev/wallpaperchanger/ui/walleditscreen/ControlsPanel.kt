package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Bottom panel with precision sliders and action buttons.
 * Has a dark gradient background so it's readable over any wallpaper.
 */
@Composable
internal fun ControlsPanel(
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    onZoomChange: (Float) -> Unit,
    onOffsetXChange: (Float) -> Unit,
    onOffsetYChange: (Float) -> Unit,
    isSaveEnabled: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        NothingBlack.copy(alpha = 0.85f),
                        NothingBlack.copy(alpha = 0.95f)
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SliderRow(
            label = stringResource(R.string.edit_controls_zoom),
            value = zoom,
            valueRange = 0.5f..5f,
            displayValue = "%.1fx".format(zoom),
            onValueChange = onZoomChange
        )
        SliderRow(
            label = "X",
            value = offsetX,
            valueRange = -1f..1f,
            displayValue = "%+.2f".format(offsetX),
            onValueChange = onOffsetXChange
        )
        SliderRow(
            label = "Y",
            value = offsetY,
            valueRange = -1f..1f,
            displayValue = "%+.2f".format(offsetY),
            onValueChange = onOffsetYChange
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            NothingButton(
                text = stringResource(R.string.edit_controls_cancel),
                onClick = onCancel,
                variant = NothingButtonVariant.SECONDARY,
                modifier = Modifier.weight(1f)
            )
            NothingButton(
                text = stringResource(R.string.edit_controls_save),
                onClick = onSave,
                enabled = isSaveEnabled,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = NothingWhite.copy(alpha = 0.6f),
            modifier = Modifier.width(48.dp)
        )

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = NothingWhite,
                activeTrackColor = NothingWhite,
                inactiveTrackColor = NothingWhite.copy(alpha = 0.15f)
            )
        )

        Text(
            text = displayValue,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = NothingWhite.copy(alpha = 0.5f),
            modifier = Modifier.width(52.dp),
            maxLines = 1
        )
    }
}

@Preview(name = "Controls Panel", backgroundColor = 0xFF121212, showBackground = true)
@Composable
private fun ControlsPanelPreview() {
    MaterialTheme {
        ControlsPanel(
            zoom = 1.5f,
            offsetX = 0.25f,
            offsetY = -0.1f,
            onZoomChange = {},
            onOffsetXChange = {},
            onOffsetYChange = {},
            isSaveEnabled = true,
            onSave = {},
            onCancel = {}
        )
    }
}