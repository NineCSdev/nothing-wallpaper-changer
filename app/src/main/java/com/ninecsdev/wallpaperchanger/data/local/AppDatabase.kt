package com.ninecsdev.wallpaperchanger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ninecsdev.wallpaperchanger.model.Wallpaper
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperFile

/**
 * Main Database for the app.
 * Using Room to persist collections and image metadata.
 * Includes [Migration] objects.
 */
@Database(entities = [
    WallpaperCollection::class,
    WallpaperFile::class,
    Wallpaper::class],
    version = 5,
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

        /**
         * v4 → v5: split the flat `wallpapers` table into an M:N model.
         *
         * The old table stored the image `uri` inline on every row. We extract a device-wide
         * `wallpaper_files` registry (one row per distinct uri) and rewrite `wallpapers` into a join
         * table that references a file by `fileId`. A photo shared across collections now maps to a
         * single file row.
         *
         * `sourceType` is derived from the uri: anything under `internal_wallpapers/` is an app-owned
         * copy (INTERNALIZED); everything else is a folder document URI (FOLDER_DOC).
         * Reference-mode manual picks are introduced in a later step.
         *
         * Join rows keep their original ids, edit params, `isManuallyAdded`, and `addedAt`. The file
         * row's `addedAt` is the earliest membership timestamp for that uri.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. File registry, deduped by uri.
                db.execSQL("""
                    CREATE TABLE wallpaper_files (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        uri TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        isAvailable INTEGER NOT NULL DEFAULT 1,
                        addedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX index_wallpaper_files_uri ON wallpaper_files(uri)")

                db.execSQL("""
                    INSERT INTO wallpaper_files (uri, sourceType, isAvailable, addedAt)
                    SELECT uri,
                           CASE WHEN uri LIKE '%/internal_wallpapers/%' THEN 'INTERNALIZED' ELSE 'FOLDER_DOC' END,
                           1,
                           MIN(addedAt)
                    FROM wallpapers
                    GROUP BY uri
                """.trimIndent())

                // 2. Rebuild wallpapers as a join table (uri column replaced by fileId).
                db.execSQL("""
                    CREATE TABLE wallpapers_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        collectionId INTEGER NOT NULL,
                        fileId INTEGER NOT NULL,
                        editZoom REAL,
                        editOffsetX REAL,
                        editOffsetY REAL,
                        isManuallyAdded INTEGER NOT NULL DEFAULT 0,
                        addedAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(collectionId) REFERENCES collections(id) ON DELETE CASCADE,
                        FOREIGN KEY(fileId) REFERENCES wallpaper_files(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO wallpapers_new (id, collectionId, fileId, editZoom, editOffsetX, editOffsetY, isManuallyAdded, addedAt)
                    SELECT w.id, w.collectionId, f.id, w.editZoom, w.editOffsetX, w.editOffsetY, w.isManuallyAdded, w.addedAt
                    FROM wallpapers w JOIN wallpaper_files f ON w.uri = f.uri
                """.trimIndent())

                db.execSQL("DROP TABLE wallpapers")
                db.execSQL("ALTER TABLE wallpapers_new RENAME TO wallpapers")
                db.execSQL("CREATE INDEX index_wallpapers_collectionId ON wallpapers(collectionId)")
                db.execSQL("CREATE INDEX index_wallpapers_fileId ON wallpapers(fileId)")
                db.execSQL("CREATE UNIQUE INDEX index_wallpapers_collectionId_fileId ON wallpapers(collectionId, fileId)")

                // 3. `isFavorites` flag on collections, marking the app-owned Favourites collection.
                // Existing collections are all user-owned so the default is 0; the Favourites row
                // itself is created lazily by the repository on the first favourite.
                db.execSQL("ALTER TABLE collections ADD COLUMN isFavorites INTEGER NOT NULL DEFAULT 0")

                // 4. User-set `isPinned` flag (pinned collections sort first). Folded into this
                // migration like isFavorites. the lazily-created Favourites row is born pinned.
                db.execSQL("ALTER TABLE collections ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
