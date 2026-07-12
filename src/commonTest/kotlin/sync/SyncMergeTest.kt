package sync

import kotlin.test.*

class SyncMergeTest {

    private fun list(
        syncId: String,
        name: String,
        updatedAt: Long,
        deleted: Boolean = false,
        deletedAt: Long? = null,
        cards: List<String> = listOf("Card A"),
    ) = SyncedList(syncId = syncId, name = name, cards = cards, updatedAt = updatedAt, deleted = deleted, deletedAt = deletedAt)

    private fun identity(l: SyncedList) = SyncMerge.Identity(l.syncId, l.name, l.updatedAt, l.deleted)
    private fun withName(l: SyncedList, newName: String) = l.copy(name = newName)

    private fun merge(local: List<SyncedList>, remote: List<SyncedList>) =
        SyncMerge.merge(local, remote, ::identity, ::withName)

    @Test fun `only local carries over unchanged`() {
        val local = listOf(list("a", "Alpha", 100))
        val result = merge(local, emptyList())
        assertEquals(local, result)
    }

    @Test fun `only remote live item is inserted locally`() {
        val remote = listOf(list("a", "Alpha", 100))
        val result = merge(emptyList(), remote)
        assertEquals(remote, result)
    }

    @Test fun `only remote tombstone is still inserted as a hidden tombstone`() {
        val remote = listOf(list("a", "Alpha", 100, deleted = true, deletedAt = 100))
        val result = merge(emptyList(), remote)
        assertEquals(1, result.size)
        assertTrue(result[0].deleted)
    }

    @Test fun `both present -- later updatedAt wins`() {
        val local = listOf(list("a", "Local Name", updatedAt = 200))
        val remote = listOf(list("a", "Remote Name", updatedAt = 300))
        val result = merge(local, remote)
        assertEquals(1, result.size)
        assertEquals("Remote Name", result[0].name)
    }

    @Test fun `both present -- earlier updatedAt loses even if it is local`() {
        val local = listOf(list("a", "Local Name", updatedAt = 500))
        val remote = listOf(list("a", "Remote Name", updatedAt = 300))
        val result = merge(local, remote)
        assertEquals("Local Name", result[0].name)
    }

    @Test fun `both present -- equal timestamps tie-break deterministically by syncId`() {
        val local = listOf(list("aaa", "Local Name", updatedAt = 200))
        val remote = listOf(list("aaa", "Remote Name", updatedAt = 200))
        // Same tie-break rule run from "the other device's" perspective (roles swapped) must agree.
        val forward = merge(local, remote)
        val backward = merge(remote, local)
        assertEquals(forward.single().name, backward.single().name)
    }

    @Test fun `a delete on one device propagates as the winning tombstone`() {
        val local = listOf(list("a", "Alpha", updatedAt = 100))
        val remoteDelete = listOf(list("a", "Alpha", updatedAt = 200, deleted = true, deletedAt = 200))
        val result = merge(local, remoteDelete)
        assertTrue(result.single().deleted)
    }

    @Test fun `independently created same-named lists never merge, incoming gets disambiguated`() {
        val local = listOf(list("local-1", "example1", updatedAt = 100))
        val remote = listOf(list("remote-1", "example1", updatedAt = 100))
        val result = merge(local, remote)
        assertEquals(2, result.size, "both distinct syncIds must survive as separate records")
        val names = result.associateBy { it.syncId }
        assertEquals("example1", names.getValue("local-1").name, "the original local item's name is untouched")
        assertEquals("example1 (Cloud)", names.getValue("remote-1").name, "the incoming copy is disambiguated")
    }

    @Test fun `name collision check is case-insensitive and trims whitespace`() {
        val local = listOf(list("local-1", "  Example1  ", updatedAt = 100))
        val remote = listOf(list("remote-1", "EXAMPLE1", updatedAt = 100))
        val result = merge(local, remote)
        val incoming = result.first { it.syncId == "remote-1" }
        assertEquals("EXAMPLE1 (Cloud)", incoming.name)
    }

    @Test fun `no collision when names differ -- incoming keeps its own name`() {
        val local = listOf(list("local-1", "Alpha", updatedAt = 100))
        val remote = listOf(list("remote-1", "Beta", updatedAt = 100))
        val result = merge(local, remote)
        val incoming = result.first { it.syncId == "remote-1" }
        assertEquals("Beta", incoming.name)
    }

    @Test fun `tombstone GC purges tombstones older than 90 days`() {
        val now = 200L * 24 * 60 * 60 * 1000
        val records = listOf(
            list("old", "Old", updatedAt = 0, deleted = true, deletedAt = 0),
            list("recent", "Recent", updatedAt = now - 1000, deleted = true, deletedAt = now - 1000),
            list("live", "Live", updatedAt = now),
        )
        val result = SyncMerge.purgeOldTombstones(records, now, ::identity)
        val ids = result.map { it.syncId }.toSet()
        assertFalse("old" in ids, "a 90+ day old tombstone must be purged")
        assertTrue("recent" in ids, "a recent tombstone must survive")
        assertTrue("live" in ids, "a non-deleted record must never be purged")
    }
}
