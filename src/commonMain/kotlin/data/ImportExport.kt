package data

import kotlinx.serialization.Serializable

/**
 * File-based import/export wire format for saved lists and saved result snapshots. Deliberately a
 * separate shape from [sync.SyncedList]/[sync.SyncedResult]/[sync.SyncBlob] (the Google Drive sync
 * wire format) even though the fields largely overlap: this format has no tombstone
 * (deleted/deletedAt) fields, since a user-facing export file has no concept of a deleted record,
 * and keeping it separate means a future change to the Drive sync schema can't silently change the
 * on-disk export format.
 */
@Serializable
data class ExportedList(
    val syncId: String,
    val name: String,
    val cards: List<String>,
    val updatedAt: Long,
)

@Serializable
data class ExportedResult(
    val syncId: String,
    val name: String,
    val description: String,
    val savedAt: Long,
    val cards: List<String>,
    val results: List<SearchResult>,
    val excludedCards: Set<String> = emptySet(),
    val uncheckedLines: Set<String> = emptySet(),
    val pinnedListings: Map<String, String> = emptyMap(),
)

/** The single JSON file written by an export and read back by an import. */
@Serializable
data class ExportBundle(
    val schemaVersion: Int = 1,
    val exportedAt: Long = 0L,
    val lists: List<ExportedList> = emptyList(),
    val results: List<ExportedResult> = emptyList(),
)

/** How to resolve an import item whose syncId already matches a local record. */
enum class ImportAction { MERGE, REPLACE, COPY }

data class ListImportConflict(val local: SavedSearchList, val incoming: ExportedList)
data class ResultImportConflict(val local: SavedResultEntry, val incoming: ExportedResult)

/**
 * The outcome of matching an [ExportBundle] against the current local state, by [ExportedList.syncId]
 * / [ExportedResult.syncId] -- mirrors how `sync.SyncEngine`/`applyRemote` identify "the same record"
 * across copies, since a local auto-increment id can't survive a round trip through a file. Items with
 * no local match are plain new inserts; items whose syncId matches a local record need the user to
 * pick [ImportAction] (merge/replace/copy) since the file and the local DB have each moved on.
 */
data class ImportPlan(
    val newLists: List<ExportedList>,
    val listConflicts: List<ListImportConflict>,
    val newResults: List<ExportedResult>,
    val resultConflicts: List<ResultImportConflict>,
) {
    val hasConflicts: Boolean get() = listConflicts.isNotEmpty() || resultConflicts.isNotEmpty()
    val isEmpty: Boolean get() = newLists.isEmpty() && listConflicts.isEmpty() && newResults.isEmpty() && resultConflicts.isEmpty()
}

fun buildImportPlan(
    bundle: ExportBundle,
    localLists: List<SavedSearchList>,
    localResults: List<SavedResultEntry>,
): ImportPlan {
    val listsBySyncId = localLists.filter { it.syncId != null }.associateBy { it.syncId }
    val newLists = mutableListOf<ExportedList>()
    val listConflicts = mutableListOf<ListImportConflict>()
    bundle.lists.forEach { incoming ->
        val local = listsBySyncId[incoming.syncId]
        if (local != null) listConflicts += ListImportConflict(local, incoming) else newLists += incoming
    }

    val resultsBySyncId = localResults.filter { it.syncId != null }.associateBy { it.syncId }
    val newResults = mutableListOf<ExportedResult>()
    val resultConflicts = mutableListOf<ResultImportConflict>()
    bundle.results.forEach { incoming ->
        val local = resultsBySyncId[incoming.syncId]
        if (local != null) resultConflicts += ResultImportConflict(local, incoming) else newResults += incoming
    }

    return ImportPlan(newLists, listConflicts, newResults, resultConflicts)
}

/** Union of two card-name lists, deduped case-insensitively, local order first, first-seen casing kept. */
fun mergeCardLists(local: List<String>, incoming: List<String>): List<String> {
    val seen = LinkedHashMap<String, String>()
    (local + incoming).forEach { card -> seen.putIfAbsent(card.trim().lowercase(), card.trim()) }
    return seen.values.toList()
}

data class MergedResultData(
    val cards: List<String>,
    val results: List<SearchResult>,
    val excludedCards: Set<String>,
    val uncheckedLines: Set<String>,
    val pinnedListings: Map<String, String>,
)

/** Combines a local saved-result snapshot with an incoming imported one into a single merged snapshot. */
fun mergeResultData(
    localCards: List<String>,
    incomingCards: List<String>,
    localResults: List<SearchResult>,
    incomingResults: List<SearchResult>,
    localExcluded: Set<String>,
    incomingExcluded: Set<String>,
    localUnchecked: Set<String>,
    incomingUnchecked: Set<String>,
    localPinned: Map<String, String>,
    incomingPinned: Map<String, String>,
): MergedResultData {
    val mergedResults = LinkedHashMap<String, SearchResult>()
    (localResults + incomingResults).forEach { r -> mergedResults.putIfAbsent(r.url, r) }
    return MergedResultData(
        cards          = mergeCardLists(localCards, incomingCards),
        results        = mergedResults.values.toList(),
        excludedCards  = localExcluded + incomingExcluded,
        uncheckedLines = localUnchecked + incomingUnchecked,
        pinnedListings = localPinned + incomingPinned,
    )
}
