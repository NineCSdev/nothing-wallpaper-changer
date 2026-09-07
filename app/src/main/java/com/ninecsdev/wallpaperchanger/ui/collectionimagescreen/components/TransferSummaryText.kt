package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import android.content.Context
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.TransferMode
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.TransferSummary

/**
 * Builds the post-transfer snackbar message for a [TransferSummary].
 *
 * Mirrors its counting rule: "N COPIED/MOVED TO X" counts operations that took effect;
 * a nothing-changed duplicate shows as "N ALREADY THERE".
 */
fun transferSummaryText(context: Context, summary: TransferSummary): String {
    val transferredText = when (summary.mode) {
        TransferMode.COPY -> context.getString(R.string.transfer_summary_copied, summary.transferred, summary.targetName.uppercase())
        TransferMode.MOVE -> context.getString(R.string.transfer_summary_moved, summary.transferred, summary.targetName.uppercase())
    }
    val alreadyPresentText = context.getString(R.string.transfer_summary_already_present, summary.alreadyPresent)

    return listOfNotNull(
        transferredText.takeIf { summary.transferred > 0 },
        alreadyPresentText.takeIf { summary.alreadyPresent > 0 }
    ).joinToString(" • ")
}
