package com.ninecsdev.wallpaperchanger.model

import android.net.Uri
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ninecsdev.wallpaperchanger.model.enums.SourceType

/**
 * Device-wide registry of a single physical image, shared across collections.
 *
 * A [Wallpaper] (join row) references one of these by [id]; many wallpapers across different
 * collections can point at the same file, so a photo added to several collections is stored once.
 * The [uri] is unique: [WallpaperRepository][com.ninecsdev.wallpaperchanger.data.WallpaperRepository]
 * dedups by it (get-or-create), and a file row is deleted only when no [Wallpaper] references it.
 *
 * [sourceType] drives orphan cleanup; [isAvailable] is toggled by rotation self-heal when the bytes
 * become unreadable (source deleted / grant revoked) so the image is excluded from rotation without
 * being destroyed.
 */
@Entity(
    tableName = "wallpaper_files",
    indices = [Index(value = ["uri"], unique = true)]
)
data class WallpaperFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: Uri,
    val sourceType: SourceType,
    val isAvailable: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)
