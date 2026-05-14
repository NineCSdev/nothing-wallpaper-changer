package com.ninecsdev.wallpaperchanger.model

import android.net.Uri
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
    val editedUri: Uri? = null,
    val editZoom: Float? = null,
    val editOffsetX: Float? = null,
    val editOffsetY: Float? = null,
    val isManuallyAdded: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)
