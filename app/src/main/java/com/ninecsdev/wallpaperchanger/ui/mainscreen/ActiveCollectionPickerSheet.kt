package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.isPinned
import com.ninecsdev.wallpaperchanger.model.resolveDisplayName
import com.ninecsdev.wallpaperchanger.ui.components.overlay.CollectionPickerItem
import com.ninecsdev.wallpaperchanger.ui.components.overlay.CollectionPickerSheet
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * The main screen's active-collection picker: wires [CollectionPickerSheetState] into the shared
 * [CollectionPickerSheet]. The currently active collection is marked, tapping any tile picks it
 * immediately, and the zero-collections empty state offers a single escape hatch to the
 * collections screen via [onCreateCollection].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActiveCollectionPickerSheet(
    state: CollectionPickerSheetState,
    activeCollectionId: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    onCreateCollection: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    CollectionPickerSheet(
        title = stringResource(R.string.label_select_collection),
        sheetState = sheetState,
        items = state.collections.map { collection ->
            CollectionPickerItem(
                id = collection.id,
                name = collection.resolveDisplayName(context),
                previewState = state.previewStates[collection.id] ?: CollectionPreviewState(),
                isActive = collection.id == activeCollectionId,
                isPinned = collection.isPinned
            )
        },
        onItemClick = { onPick(it.id) },
        onDismiss = onDismiss,
        emptyContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.collections_empty_title),
                    style = NothingType.caption,
                    color = NothingWhite.copy(alpha = 0.4f),
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        // Animate the sheet closed first, then navigate, so the collections
                        // screen doesn't appear while the sheet is still on screen.
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) onCreateCollection()
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.picker_empty_create),
                        style = NothingType.dialogButton,
                        color = NothingWhite,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    )
}
