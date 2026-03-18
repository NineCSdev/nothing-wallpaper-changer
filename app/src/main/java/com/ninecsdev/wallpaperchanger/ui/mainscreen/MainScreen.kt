package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.DelayLabel
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Stateless UI for the main screen.
 * Composed of the status header, collection selection card,
 * default wallpaper card, and service control buttons.
 */
@Composable
fun MainScreen(
    uiState: MainUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSelectFolderClick: () -> Unit,
    onOpenCollectionsClick: () -> Unit,
    onToggleRevert: (Boolean) -> Unit,
    onSelectDefaultClick: () -> Unit,
    onDelaySelected: (DelayLabel) -> Unit
) {
    Scaffold(
        containerColor = NothingBlack,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    ServiceStatusHeader(
                        state = uiState.serviceState,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = onOpenCollectionsClick,
                        modifier = Modifier.offset(y = (-20).dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.icon_grid),
                            contentDescription = "Collections",
                            modifier = Modifier.size(32.dp),
                            tint = NothingWhite
                        )
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))

                WallpaperSelectionCard(
                    activeCollection = uiState.activeCollection,
                    previewImages = uiState.previewImages,
                    totalImages = uiState.activeCollectionSize,
                    onSelectFolderClick = onSelectFolderClick
                )

                Spacer(modifier = Modifier.height(16.dp))

                DefaultWallpaperCard(
                    revertToDefault = uiState.revertToDefaultOnStop,
                    defaultUri = uiState.defaultWallpaperUri,
                    onToggleRevert = onToggleRevert,
                    onSelectDefaultClick = onSelectDefaultClick
                )

                Spacer(modifier = Modifier.height(16.dp))

                ServiceSettingsCard(
                    delayLabel = uiState.delayLabel,
                    onDelaySelected = onDelaySelected
                )

                // Add extra padding at bottom to avoid being covered by buttons
                Spacer(modifier = Modifier.height(120.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .statusBarsPadding()
            ) {
                ServiceControlButtons(
                    isStartEnabled = uiState.isStartEnabled,
                    isStopEnabled = uiState.isStopEnabled,
                    onStartClick = onStartClick,
                    onStopClick = onStopClick
                )
            }
        }
    }
}

@Preview(showSystemUi = true, name = "Main Screen - Running", backgroundColor = 0xFF000000)
@Composable
fun MainScreenRunningPreview() {
    MaterialTheme {
        CompositionLocalProvider(LocalContentColor provides NothingWhite) {
            MainScreen(
                uiState = MainUiState(
                    serviceState = ServiceState.Running,
                    revertToDefaultOnStop = true
                ),
                onStartClick = {},
                onStopClick = {},
                onSelectFolderClick = {},
                onOpenCollectionsClick = {},
                onToggleRevert = {},
                onSelectDefaultClick = {},
                onDelaySelected = {}
            )
        }
    }
}
