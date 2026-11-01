package com.ninecsdev.wallpaperchanger.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/** Where the app's exports are saved by default, so restores look there first too */
private val DOWNLOADS_URI = DocumentsContract.buildDocumentUri(
    "com.android.externalstorage.documents",
    "primary:Download"
)

/** Where folder collections usually live, so folder pickers start there */
val PICTURES_URI: Uri = DocumentsContract.buildDocumentUri(
    "com.android.externalstorage.documents",
    "primary:Pictures"
)

class CreateDocumentInDownloads(mimeType: String) :
    ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS_URI)
}

class OpenDocumentInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS_URI)
}
