package com.ninecsdev.wallpaperchanger.model

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a single wallpaper associated with a collection.
 *
 * Linked to [WallpaperCollection] via Foreign Key.
 * If the parent collection is deleted, all its images are removed automatically.
 */
@Entity(
    tableName = "wallpapers",
    foreignKeys = [
        ForeignKey(
            entity = WallpaperCollection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("collectionId")]
)
data class WallpaperImage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val collectionId: Long,
    val uri: Uri,
    @Embedded val editParams: EditParams? = null,
    val isManuallyAdded: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Per-image edit transform (zoom + normalized pan offsets), embedded into [WallpaperImage] and
 * applied on-the-fly by [BufferManager][com.ninecsdev.wallpaperchanger.logic.BufferManager] when rendering the
 * next wallpaper.
 *
 * Embedded as a nullable unit: either all three values are present (the image has an edit) or the
 * whole thing is null (no edit);
 */
data class EditParams(
    @ColumnInfo(name = "editZoom") val zoom: Float,
    @ColumnInfo(name = "editOffsetX") val offsetX: Float,
    @ColumnInfo(name = "editOffsetY") val offsetY: Float
)
