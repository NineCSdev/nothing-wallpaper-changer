package com.ninecsdev.wallpaperchanger.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.ninecsdev.wallpaperchanger.model.enums.SourceType

/**
 * Read model: a [Wallpaper] join row with its [WallpaperFile]'s uri, source type and availability
 * folded in; never inserted. [id] is the join-row id, [fileId] the shared file.
 */
data class WallpaperImage(
    val id: Long = 0,
    val collectionId: Long,
    val fileId: Long = 0,
    @ColumnInfo(name = "uri") val uriString: String,
    val sourceType: SourceType = SourceType.INTERNALIZED,
    val isAvailable: Boolean = true,
    @Embedded val editParams: EditParams? = null,
    val isManuallyAdded: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val isDefault: Boolean = false
)
