package com.ninecsdev.wallpaperchanger.ui.components.overlay

import android.content.Context
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.source.PickImportResult

/**
 * Builds the post-add snackbar message for a [PickImportResult], omitting any zero-count part
 * (e.g. a fully-referenced batch shows only "N ADDED AS REFERENCES").
 * Takes a [Context] rather than reading resources from composition: the counts only become known
 * inside the coroutine that shows the message.
 */
fun importSummaryText(context: Context, result: PickImportResult): String {
    val parts = listOfNotNull(
        if (result.referenced > 0) context.getString(R.string.import_summary_referenced, result.referenced) else null,
        if (result.internalized > 0) context.getString(R.string.import_summary_internalized, result.internalized) else null,
        if (result.skipped > 0) context.getString(R.string.import_summary_skipped, result.skipped) else null
    )
    return parts.joinToString(" • ")
}
