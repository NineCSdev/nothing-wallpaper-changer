package com.ninecsdev.wallpaperchanger.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Join row linking a [WallpaperFile] to a [WallpaperCollection]: one membership, read through
 * [WallpaperImage]. Each membership carries its own [editParams]; re-adding the same file to a
 * collection is a no-op. [isDefault] marks the hidden membership that is the global
 * default wallpaper.
 */
@Entity(
    tableName = "wallpapers",
    foreignKeys = [
        ForeignKey(
            entity = WallpaperCollection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WallpaperFile::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("collectionId"),
        Index("fileId"),
        Index(value = ["collectionId", "fileId"], unique = true)
    ]
)
data class Wallpaper(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val collectionId: Long,
    val fileId: Long,
    @Embedded val editParams: EditParams? = null,
    val isManuallyAdded: Boolean = false,
    val isDefault: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Per-membership zoom and normalized pan offsets, applied at render time. Embedded as a nullable
 * unit: all three values are present, or the whole thing is null (no edit).
 */
data class EditParams(
    @ColumnInfo(name = "editZoom") val zoom: Float,
    @ColumnInfo(name = "editOffsetX") val offsetX: Float,
    @ColumnInfo(name = "editOffsetY") val offsetY: Float
)
