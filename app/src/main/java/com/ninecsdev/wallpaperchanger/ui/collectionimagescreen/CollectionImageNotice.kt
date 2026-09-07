package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import com.ninecsdev.wallpaperchanger.data.source.PickImportResult

/**
 * A word owed to the user after a Collection Image action, delivered once to whoever is showing
 * the screen. Nothing is kept for a screen that is not being shown.
 */
sealed interface CollectionImageNotice {
    /** Counts from the last pick import. */
    data class Import(val result: PickImportResult) : CollectionImageNotice

    /** The last re-link attempt failed. */
    data object RelinkFailed : CollectionImageNotice

    /** Counts from the last copy/move. */
    data class Transfer(val summary: TransferSummary) : CollectionImageNotice
}
