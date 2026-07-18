package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.TransferMode
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.TransferSummary

/**
 * Shows the one-shot transfer [summary] in [hostState] and reports back via [onShown] so the
 * owning ViewModel can clear it. A no-op while [summary] is null.
 *
 * Mirrors the counting rule of [com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.TransferSummary]: "N COPIED/MOVED TO X" counts operations that
 * took effect; a nothing-changed duplicate shows as "N ALREADY THERE".
 */
@Composable
fun TransferSummarySnackbarEffect(
    summary: TransferSummary?,
    hostState: SnackbarHostState,
    onShown: () -> Unit
) {
    if (summary == null) return

    val transferredText = when (summary.mode) {
        TransferMode.COPY -> stringResource(R.string.transfer_summary_copied, summary.transferred, summary.targetName.uppercase())
        TransferMode.MOVE -> stringResource(R.string.transfer_summary_moved, summary.transferred, summary.targetName.uppercase())
    }

    val alreadyPresentText = stringResource(R.string.transfer_summary_already_present, summary.alreadyPresent)
    val message = listOfNotNull(
        transferredText.takeIf { summary.transferred > 0 },
        alreadyPresentText.takeIf { summary.alreadyPresent > 0 }
    ).joinToString(" • ")

    LaunchedEffect(summary) {
        if (message.isNotBlank()) hostState.showSnackbar(message)
        onShown()
    }
}
