package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.components.NothingDialogCard
import com.ninecsdev.wallpaperchanger.ui.components.NothingTextField
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Name prompt for a collection created from the copy/move sheet. The selection is its content,
 * so a name is all it asks for.
 */
@Composable
internal fun NewCollectionNameDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        NewCollectionNameContent(onConfirm, onDismiss)
    }
}

@Composable
private fun NewCollectionNameContent(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    NothingDialogCard(isProcessing = false, processingMessage = "") {
        Text(
            text = stringResource(R.string.create_collection_title),
            style = NothingType.createDialogTitle,
            color = NothingWhite
        )

        Spacer(modifier = Modifier.height(24.dp))

        NothingTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.create_collection_field_name),
            modifier = Modifier.focusRequester(focusRequester)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NothingButton(
                text = stringResource(R.string.create_collection_action_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                variant = NothingButtonVariant.SECONDARY
            )
            NothingButton(
                text = stringResource(R.string.create_collection_action_create),
                onClick = { onConfirm(name) },
                modifier = Modifier.weight(1f),
                enabled = name.isNotBlank(),
                variant = NothingButtonVariant.PRIMARY
            )
        }
    }
}

@Preview(name = "NewCollectionNameDialog", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun NewCollectionNameDialogPreview() {
    WallpaperChangerTheme {
        NewCollectionNameContent(onConfirm = {}, onDismiss = {})
    }
}
