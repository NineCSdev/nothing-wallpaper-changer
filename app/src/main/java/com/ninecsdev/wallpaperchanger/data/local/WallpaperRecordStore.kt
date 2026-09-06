package com.ninecsdev.wallpaperchanger.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_BUFFERED_WALLPAPER_ID = longPreferencesKey("buffered_wallpaper_id")
private val KEY_BUFFERED_COLLECTION_ID = longPreferencesKey("buffered_collection_id")
private val KEY_ATMOSPHERE_LIVE_WALLPAPER_ID = longPreferencesKey("atmosphere_live_wallpaper_id")
private val KEY_ATMOSPHERE_RENDER_KEY = stringPreferencesKey("atmosphere_render_key")
private val KEY_APPLIED_WALLPAPER_ID = longPreferencesKey("applied_wallpaper_id")

/**
 * What is on screen, and what is queued to be. All fields are absent on a fresh install and
 * after a revert to the default wallpaper.
 */
data class WallpaperRecord(
    /** The membership whose image is on screen as the static wallpaper. */
    val appliedWallpaperId: Long? = null,
    /** The membership whose image the atmosphere engine is animating. */
    val liveAtmosphereWallpaperId: Long? = null,
    /** The render inputs [liveAtmosphereWallpaperId] was produced from (for a reconcile to compare). */
    val atmosphereRenderKey: String? = null,
    /** The membership prepared and waiting to be applied next. */
    val bufferedWallpaperId: Long? = null,
    /** The collection [bufferedWallpaperId] was drawn from (to spot a stale buffer). */
    val bufferedCollectionId: Long? = null
)

/**
 * Persists the [WallpaperRecord]. Separate from [AppDataStore] because settings record what the user
 * chose and this records what happened; both sit on the same preferences file.
 *
 * Reads are whole-record only, by design. Writes stay per-concern.
 */
@Singleton
class WallpaperRecordStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore: DataStore<Preferences> = context.appPreferences

    /** Re-emits only on a real change. */
    val record: Flow<WallpaperRecord> = dataStore.safeData()
        .map { prefs ->
            WallpaperRecord(
                appliedWallpaperId = prefs[KEY_APPLIED_WALLPAPER_ID],
                liveAtmosphereWallpaperId = prefs[KEY_ATMOSPHERE_LIVE_WALLPAPER_ID],
                atmosphereRenderKey = prefs[KEY_ATMOSPHERE_RENDER_KEY],
                bufferedWallpaperId = prefs[KEY_BUFFERED_WALLPAPER_ID],
                bufferedCollectionId = prefs[KEY_BUFFERED_COLLECTION_ID]
            )
        }
        .distinctUntilChanged()

    /** One consistent read of every field. */
    suspend fun snapshot(): WallpaperRecord = record.first()

    /** Written together so the buffer and the collection it came from cannot diverge. */
    suspend fun setBufferedWallpaper(wallpaperId: Long?, collectionId: Long?) {
        dataStore.edit { prefs ->
            prefs.setOrClear(KEY_BUFFERED_WALLPAPER_ID, wallpaperId)
            prefs.setOrClear(KEY_BUFFERED_COLLECTION_ID, collectionId)
        }
    }

    suspend fun setLiveAtmosphereWallpaperId(wallpaperId: Long?) =
        edit(KEY_ATMOSPHERE_LIVE_WALLPAPER_ID, wallpaperId)

    /** Null reads as "nothing delivered". */
    suspend fun setAtmosphereRenderKey(renderKey: String?) =
        edit(KEY_ATMOSPHERE_RENDER_KEY, renderKey)

    /** Null records "nothing of ours", covering a revert to the default wallpaper. */
    suspend fun setAppliedWallpaperId(wallpaperId: Long?) =
        edit(KEY_APPLIED_WALLPAPER_ID, wallpaperId)

    private suspend fun <T> edit(key: Preferences.Key<T>, value: T?) {
        dataStore.edit { prefs -> prefs.setOrClear(key, value) }
    }

    private fun <T> MutablePreferences.setOrClear(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }
}
