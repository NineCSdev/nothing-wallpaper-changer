package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import com.ninecsdev.wallpaperchanger.data.source.PickImportResult

/** A message owed to the user after an action, delivered once; dropped if no screen is showing. */
sealed interface CollectionImageNotice {
    data class Import(val result: PickImportResult) : CollectionImageNotice

    data object RelinkFailed : CollectionImageNotice

    data class Transfer(val summary: TransferSummary) : CollectionImageNotice
}
