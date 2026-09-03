package com.ninecsdev.wallpaperchanger.ui.walleditscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.SmallCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

// Wide enough for "100.0" and "5.00" at caption size
private val ValueFieldWidth = 48.dp
private val ValueFieldHeight = 24.dp

/** Alpha for a row whose axis has nowhere to pan, so it reads as present but inert. */
private const val InertAlpha = 0.35f

/**
 * Bottom panel with precision sliders and action buttons.
 * Has a dark gradient background so it's readable over any wallpaper.
 *
 * Each row's value is a text field, so a value can be dragged or typed exactly.
 * [canPanX]/[canPanY] report whether the image overflows that axis at the current
 * zoom making the row go inert.
 */
@Composable
internal fun ControlsPanel(
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    canPanX: Boolean,
    canPanY: Boolean,
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
            // The panel rides above the soft keyboard as the fields it hosts are why the keyboard is open
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SliderRow(
            label = stringResource(R.string.edit_controls_zoom),
            value = zoom,
            valueRange = 1f..5f,
            text = formatZoom(zoom),
            decimals = ZoomDecimals,
            onValueChange = onZoomChange,
            onTextChange = { parseZoomInput(it)?.let(onZoomChange) }
        )
        SliderRow(
            label = "X",
            value = offsetToPercent(offsetX),
            valueRange = 0f..100f,
            text = formatOffsetPercent(offsetX),
            decimals = OffsetPercentDecimals,
            enabled = canPanX,
            onValueChange = { onOffsetXChange(percentToOffset(it)) },
            onTextChange = { parseOffsetPercentInput(it)?.let(onOffsetXChange) }
        )
        SliderRow(
            label = "Y",
            value = offsetToPercent(offsetY),
            valueRange = 0f..100f,
            text = formatOffsetPercent(offsetY),
            decimals = OffsetPercentDecimals,
            enabled = canPanY,
            onValueChange = { onOffsetYChange(percentToOffset(it)) },
            onTextChange = { parseOffsetPercentInput(it)?.let(onOffsetYChange) }
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
    text: String,
    decimals: Int,
    onValueChange: (Float) -> Unit,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = NothingType.overline,
            color = NothingWhite.copy(alpha = if (enabled) 0.6f else InertAlpha),
            modifier = Modifier.width(48.dp)
        )

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = NothingWhite,
                activeTrackColor = NothingWhite,
                inactiveTrackColor = NothingWhite.copy(alpha = 0.15f),
                disabledThumbColor = NothingWhite.copy(alpha = InertAlpha),
                disabledActiveTrackColor = NothingWhite.copy(alpha = InertAlpha),
                disabledInactiveTrackColor = NothingWhite.copy(alpha = 0.1f)
            )
        )

        ValueField(
            text = text,
            decimals = decimals,
            enabled = enabled,
            onTextChange = onTextChange
        )
    }
}

/**
 * The row's value, readable and editable in the same place. Tapping it opens a numeric keyboard,
 * and every accepted keystroke lands on the preview immediately.
 *
 * The field owns its string only while focused. On focus loss it snaps back to the real value.
 */
@Composable
private fun ValueField(
    text: String,
    decimals: Int,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    var isFocused by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(text) }

    LaunchedEffect(text, isFocused) {
        if (!isFocused) draft = text
    }

    val shape = RoundedCornerShape(SmallCornerRadius)
    Box(
        modifier = modifier
            .width(ValueFieldWidth)
            .height(ValueFieldHeight)
            .clip(shape)
            .background(NothingGray.copy(alpha = if (enabled) 1f else 0.4f))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { raw ->
                val sanitized = sanitizeDecimalInput(raw, decimals)
                draft = sanitized
                // Re-typing the value already in effect is not an edit
                if (sanitized != text) onTextChange(sanitized)
            },
            enabled = enabled,
            textStyle = NothingType.caption.copy(
                color = NothingWhite.copy(alpha = if (enabled) 0.9f else InertAlpha),
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(NothingWhite),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
        )
    }
}

@Preview(name = "Controls Panel", backgroundColor = 0xFF121212, showBackground = true)
@Composable
private fun ControlsPanelPreview() {
    WallpaperChangerTheme {
        ControlsPanel(
            zoom = 1.5f,
            offsetX = 0.25f,
            offsetY = -0.1f,
            canPanX = true,
            canPanY = true,
            onZoomChange = {},
            onOffsetXChange = {},
            onOffsetYChange = {},
            isSaveEnabled = true,
            onSave = {},
            onCancel = {}
        )
    }
}

@Preview(name = "Controls Panel - X axis inert", backgroundColor = 0xFF121212, showBackground = true)
@Composable
private fun ControlsPanelInertAxisPreview() {
    WallpaperChangerTheme {
        ControlsPanel(
            zoom = 1f,
            offsetX = 0f,
            offsetY = 1f,
            canPanX = false,
            canPanY = true,
            onZoomChange = {},
            onOffsetXChange = {},
            onOffsetYChange = {},
            isSaveEnabled = false,
            onSave = {},
            onCancel = {}
        )
    }
}
