package com.ninecsdev.wallpaperchanger.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ninecsdev.wallpaperchanger.model.enums.SourceType

/**
 * Device-wide registry of a single physical image, shared across collections.
 *
 * A [Wallpaper] (join row) references one of these by [id]; many wallpapers across different
 * collections can point at the same file, so a photo added to several collections is stored once.
 * The [uriString] is unique: [WallpaperRepository][com.ninecsdev.wallpaperchanger.data.WallpaperRepository]
 * de-duplicates by it (get-or-create), and a file row is deleted only when no [Wallpaper] references it.
 *
 * [sourceType] drives orphan cleanup; [isAvailable] is toggled by rotation self-heal when the bytes
 * become unreadable (source deleted / grant revoked) so the image is excluded from rotation without
 * being destroyed. [isVerified] says whether this row's [uriString] has ever been proven, *on this device*, to name the
 * image it claims. Only a restore can produce an unverified row.
 */
@Entity(
    tableName = "wallpaper_files",
    indices = [Index(value = ["uri"], unique = true)]
)
data class WallpaperFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "uri") val uriString: String,
    val sourceType: SourceType,
    val isAvailable: Boolean = true,
    val isVerified: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)
