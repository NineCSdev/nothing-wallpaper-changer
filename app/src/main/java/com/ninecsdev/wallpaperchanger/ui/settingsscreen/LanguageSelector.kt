package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Stateless language-selection dropdown for the Settings screen.
 *
 * The supported [languages], the [selectedTag], and locale application are all owned by
 * [SettingsViewModel].
 */
@Composable
internal fun LanguageSelector(
    languages: List<LanguageOption>,
    selectedTag: String,
    onLanguageSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (languages.isEmpty()) return

    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    // Used to make the drop menu size match the select language box
    var buttonSize by remember { mutableStateOf(Size.Zero) }

    val selectedLanguage = languages.find { it.tag == selectedTag } ?: languages.first()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        SettingsRowHeader(
            title = stringResource(R.string.settings_language_title),
            subtitle = stringResource(R.string.settings_language_subtitle),
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier.onGloballyPositioned { coordinates ->
                buttonSize = coordinates.size.toSize()
            }
        ) {
            TextButton(
                onClick = { expanded = true },
                border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.25f)),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = NothingBlack,
                    contentColor = NothingWhite
                ),
                shape = MaterialTheme.shapes.small,
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedLanguage.nativeName,
                        style = NothingType.rowLabel
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = NothingWhite.copy(alpha = 0.5f),
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (expanded) 180f else 0f)
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = NothingBlack,
                border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.15f)),
                modifier = Modifier.width(with(density) { buttonSize.width.toDp() })
            ) {
                languages.forEach { language ->
                    val isSelected = language.tag == selectedTag
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = language.nativeName,
                                    style = NothingType.metaLabel,
                                    color = if (isSelected) NothingWhite else NothingWhite.copy(alpha = 0.6f),
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal
                                )
                                if (language.displayName != null) {
                                    Text(
                                        text = language.displayName,
                                        style = NothingType.caption,
                                        color = NothingWhite.copy(alpha = 0.35f)
                                    )
                                }
                            }
                        },
                        onClick = {
                            onLanguageSelected(language.tag)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
