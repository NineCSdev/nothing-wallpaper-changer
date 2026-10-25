package com.ninecsdev.wallpaperchanger.data.local

import androidx.room.ColumnInfo
import com.ninecsdev.wallpaperchanger.model.enums.SourceType

/**
 * What a collection grid item is drawn from: its oldest few files and its total image count.
 * A projection, not a domain type why it lives here.
 *
 * [previewFiles] is capped by the caller's limit and ordered oldest-first.
 * [imageCount] is the whole collection, so it is routinely larger.
 */
data class CollectionPreview(
    val previewFiles: List<PreviewFile> = emptyList(),
    val imageCount: Int = 0
)

/** One file a collection card shows: enough to find or create its grid thumbnail. */
data class PreviewFile(
    val fileId: Long,
    @ColumnInfo(name = "uri") val uriString: String,
    val sourceType: SourceType
)
