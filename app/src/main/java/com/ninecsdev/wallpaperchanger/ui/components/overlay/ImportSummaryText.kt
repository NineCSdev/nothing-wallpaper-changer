package com.ninecsdev.wallpaperchanger.ui.components.overlay

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.source.PickImportResult

/**
 * Shows the one-shot [summary] notice in [hostState] and reports back via [onShown] so the owning
 * ViewModel can clear it from its ui state. A no-op while [summary] is null.
 */
@Composable
fun ImportSummarySnackbarEffect(
    summary: PickImportResult?,
    hostState: SnackbarHostState,
    onShown: () -> Unit
) {
    if (summary == null) return
    val message = importSummaryText(summary)
    LaunchedEffect(summary) {
        if (message.isNotBlank()) hostState.showSnackbar(message)
        onShown()
    }
}

/**
 * Builds the post-add snackbar message for a [PickImportResult], omitting any zero-count part
 * (e.g. a fully-referenced batch shows only "N ADDED AS REFERENCES").
 */
@Composable
private fun importSummaryText(result: PickImportResult): String {
    val parts = listOfNotNull(
        if (result.referenced > 0) stringResource(R.string.import_summary_referenced, result.referenced) else null,
        if (result.internalized > 0) stringResource(R.string.import_summary_internalized, result.internalized) else null,
        if (result.skipped > 0) stringResource(R.string.import_summary_skipped, result.skipped) else null
    )
    return parts.joinToString(" • ")
}
