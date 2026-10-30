package com.ninecsdev.wallpaperchanger.data.local

import androidx.room.ColumnInfo
import com.ninecsdev.wallpaperchanger.model.enums.SourceType

/** [previewFiles] is capped by the caller's limit, oldest-first; [imageCount] covers the whole collection. */
data class CollectionPreview(
    val previewFiles: List<PreviewFile> = emptyList(),
    val imageCount: Int = 0
)

data class PreviewFile(
    val fileId: Long,
    @ColumnInfo(name = "uri") val uriString: String,
    val sourceType: SourceType
)
