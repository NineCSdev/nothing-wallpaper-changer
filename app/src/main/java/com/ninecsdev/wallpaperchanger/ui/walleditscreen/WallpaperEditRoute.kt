package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stateful entry point for the wallpaper edit screen: owns the ViewModel (which reads
 * the wallpaper id from its SavedStateHandle), collects state, and wires it into the
 * stateless [WallpaperEditScreen].
 */
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
