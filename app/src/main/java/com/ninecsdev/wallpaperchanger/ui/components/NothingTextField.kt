package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

@Composable
fun NothingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = NothingWhite),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NothingWhite,
            unfocusedBorderColor = NothingWhite.copy(alpha = 0.2f),
            cursorColor = NothingWhite,
            focusedLabelColor = NothingWhite,
            unfocusedLabelColor = NothingWhite.copy(alpha = 0.5f)
        ),
        singleLine = true
    )
}

@Preview(name = "Nothing TextField")
@Composable
fun NothingTextFieldPreview() {
    WallpaperChangerTheme {
        Box(modifier = Modifier.background(NothingBlack).padding(16.dp)) {
            NothingTextField(
                value = "Amoled Pack",
                onValueChange = {},
                label = "LIST NAME"
            )
        }
    }
}