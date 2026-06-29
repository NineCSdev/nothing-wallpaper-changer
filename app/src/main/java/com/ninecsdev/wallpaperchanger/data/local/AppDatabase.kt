package com.ninecsdev.wallpaperchanger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Main Database for the app.
 * Using Room to persist collections and image metadata.
 * Includes [Migration] objects.
 */
@Database(entities = [
    WallpaperCollection::class,
    WallpaperImage::class],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wallpaperDao(): WallpaperDao

    companion object {
        const val DB_NAME = "smart_wallpaper_database.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collections ADD COLUMN rotationFrequency TEXT NOT NULL DEFAULT 'PER_LOCK'")
                db.execSQL("ALTER TABLE collections ADD COLUMN lastWallpaperChangeAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN editZoom REAL")
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN editOffsetX REAL")
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN editOffsetY REAL")
            }
        }

        /**
         * v3 → v4: Drop the `editedUri` column from the wallpapers table.
         *
         * SQLite versions below API 35 do not support `ALTER TABLE ... DROP COLUMN`,
         * so we use the standard 12-step table rebuild approach:
         * create new table → copy data → drop old table → rename new table → recreate indexes.
         *
         * The edit params (editZoom, editOffsetX, editOffsetY) are preserved.
         * The `editedUri` column is discarded. Files in `edited_wallpapers/` are deleted
         * separately in the AppModule database callback.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE wallpapers_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        collectionId INTEGER NOT NULL,
                        uri TEXT NOT NULL,
                        editZoom REAL,
                        editOffsetX REAL,
                        editOffsetY REAL,
                        isManuallyAdded INTEGER NOT NULL DEFAULT 0,
                        addedAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(collectionId) REFERENCES collections(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO wallpapers_new (id, collectionId, uri, editZoom, editOffsetX, editOffsetY, isManuallyAdded, addedAt)
                    SELECT id, collectionId, uri, editZoom, editOffsetX, editOffsetY, isManuallyAdded, addedAt
                    FROM wallpapers
                """.trimIndent())

                db.execSQL("DROP TABLE wallpapers")
                db.execSQL("ALTER TABLE wallpapers_new RENAME TO wallpapers")
                db.execSQL("CREATE INDEX index_wallpapers_collectionId ON wallpapers(collectionId)")
            }
        }
    }
}
