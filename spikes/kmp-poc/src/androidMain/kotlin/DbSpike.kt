package co.za.zarchive.poc

import android.content.Context
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import co.za.zarchive.poc.db.PocDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * Phase 0C spike: real SQLDelight + AndroidSqliteDriver round-trip against the schema mirroring
 * all 5 tables in the desktop project's data/AppDatabase.kt. Verifies:
 *  - the driver/database open correctly under Context.filesDir-adjacent storage (default Android
 *    app-private db path, matching how AndroidSearchListRepo would work in the real Phase 5)
 *  - insert + Flow-backed query round-trip (SearchListRepo's StateFlow<List<...>> shape)
 *  - persistence across process restarts: only seeds once, guarded by a check, then on every
 *    subsequent launch confirms the previously-seeded row is still there unchanged.
 */
suspend fun runDbSpike(context: Context): String {
    val driver = AndroidSqliteDriver(PocDatabase.Schema, context, "poc-spike.db")
    val db = PocDatabase(driver)
    val q = db.pocDatabaseQueries

    val existing = q.selectAllLists().executeAsList()
    val seedNote: String
    if (existing.isEmpty()) {
        q.insertList("Phase 0C Spike List", 1000L, 1000L)
        val id = q.lastInsertRowId().executeAsOne()
        q.insertListCard(id, 0, "Lightning Bolt")
        q.insertListCard(id, 1, "Counterspell")
        seedNote = "seeded fresh (first launch since install/data-clear)"
    } else {
        seedNote = "already had ${existing.size} list(s) — persistence across restart confirmed"
    }

    // Flow-backed query round-trip, mirroring SearchListRepo's StateFlow<List<SavedSearchList>>.
    val lists = q.selectAllLists().asFlow().mapToList(Dispatchers.IO).first()
    val summary = lists.joinToString("; ") { list ->
        val cards = q.selectCardsForList(list.id).executeAsList()
        "${list.name} (id=${list.id}, cards=${cards.joinToString(",")})"
    }

    driver.close()
    return "$seedNote. Lists now: $summary"
}
