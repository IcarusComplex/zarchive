package data

import kotlin.test.*

class ManaBoxImportTest {

    // ── parseCsv ─────────────────────────────────────────────────────────────

    @Test fun `splits simple unquoted fields`() {
        val rows = parseCsv("a,b,c\n1,2,3")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
    }

    @Test fun `quoted field with embedded comma is not split`() {
        val rows = parseCsv("""Name,Set
"Mikokoro, Center of the Sea",CHK""")
        assertEquals(listOf(listOf("Name", "Set"), listOf("Mikokoro, Center of the Sea", "CHK")), rows)
    }

    @Test fun `doubled quote inside quoted field is unescaped`() {
        val csv = "Name\n\"Say \"\"hello\"\"\""
        val rows = parseCsv(csv)
        assertEquals("Say \"hello\"", rows[1][0])
    }

    @Test fun `handles CRLF line endings`() {
        val rows = parseCsv("a,b\r\n1,2\r\n3,4")
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2"), listOf("3", "4")), rows)
    }

    @Test fun `last row without trailing newline is included`() {
        val rows = parseCsv("a,b\n1,2")
        assertEquals(2, rows.size)
        assertEquals(listOf("1", "2"), rows[1])
    }

    @Test fun `blank lines are dropped`() {
        val rows = parseCsv("a,b\n\n1,2\n")
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows)
    }

    // ── parseManaBoxCollection ───────────────────────────────────────────────

    private val sampleCsv = """
        Binder Name,Binder Type,Name,Set code,Quantity
        Lands,binder,Plains,ONE,1
        Lands,binder,"Mikokoro, Center of the Sea",DMC,2
        Wishlist,list,Ragavan,MH2,1
    """.trimIndent()

    @Test fun `extracts rows with quoted comma name intact`() {
        val rows = parseManaBoxCollection(sampleCsv)
        assertEquals(3, rows.size)
        assertEquals("Mikokoro, Center of the Sea", rows[1].cardName)
        assertEquals(2, rows[1].quantity)
    }

    @Test fun `binder and list rows both parsed, distinguished by groupType`() {
        val rows = parseManaBoxCollection(sampleCsv)
        assertEquals("binder", rows[0].groupType)
        assertEquals("list", rows[2].groupType)
    }

    @Test fun `column order does not matter`() {
        val reordered = """
            Name,Binder Type,Quantity,Binder Name
            Plains,binder,1,Lands
        """.trimIndent()
        val rows = parseManaBoxCollection(reordered)
        assertEquals(1, rows.size)
        assertEquals(CollectionRow("Lands", "binder", "Plains", 1), rows[0])
    }

    @Test fun `missing quantity column defaults to 1`() {
        val noQty = """
            Binder Name,Binder Type,Name
            Lands,binder,Plains
        """.trimIndent()
        val rows = parseManaBoxCollection(noQty)
        assertEquals(1, rows[0].quantity)
    }

    @Test fun `rows missing a card name are skipped`() {
        val withBlank = """
            Binder Name,Binder Type,Name,Quantity
            Lands,binder,,1
            Lands,binder,Plains,1
        """.trimIndent()
        val rows = parseManaBoxCollection(withBlank)
        assertEquals(1, rows.size)
        assertEquals("Plains", rows[0].cardName)
    }

    // ── defaultGroupSelection / mergeSeenGroups ─────────────────────────────

    @Test fun `defaultGroupSelection includes every group, binder and list alike`() {
        val rows = parseManaBoxCollection(sampleCsv)
        assertEquals(setOf("Lands", "Wishlist"), defaultGroupSelection(rows))
    }

    @Test fun `mergeSeenGroups auto-includes every new group regardless of type`() {
        val rows = parseManaBoxCollection(sampleCsv)
        val (included, seen) = mergeSeenGroups(previousIncluded = emptySet(), previousSeen = emptySet(), rows = rows)
        assertEquals(setOf("Lands", "Wishlist"), included)
        assertEquals(setOf("Lands", "Wishlist"), seen)
    }

    @Test fun `mergeSeenGroups preserves a user override on a previously seen group`() {
        // User manually excluded "Lands" (a binder) on a prior import.
        val rows = parseManaBoxCollection(sampleCsv)
        val (included, seen) = mergeSeenGroups(
            previousIncluded = emptySet(),
            previousSeen = setOf("Lands", "Wishlist"),
            rows = rows,
        )
        assertEquals(emptySet(), included)
        assertEquals(setOf("Lands", "Wishlist"), seen)
    }
}
