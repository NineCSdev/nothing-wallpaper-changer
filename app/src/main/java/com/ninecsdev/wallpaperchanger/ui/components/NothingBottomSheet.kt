package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import kotlinx.coroutines.launch

private val SheetCornerRadius = 28.dp

/**
 * The app's bottom-sheet chrome: black surface, 28dp top corners, a traced top border and the
 * dimmed drag handle. [content] is laid out in a center-aligned column below the handle.
 *
 * Scrim tap, swipe-down and system back all dismiss via [onDismiss], animated by the sheet itself.
 * A sheet closed from *inside* (tapping a choice) has to ask for that animation, which is what
 * the `hideThen` handed to [content] is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NothingBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.(hideThen: (() -> Unit) -> Unit) -> Unit
) {
    val sheetShape = RoundedCornerShape(topStart = SheetCornerRadius, topEnd = SheetCornerRadius)
    val scope = rememberCoroutineScope()
    val isPreview = LocalInspectionMode.current

    // Latches so a second tap during the slide-out cannot run the action twice
    var isHiding by remember { mutableStateOf(false) }
    val hideThen: (() -> Unit) -> Unit = { action ->
        if (isPreview) {
            action()
        } else if (!isHiding) {
            isHiding = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
        }
    }

    // The border is drawn inside the sheet's own content because that outer modifier attaches to
    // the anchored/swipeable layout node before it's offset into position, which made the border
    // render up at the top of the screen instead of tracing the visible sheet
    val topBorderModifier = Modifier.topRoundedBorder(
        strokeWidth = 2.dp,
        color = NothingWhite.copy(alpha = 0.3f),
        cornerRadius = SheetCornerRadius
    )

    if (isPreview) {
        // In Preview, we wrap the content in a simulated BottomSheet because ModalBottomSheet fails to render in the IDE
        Box(
            modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = NothingBlack,
                shape = sheetShape
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = topBorderModifier
                ) {
                    BottomSheetDefaults.DragHandle(color = NothingWhite.copy(alpha = 0.4f))
                    content(hideThen)
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = NothingBlack,
            shape = sheetShape,
            dragHandle = null,
            modifier = modifier
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = topBorderModifier
            ) {
                BottomSheetDefaults.DragHandle(color = NothingWhite.copy(alpha = 0.4f))
                content(hideThen)
            }
        }
    }
}

/**
 * Traces a border along the top edge, rounded top corners, and both sides of a shape matching
 * [RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)], leaving only the bottom
 * undrawn.
 */
private fun Modifier.topRoundedBorder(
    strokeWidth: Dp,
    color: Color,
    cornerRadius: Dp
): Modifier = this.drawWithContent {
    drawContent()
    val strokePx = strokeWidth.toPx()
    val inset = strokePx / 2f
    val radiusPx = (cornerRadius.toPx() - inset).coerceAtLeast(0f)
    val path = Path().apply {
        moveTo(inset, size.height)
        lineTo(inset, radiusPx + inset)
        arcTo(
            rect = Rect(inset, inset, inset + radiusPx * 2, inset + radiusPx * 2),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        lineTo(size.width - inset - radiusPx, inset)
        arcTo(
            rect = Rect(size.width - inset - radiusPx * 2, inset, size.width - inset, inset + radiusPx * 2),
            startAngleDegrees = 270f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        lineTo(size.width - inset, size.height)
    }
    drawPath(path = path, color = color, style = Stroke(width = strokePx, cap = StrokeCap.Round))
}
