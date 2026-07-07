package com.ninecsdev.wallpaperchanger.data.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans a user-granted folder tree for images via SAF ([DocumentsContract]).
 */
@Singleton
class FolderScanner @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) {
    private companion object {
        const val TAG = "FolderScanner"
    }

    /**
     * Scans ONLY (no subfolders) the user-selected folder for images.
     *
     * **Throws on scan failure instead of returning an empty list to
     * avoid giving the impression the folder is empty.** .
     *
     * @param rootFolderUri The top-level folder URI granted by the user.
     * @return The document URIs of the images found in the folder.
     */
    suspend fun scan(rootFolderUri: Uri): List<Uri> {
        return withContext(Dispatchers.IO) {
            val imageList = mutableListOf<Uri>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootFolderUri, DocumentsContract.getTreeDocumentId(rootFolderUri)
            )

            try {
                appContext.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeTypeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val mimeType = cursor.getString(mimeTypeCol)

                        if (mimeType != null && mimeType.startsWith("image/")) {
                            val docId = cursor.getString(idCol)
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(rootFolderUri, docId)
                            imageList.add(docUri)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Folder scan failed, aborting: $e")
                throw e
            }
            Log.d(TAG, "Folder scan found ${imageList.size} valid images.")
            imageList
        }
    }
}
