package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
internal fun <T> SettingsSegmentedSelector(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    onOptionChange: (T) -> Unit,
    optionLabel: @Composable (T) -> String,
    onInfoClick: (() -> Unit)? = null
) {
    Column {
        SettingsRowHeader(
            title = title,
            subtitle = subtitle,
            titleTrailingContent = {
                if (onInfoClick != null) {
                    IconButton(
                        onClick = onInfoClick,
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.cd_setting_information),
                            tint = NothingWhite.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected

                TextButton(
                    onClick = { onOptionChange(option) },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) NothingWhite else NothingWhite.copy(alpha = 0.25f)
                    ),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (isSelected) NothingWhite.copy(alpha = 0.12f)
                        else NothingBlack,
                        contentColor = if (isSelected) NothingWhite
                        else NothingWhite.copy(alpha = 0.5f)
                    ),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor =0xFF000000)
@Composable
fun SettingsSegmentedSelectorPreview() {
    // Example state for the preview
    val options = listOf("Daily", "Weekly", "Monthly")
    val selectedOption = options[0]

    Box(
        modifier = Modifier
            .padding(16.dp)
            .background(NothingBlack)
    ) {
        SettingsSegmentedSelector(
            title = "UPDATE FREQUENCY",
            subtitle = "Choose how often the wallpaper changes automatically",
            options = options,
            selected = selectedOption,
            onOptionChange = {},
            optionLabel = { it.uppercase() }
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Multiple Options", backgroundColor = 0xFF000000)
@Composable
fun SettingsSegmentedSelectorNumbersPreview() {
    val options = listOf(1, 2, 3, 4)

    Box(
        modifier = Modifier
            .padding(16.dp)
            .background(NothingBlack)
    ) {
        SettingsSegmentedSelector(
            title = "COLUMN COUNT",
            subtitle = "Select the number of columns in the grid",
            options = options,
            selected = 3,
            onOptionChange = {},
            optionLabel = { "$it Columns" }
        )
    } }
