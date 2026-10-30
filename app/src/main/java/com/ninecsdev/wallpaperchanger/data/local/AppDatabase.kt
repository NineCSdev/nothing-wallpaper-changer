package com.ninecsdev.wallpaperchanger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ninecsdev.wallpaperchanger.model.FolderExclusion
import com.ninecsdev.wallpaperchanger.model.Wallpaper
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperFile

const val DB_SCHEMA_VERSION = 6

@Database(entities = [
    WallpaperCollection::class,
    WallpaperFile::class,
    Wallpaper::class,
    FolderExclusion::class],
    version = DB_SCHEMA_VERSION,
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

        /** v3 → v4: drops `editedUri`. Rebuilds the table because SQLite below API 35 has no `DROP COLUMN`. */
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
         * v4 → v5: splits the flat `wallpapers` table into a `wallpaper_files` registry (one row per
         * distinct uri) plus a join table keyed by `fileId`. `sourceType` is INTERNALIZED for uris under
         * `internal_wallpapers/`, otherwise FOLDER_DOC. A file row's `addedAt` is its earliest membership's.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
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

                // The Favourites row itself is created lazily on the first favourite
                db.execSQL("ALTER TABLE collections ADD COLUMN isFavorites INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE collections ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")

                // Tombstones so in-app deletions survive folder sync; the nullable edit columns snapshot
                // the removed membership's edit for "Restore removed images". Nothing to backfill.
                db.execSQL("""
                    CREATE TABLE folder_exclusions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        collectionId INTEGER NOT NULL,
                        uri TEXT NOT NULL,
                        editZoom REAL,
                        editOffsetX REAL,
                        editOffsetY REAL,
                        excludedAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(collectionId) REFERENCES collections(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX index_folder_exclusions_collectionId ON folder_exclusions(collectionId)")
                db.execSQL("CREATE UNIQUE INDEX index_folder_exclusions_collectionId_uri ON folder_exclusions(collectionId, uri)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collections RENAME COLUMN rotationFrequency TO rotationPolicy")

                // Marks the hidden join row that is the global default wallpaper
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0")

                // Whether this device has ever proven the uri names the image it claims. Existing rows
                // were all read when registered, so they default to verified; only a restore adds a 0.
                db.execSQL("ALTER TABLE wallpaper_files ADD COLUMN isVerified INTEGER NOT NULL DEFAULT 1")

                // Rebuild `collections`: `isFavorites` becomes the nullable `appRole`, and
                // `defaultWallpaperId` (a membership id; null follows the global default) is added.
                // Foreign keys are off during migrations, so dropping this table, which `wallpapers`
                // and `folder_exclusions` reference, does not cascade their rows away.
                db.execSQL("""
                    CREATE TABLE collections_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        rootUri TEXT,
                        defaultCropRule TEXT NOT NULL,
                        rotationPolicy TEXT NOT NULL,
                        lastWallpaperChangeAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL,
                        isPinned INTEGER NOT NULL,
                        appRole TEXT,
                        defaultWallpaperId INTEGER,
                        FOREIGN KEY(defaultWallpaperId) REFERENCES wallpapers(id) ON DELETE SET NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO collections_new (
                        id, name, type, isActive, rootUri, defaultCropRule, rotationPolicy,
                        lastWallpaperChangeAt, createdAt, lastUsedAt, isPinned, appRole, defaultWallpaperId
                    )
                    SELECT id, name, type, isActive, rootUri, defaultCropRule, rotationPolicy,
                           lastWallpaperChangeAt, createdAt, lastUsedAt, isPinned,
                           CASE WHEN isFavorites = 1 THEN 'FAVORITES' END,
                           NULL
                    FROM collections
                """.trimIndent())

                db.execSQL("DROP TABLE collections")
                db.execSQL("ALTER TABLE collections_new RENAME TO collections")
                db.execSQL("CREATE INDEX index_collections_defaultWallpaperId ON collections(defaultWallpaperId)")
            }
        }
    }
}
