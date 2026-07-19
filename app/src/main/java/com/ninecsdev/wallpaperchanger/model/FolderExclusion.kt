package com.ninecsdev.wallpaperchanger.model

import android.net.Uri
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tombstone recording that the user removed (deleted or moved out) a *folder-sourced* image from a
 * folder collection, so sync never re-adds it. A folder collection's membership is "whatever is in
 * the folder, minus these exclusions, plus manually added members".
 *
 * Keyed by the folder-document [uri]: renaming the file on disk makes it a new image (it reappears),
 * a different file recreated at the same path stays excluded. Only folder-sourced members leave exclusions
 * as removing a manually-added member is plain membership removal.
 *
 * [editParams] snapshots the removed membership's edit so "Restore removed images" can bring the
 * image back with its framing intact. Rows are cleared by restore, by re-adding the same uri
 * (invariant: a uri is never both excluded and a member), or by the collection-delete cascade
 * otherwise they persist indefinitely.
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
    val uri: Uri,
    @Embedded val editParams: EditParams? = null,
    val excludedAt: Long = System.currentTimeMillis()
)
