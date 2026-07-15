package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.CollectionGridItem
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Destination picker for copying/moving the selected wallpapers into another collection:
 * a 2-column grid of [CollectionGridItem]s (same mosaic cards as the collections screen).
 * Tapping a card runs the transfer — pure membership operation, so no confirmation step, and
 * no explicit cancel button either (scrim tap and system back both dismiss via [onCancel]).
 */
@Composable
fun TransferTargetOverlay(
    mode: TransferMode,
    targets: List<TransferTarget>,
    onTargetSelected: (TransferTarget) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        TransferTargetCard(
            mode = mode,
            targets = targets,
            onTargetSelected = onTargetSelected,
            modifier = modifier.padding(horizontal = 24.dp)
        )
    }
}

/**
 * The dialog's content card, extracted from the [Dialog] window so Compose previews can render it
 */
@Composable
private fun TransferTargetCard(
    mode: TransferMode,
    targets: List<TransferTarget>,
    onTargetSelected: (TransferTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NothingBlack),
        border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = stringResource(
                    if (mode == TransferMode.COPY) R.string.transfer_dialog_title_copy
                        else R.string.transfer_dialog_title_move
                ),
                style = NothingType.titleCaps,
                color = NothingWhite,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (targets.isEmpty()) {
                Text(
                    text = stringResource(R.string.transfer_dialog_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = NothingWhite.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(targets, key = { it.collectionId }) { target ->
                        CollectionGridItem(
                            name = target.name,
                            state = CollectionPreviewState(
                                previewUris = target.previewUris,
                                totalCount = target.imageCount
                            ),
                            onClick = { onTargetSelected(target) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shows the one-shot transfer [summary] in [hostState] and reports back via [onShown] so the
 * owning ViewModel can clear it. A no-op while [summary] is null.
 *
 * Mirrors the counting rule of [TransferSummary]: "N COPIED/MOVED TO X" counts operations that
 * took effect; a nothing-changed duplicate shows as "N ALREADY THERE". Zero-count parts are omitted.
 */
@Composable
fun TransferSummarySnackbarEffect(
    summary: TransferSummary?,
    hostState: SnackbarHostState,
    onShown: () -> Unit
) {
    if (summary == null) return
    val transferredText = when (summary.mode) {
        TransferMode.COPY -> stringResource(
            R.string.transfer_summary_copied, summary.transferred, summary.targetName.uppercase()
        )
        TransferMode.MOVE -> stringResource(
            R.string.transfer_summary_moved, summary.transferred, summary.targetName.uppercase()
        )
    }
    val alreadyPresentText =
        stringResource(R.string.transfer_summary_already_present, summary.alreadyPresent)
    val message = listOfNotNull(
        transferredText.takeIf { summary.transferred > 0 },
        alreadyPresentText.takeIf { summary.alreadyPresent > 0 }
    ).joinToString(" • ")

    LaunchedEffect(summary) {
        if (message.isNotBlank()) hostState.showSnackbar(message)
        onShown()
    }
}

// Previews

@Preview(name = "Transfer Target Card", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun TransferTargetCardPreview() {
    WallpaperChangerTheme {
        TransferTargetCard(
            mode = TransferMode.COPY,
            targets = listOf(
                TransferTarget(1, "Amoled", emptyList(), imageCount = 24),
                TransferTarget(2, "Dark minimal", emptyList(), imageCount = 7),
                TransferTarget(3, "Favorites", emptyList(), imageCount = 3)
            ),
            onTargetSelected = {},
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Preview(name = "Transfer Target Card (empty)", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun TransferTargetCardEmptyPreview() {
    WallpaperChangerTheme {
        TransferTargetCard(
            mode = TransferMode.MOVE,
            targets = emptyList(),
            onTargetSelected = {},
            modifier = Modifier.padding(24.dp)
        )
    }
}
