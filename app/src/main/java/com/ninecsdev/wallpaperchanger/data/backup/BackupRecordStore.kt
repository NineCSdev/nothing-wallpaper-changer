package com.ninecsdev.wallpaperchanger.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ninecsdev.wallpaperchanger.data.local.appPreferences
import com.ninecsdev.wallpaperchanger.data.local.safeData
import com.ninecsdev.wallpaperchanger.data.local.setOrClear
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_EXPORT_IN_FLIGHT = stringPreferencesKey("backup_export_in_flight")
private val KEY_EXPORT_IN_FLIGHT_NAME = stringPreferencesKey("backup_export_in_flight_name")
private val KEY_IMPORT_COMMITTING = booleanPreferencesKey("backup_import_committing")

/**
 * What a backup is part-way through, persisted so it outlives the process.
 * A still-set [inFlightTarget] on the next run means the export died and its partial archive is untrustworthy;
 * [isCommitting] tells a restore interrupted by process death from one that finished.
 */
@Singleton
class BackupRecordStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore: DataStore<Preferences> = context.appPreferences

    /** Null when no export was left unfinished. */
    suspend fun inFlightTarget(): Uri? = read(KEY_EXPORT_IN_FLIGHT)?.toUri()

    /** The archive's file name, captured while the export could still read it. */
    suspend fun inFlightName(): String? = read(KEY_EXPORT_IN_FLIGHT_NAME)

    /** Written together, so a target can never outlive its name or the other way round. */
    suspend fun setInFlightTarget(target: Uri?, name: String? = null) {
        dataStore.edit { prefs ->
            prefs.setOrClear(KEY_EXPORT_IN_FLIGHT, target?.toString())
            prefs.setOrClear(KEY_EXPORT_IN_FLIGHT_NAME, name.takeIf { target != null })
        }
    }

    /** True from the moment a commit begins destroying, until it finishes or fails. */
    suspend fun isCommitting(): Boolean = dataStore.safeData().first()[KEY_IMPORT_COMMITTING] == true
    suspend fun setCommitting(committing: Boolean) = write(KEY_IMPORT_COMMITTING, true.takeIf { committing })

    private suspend fun read(key: Preferences.Key<String>): String? = dataStore.safeData().first()[key]

    private suspend fun <T> write(key: Preferences.Key<T>, value: T?) {
        dataStore.edit { prefs -> prefs.setOrClear(key, value) }
    }
}
