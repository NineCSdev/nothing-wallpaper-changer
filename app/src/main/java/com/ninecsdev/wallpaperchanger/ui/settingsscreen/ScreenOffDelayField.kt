package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.local.DeviceDefaults
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
internal fun ScreenOffDelayField(
    currentDelayMs: Long,
    onDelayChange: (Long) -> Unit
) {
    var text by remember(currentDelayMs) { mutableStateOf(currentDelayMs.toString()) }

    Column {
        Text(
            text = stringResource(R.string.settings_screen_off_delay_title),
            style = MaterialTheme.typography.bodySmall,
            color = NothingWhite,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = stringResource(
                R.string.settings_screen_off_delay_subtitle,
                DeviceDefaults.forThisDevice()
            ),
            style = MaterialTheme.typography.labelSmall,
            color = NothingWhite.copy(alpha = 0.4f),
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { newValue ->
                // Only allow digits
                val filtered = newValue.filter { it.isDigit() }
                text = filtered
                filtered.toLongOrNull()?.let { onDelayChange(it) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            suffix = {
                Text(
                    text = stringResource(R.string.settings_screen_off_delay_unit),
                    color = NothingWhite.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NothingWhite,
                unfocusedTextColor = NothingWhite,
                cursorColor = NothingWhite,
                focusedBorderColor = NothingWhite,
                unfocusedBorderColor = NothingWhite.copy(alpha = 0.3f),
                focusedContainerColor = NothingGray,
                unfocusedContainerColor = NothingGray
            ),
            modifier = Modifier.width(160.dp)
        )
    }
}

@Preview
@Composable
fun ScreenOffDelayFieldPreview() {
    var delay by remember { mutableStateOf(250L) }
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
