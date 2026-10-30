package com.ninecsdev.wallpaperchanger.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tombstone for a folder-sourced image the user removed from a folder collection, so sync never
 * re-adds it. Keyed by the document uri: renaming the file makes it a new image, a different file
 * recreated at the same path stays excluded. Removing a manually-added member leaves none.
 *
 * [editParams] snapshots the removed membership's framing for "Restore removed images". Rows clear
 * on restore, on re-adding the same uri (a uri is never both excluded and a member), or on the
 * collection-delete cascade; otherwise they persist.
 */
@Entity(
    tableName = "folder_exclusions",
    foreignKeys = [
        ForeignKey(
            entity = WallpaperCollection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("collectionId"),
        Index(value = ["collectionId", "uri"], unique = true)
    ]
)
data class FolderExclusion(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val collectionId: Long,
    @ColumnInfo(name = "uri") val uriString: String,
    @Embedded val editParams: EditParams? = null,
    val excludedAt: Long = System.currentTimeMillis()
)
