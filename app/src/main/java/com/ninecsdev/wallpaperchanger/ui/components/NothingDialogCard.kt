package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ProcessingOverlay
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/** [overlay] is an extra slot for dialogs layered above the card (e.g. the edit card's delete confirmation). */
@Composable
internal fun NothingDialogCard(
    isProcessing: Boolean,
    processingMessage: String,
    modifier: Modifier = Modifier,
    columnContentAlignment: Alignment.Horizontal = Alignment.Start,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.cardColors(containerColor = NothingBlack),
        border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.2f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = columnContentAlignment,
                content = content
            )

            if (isProcessing) {
                ProcessingOverlay(
                    message = processingMessage,
                    modifier = Modifier.matchParentSize()
                )
            }

            overlay()
        }
    }
}
