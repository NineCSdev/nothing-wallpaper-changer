package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun WallpaperEditRoute(onBack: () -> Unit) {
    val viewModel: WallpaperEditViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WallpaperEditScreen(
        uiState = uiState,
        onSave = viewModel::save,
        onReset = viewModel::resetAndExit,
        onBack = onBack
    )
}
