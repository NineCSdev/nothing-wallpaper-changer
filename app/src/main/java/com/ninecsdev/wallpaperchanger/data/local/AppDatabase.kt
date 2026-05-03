package com.ninecsdev.wallpaperchanger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Main Database for the application.
 * Using Room to persist collections and image metadata.
 */
@Database(entities = [
    WallpaperCollection::class,
    WallpaperImage::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wallpaperDao(): WallpaperDao

    companion object {
        const val DB_NAME = "smart_wallpaper_database.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE collections ADD COLUMN rotationFrequency TEXT NOT NULL DEFAULT 'PER_LOCK'"
                )
                db.execSQL(
                    "ALTER TABLE collections ADD COLUMN lastWallpaperChangeAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
