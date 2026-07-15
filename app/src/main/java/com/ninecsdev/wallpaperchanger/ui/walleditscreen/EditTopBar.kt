package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Top app-bar overlay for the wallpaper editor.
 *
 * Displays the back button, screen title, and contextual action buttons:
 * an undo/reset button (visible only when relevant), a fit-height button,
 * and a settings toggle for the controls panel.
 */
@Composable
internal fun EditTopBar(
    hasSavedEdits: Boolean,
    hasUnsavedChanges: Boolean,
    showControls: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onResetSaved: () -> Unit,
    onToggleControls: () -> Unit,
    onFitHeight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canResetSaved = !hasUnsavedChanges && hasSavedEdits
    val resetEnabled = hasUnsavedChanges || canResetSaved

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        NothingBlack.copy(alpha = 0.7f),
                        Color.Transparent
                    )
                )
            )
            .padding(top = 40.dp, bottom = 24.dp, start = 4.dp, end = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = NothingWhite
                )
            }

            Text(
                text = stringResource(R.string.edit_screen_title),
                style = NothingType.titleCaps,
                color = NothingWhite,
                modifier = Modifier.weight(1f)
            )

            if (resetEnabled) {
                IconButton(
                    onClick = {
                        when {
                            hasUnsavedChanges -> onUndo()
                            canResetSaved -> onResetSaved()
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.icon_undo),
                        contentDescription = when {
                            hasUnsavedChanges -> stringResource(R.string.cd_undo_changes)
                            canResetSaved -> stringResource(R.string.cd_reset_edit)
                            else -> stringResource(R.string.cd_reset_edit)
                        },
                        tint = NothingWhite,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            IconButton(onClick = onFitHeight) {
                Icon(
                    painter = painterResource(R.drawable.icon_fit_height),
                    contentDescription = stringResource(R.string.cd_fit_height),
                    tint = NothingWhite,
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(onClick = onToggleControls) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = if (showControls) stringResource(R.string.cd_hide_controls) else stringResource(R.string.cd_show_controls),
                    tint = if (showControls) NothingWhite else NothingWhite.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Preview(name = "Top Bar – No changes", backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun EditTopBarDefaultPreview() {
    WallpaperChangerTheme {
        EditTopBar(
            hasSavedEdits = false,
            hasUnsavedChanges = false,
            showControls = false,
            onBack = {},
            onUndo = {},
            onResetSaved = {},
            onToggleControls = {},
            onFitHeight = {},
        )
    }
}

@Preview(name = "Top Bar – Unsaved changes", backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun EditTopBarUnsavedPreview() {
    WallpaperChangerTheme {
        EditTopBar(
            hasSavedEdits = false,
            hasUnsavedChanges = true,
            showControls = false,
            onBack = {},
            onUndo = {},
            onResetSaved = {},
            onToggleControls = {},
            onFitHeight = {},
        )
    }
}

@Preview(name = "Top Bar – Reset available", backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun EditTopBarResetPreview() {
    WallpaperChangerTheme {
        EditTopBar(
            hasSavedEdits = true,
            hasUnsavedChanges = false,
            showControls = false,
            onBack = {},
            onUndo = {},
            onResetSaved = {},
            onToggleControls = {},
            onFitHeight = {},
        )
    }
}

@Preview(name = "Top Bar – Controls open", backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun EditTopBarControlsOpenPreview() {
    WallpaperChangerTheme {
        EditTopBar(
            hasSavedEdits = false,
            hasUnsavedChanges = false,
            showControls = true,
            onBack = {},
            onUndo = {},
            onResetSaved = {},
            onToggleControls = {},
            onFitHeight = {},
        )
    }
}
