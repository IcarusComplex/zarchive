package sync

/**
 * Pure Google Drive sync merge algorithm -- no I/O, no DB, no network, fully unit-testable.
 * Operates generically over [SyncedList]/[SyncedResult] via small extractor lambdas rather than a
 * shared interface, so neither data class needs to change shape for this to work.
 *
 * Matching is always by [Identity.syncId], never by name -- two independently-created items that
 * happen to share a display name are never merged, they remain two separate records forever.
 */
object SyncMerge {
    private const val CLOUD_SUFFIX = " (Cloud)"
    private const val TOMBSTONE_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000 // 90 days

    data class Identity(val syncId: String, val name: String, val updatedAt: Long, val deleted: Boolean)

    /**
     * Merges [local] and [remote] into the single list of records that should become both the new
     * local DB state and the next uploaded blob. Every record present on only one side carries
     * over as-is (only-local: not yet uploaded; only-remote-live: adopted, disambiguated on a name
     * collision; only-remote-tombstone: adopted as a hidden tombstone). Records present on both
     * sides keep whichever has the later [Identity.updatedAt]; if exactly equal, tie-broken by
     * comparing the two candidates' own content (see [pickWinner]) so both devices independently
     * compute the same winner without needing clock sync.
     */
    fun <T> merge(
        local: List<T>,
        remote: List<T>,
        identity: (T) -> Identity,
        withName: (T, String) -> T,
    ): List<T> {
        val localById = local.associateBy { identity(it).syncId }
        val remoteById = remote.associateBy { identity(it).syncId }
        val localNamesByOtherId = local.associate { identity(it).syncId to normalizeName(identity(it).name) }

        val allIds = localById.keys + remoteById.keys
        return allIds.map { id ->
            val l = localById[id]
            val r = remoteById[id]
            when {
                l != null && r == null -> l
                l == null && r != null -> {
                    val incomingName = normalizeName(identity(r).name)
                    val collides = localNamesByOtherId.any { (otherId, otherName) -> otherId != id && otherName == incomingName }
                    if (collides) withName(r, identity(r).name + CLOUD_SUFFIX) else r
                }
                l != null && r != null -> pickWinner(l, r, identity)
                else -> error("unreachable: syncId present in neither map")
            }
        }
    }

    // Note: when both sides are present, they share the same syncId by definition (that's how
    // they were matched) -- so syncId can't be the tie-break here. Falling back to full content
    // (data-class toString()) instead keeps the choice a pure function of (a, b)'s *content*, not
    // which side happened to be passed as "local" -- both devices computing this independently
    // (with their own local/remote roles swapped relative to each other) must land on the same
    // winning content, and only a content-based comparison guarantees that.
    private fun <T> pickWinner(a: T, b: T, identity: (T) -> Identity): T {
        val ia = identity(a)
        val ib = identity(b)
        return when {
            ia.updatedAt != ib.updatedAt -> if (ia.updatedAt > ib.updatedAt) a else b
            else -> if (a.toString() >= b.toString()) a else b
        }
    }

    private fun normalizeName(name: String) = name.trim().lowercase()

    /**
     * Hard-purges tombstones older than 90 days past their delete time -- comfortably longer than
     * any plausible "device was offline" gap for two personal devices, so it can't cause a
     * resurrection, while keeping the synced blob from growing forever.
     */
    fun <T> purgeOldTombstones(records: List<T>, now: Long, identity: (T) -> Identity): List<T> =
        records.filterNot { record ->
            val id = identity(record)
            id.deleted && (now - deletedAtOrUpdatedAt(id)) > TOMBSTONE_MAX_AGE_MS
        }

    // deletedAt isn't part of Identity (only deleted/updatedAt/name/syncId are needed for merge
    // itself); GC uses updatedAt as a stand-in since delete() always bumps it to the same instant.
    private fun deletedAtOrUpdatedAt(identity: Identity) = identity.updatedAt
}
