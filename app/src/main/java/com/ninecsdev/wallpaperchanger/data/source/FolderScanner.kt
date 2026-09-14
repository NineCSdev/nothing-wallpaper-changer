package com.ninecsdev.wallpaperchanger.data.source

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One image found in a folder tree, with the metadata that identifies it */
data class ScannedDocument(
    val uri: String,
    val fingerprint: SourceFingerprint
)

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
     * avoid giving the impression the folder is empty.**
     *
     * @param rootFolderUri The top-level folder URI granted by the user.
     * @return The document URIs of the images found in the folder.
     */
    suspend fun scan(rootFolderUri: String): List<String> = scanDetailed(rootFolderUri).map { it.uri }

    /** [scan] plus each document's [SourceFingerprint]. Used at import/export */
    suspend fun scanDetailed(rootFolderUri: String): List<ScannedDocument> {
        return withContext(Dispatchers.IO) {
            val rootUri = rootFolderUri.toUri()
            val imageList = mutableListOf<ScannedDocument>()
            var hiddenSkipped = 0
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootUri, DocumentsContract.getTreeDocumentId(rootUri)
            )

            try {
                val cursor = appContext.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null,
                    null,
                    null
                ) ?: throw IOException("Folder scan failed: provider returned no cursor for $rootFolderUri")

                cursor.use {
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeTypeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    // Deliberately not OrThrow: a provider that omits display names must cost us the
                    // hidden-document filter, not the whole folder.
                    val displayNameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        val mimeType = cursor.getString(mimeTypeCol)
                        if (mimeType == null || !mimeType.startsWith("image/")) continue

                        val displayName = cursor.stringAt(displayNameCol)
                        if (isHiddenDocumentName(displayName)) {
                            hiddenSkipped++
                            continue
                        }

                        val docId = cursor.getString(idCol)
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                        imageList.add(
                            ScannedDocument(
                                uri = docUri.toString(),
                                fingerprint = SourceFingerprint(
                                    displayName = displayName,
                                    sizeBytes = cursor.longAt(sizeCol),
                                    modifiedAt = cursor.longAt(modifiedCol)
                                )
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Folder scan failed, aborting: $e")
                throw e
            }
            // The skipped count is the only diagnostic a user report will ever give us for this filter.
            Log.d(TAG, "Folder scan found ${imageList.size} valid images, skipped $hiddenSkipped hidden.")
            imageList
        }
    }
}
