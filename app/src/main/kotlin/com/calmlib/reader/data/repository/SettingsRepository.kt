package com.calmlib.reader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.calmlib.reader.data.model.ReadingSettings
import com.calmlib.reader.data.model.SortField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsRepository(context: Context) {
    private val store = context.dataStore

    private object Keys {
        val fontSize = floatPreferencesKey("font_size")
        val lineSpacing = floatPreferencesKey("line_spacing")
        val marginH = intPreferencesKey("margin_h")
        val marginV = intPreferencesKey("margin_v")
        val fontFamily = stringPreferencesKey("font_family")
        val contrastBoost = booleanPreferencesKey("contrast_boost")
        val boldMode = booleanPreferencesKey("bold_mode")
        val antiAliasing = booleanPreferencesKey("anti_aliasing")
        val autoRefresh = intPreferencesKey("auto_refresh")
        val sortField = stringPreferencesKey("sort_field")
        val shelfView = booleanPreferencesKey("shelf_view")
        val firstRun = booleanPreferencesKey("first_run_complete")
        val coversRepaired = booleanPreferencesKey("covers_repaired_v1")
        val pinnedBooks = stringPreferencesKey("pinned_books")
        val justify = booleanPreferencesKey("justify")
        val hyphenation = booleanPreferencesKey("hyphenation")
        val bionic = booleanPreferencesKey("bionic")
        val lastAutoBackup = longPreferencesKey("last_auto_backup")
        val formatFilter = stringPreferencesKey("format_filter")
        val ignoredScanPaths = stringSetPreferencesKey("ignored_scan_paths")
    }

    /** Defaults for books with no settings of their own: what "Use for new
     *  books" saved, else the built-in defaults. PDF zoom and trim stay per book. */
    val readingSettings: Flow<ReadingSettings> = store.data.map { prefs ->
        val d = ReadingSettings()
        ReadingSettings(
            fontSize = prefs[Keys.fontSize] ?: d.fontSize,
            lineSpacing = prefs[Keys.lineSpacing] ?: d.lineSpacing,
            marginHorizontal = prefs[Keys.marginH] ?: d.marginHorizontal,
            marginVertical = prefs[Keys.marginV] ?: d.marginVertical,
            fontFamily = prefs[Keys.fontFamily] ?: d.fontFamily,
            contrastBoost = prefs[Keys.contrastBoost] ?: d.contrastBoost,
            boldMode = prefs[Keys.boldMode] ?: d.boldMode,
            antiAliasing = prefs[Keys.antiAliasing] ?: d.antiAliasing,
            autoRefreshInterval = prefs[Keys.autoRefresh] ?: d.autoRefreshInterval,
            justify = prefs[Keys.justify] ?: d.justify,
            hyphenation = prefs[Keys.hyphenation] ?: d.hyphenation,
            bionicReading = prefs[Keys.bionic] ?: d.bionicReading,
        )
    }

    val lastAutoBackup: Flow<Long> = store.data.map { it[Keys.lastAutoBackup] ?: 0L }
    suspend fun setLastAutoBackup(ts: Long) {
        store.edit { it[Keys.lastAutoBackup] = ts }
    }

    private val knownFormats = setOf("EPUB", "PDF", "TXT", "FB2")

    /**
     * Format filters as a SET so the user can show e.g. EPUB and PDF together.
     * Stored comma-separated; empty = show everything. Values from the old
     * single-choice era ("ALL") are filtered out by the knownFormats check.
     */
    val formatFilters: Flow<Set<String>> = store.data.map { prefs ->
        (prefs[Keys.formatFilter] ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it in knownFormats }
            .toSet()
    }

    suspend fun setFormatFilters(filters: Set<String>) {
        store.edit { it[Keys.formatFilter] = filters.filter { f -> f in knownFormats }.joinToString(",") }
    }

    /**
     * Files the user deliberately removed from the library. The scanner skips
     * these so the quiet auto-rescan doesn't resurrect a deleted book on the
     * next resume. Cleared by Reset Library.
     */
    val ignoredScanPaths: Flow<Set<String>> = store.data.map { it[Keys.ignoredScanPaths] ?: emptySet() }

    suspend fun addIgnoredScanPaths(paths: Collection<String>) {
        store.edit { prefs ->
            prefs[Keys.ignoredScanPaths] = (prefs[Keys.ignoredScanPaths] ?: emptySet()) + paths
        }
    }

    suspend fun clearIgnoredScanPaths() {
        store.edit { it.remove(Keys.ignoredScanPaths) }
    }

    val sortField: Flow<SortField> = store.data.map { prefs ->
        try { SortField.valueOf(prefs[Keys.sortField] ?: "TITLE") } catch (_: Exception) { SortField.TITLE }
    }

    suspend fun updateReadingSettings(settings: ReadingSettings) {
        store.edit { prefs ->
            prefs[Keys.fontSize] = settings.fontSize
            prefs[Keys.lineSpacing] = settings.lineSpacing
            prefs[Keys.marginH] = settings.marginHorizontal
            prefs[Keys.marginV] = settings.marginVertical
            prefs[Keys.fontFamily] = settings.fontFamily
            prefs[Keys.contrastBoost] = settings.contrastBoost
            prefs[Keys.boldMode] = settings.boldMode
            prefs[Keys.antiAliasing] = settings.antiAliasing
            prefs[Keys.autoRefresh] = settings.autoRefreshInterval
            prefs[Keys.justify] = settings.justify
            prefs[Keys.hyphenation] = settings.hyphenation
            prefs[Keys.bionic] = settings.bionicReading
        }
    }

    suspend fun setSortField(field: SortField) {
        store.edit { it[Keys.sortField] = field.name }
    }

    val firstRunComplete: Flow<Boolean> = store.data.map { it[Keys.firstRun] ?: false }

    /** Books the reader has put on their own shelf, in the order they pinned them. */
    val pinnedBooks: Flow<List<Long>> = store.data.map { prefs ->
        (prefs[Keys.pinnedBooks] ?: "").split(',').mapNotNull { it.trim().toLongOrNull() }
    }
    suspend fun setPinnedBooks(ids: List<Long>) {
        store.edit { it[Keys.pinnedBooks] = ids.joinToString(",") }
    }

    suspend fun coversRepaired(): Boolean = store.data.first()[Keys.coversRepaired] ?: false
    suspend fun setCoversRepaired() { store.edit { it[Keys.coversRepaired] = true } }

    suspend fun setFirstRunComplete(complete: Boolean) {
        store.edit { it[Keys.firstRun] = complete }
    }
}
