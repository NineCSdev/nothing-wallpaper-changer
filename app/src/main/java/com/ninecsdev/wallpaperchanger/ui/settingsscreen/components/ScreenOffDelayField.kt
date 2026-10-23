package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.local.DeviceDefaults
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.SmallCornerRadius

/** Wide enough for [MaxDigits] digits; the unit sits outside the box. */
private val FieldWidth = 58.dp
private const val MaxDigits = 4

@Composable
internal fun ScreenOffDelayField(
    currentDelayMs: Long,
    onDelayChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(currentDelayMs) { mutableStateOf(currentDelayMs.toString()) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowHeader(
            title = stringResource(R.string.settings_screen_off_delay_title),
            subtitle = stringResource(R.string.settings_screen_off_delay_subtitle),
            modifier = Modifier.weight(1f),
            infoDialogTitle = stringResource(R.string.settings_screen_off_delay_dialog_title),
            infoDialogBody = stringResource(
                R.string.settings_screen_off_delay_dialog_body,
                DeviceDefaults.forThisDevice()
            )
        )

        Spacer(modifier = Modifier.width(16.dp))

        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()

        BasicTextField(
            value = text,
            onValueChange = { newValue ->
                // Only allow digits
                val filtered = newValue.filter { it.isDigit() }.take(MaxDigits)
                text = filtered
                filtered.toLongOrNull()?.let { onDelayChange(it) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = NothingWhite,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End
            ),
            cursorBrush = SolidColor(NothingWhite),
            interactionSource = interactionSource,
            modifier = Modifier
                .width(FieldWidth)
                .border(
                    width = 1.dp,
                    color = NothingWhite.copy(alpha = if (focused) 1f else 0.25f),
                    shape = RoundedCornerShape(SmallCornerRadius)
                )
                .padding(horizontal = 10.dp, vertical = 10.dp)
        )

        Text(
            text = stringResource(R.string.settings_screen_off_delay_unit),
            style = NothingType.caption,
            color = NothingWhite.copy(alpha = 0.4f),
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Preview
@Composable
fun ScreenOffDelayFieldPreview() {
    var delay by remember { mutableLongStateOf(250L) }
    Box(
        modifier = Modifier
            .background(NothingBlack)
            .padding(16.dp)
    ) {
        ScreenOffDelayField(
            currentDelayMs = delay,
            onDelayChange = { delay = it }
        )
    }
}
