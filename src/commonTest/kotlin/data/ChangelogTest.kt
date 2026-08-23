package data

import kotlin.test.*

class ChangelogTest {

    private val fixture = mapOf(
        "1.1.12" to listOf("Bullet 12a"),
        "1.1.13" to listOf("Bullet 13a", "Bullet 13b"),
        "1.1.14" to listOf("Bullet 14a"),
    )

    @Test fun `blank lastSeen shows only the newest entry, not the full history`() =
        // Regression: every existing install has a blank lastSeenVersion the first time this
        // feature's own release ships (the setting never existed before) -- an Android update
        // from 1.1.13 to the 1.1.14 release that introduced this feature showed nothing, because
        // blank used to mean "suppress entirely." It must show *something* (the latest entry),
        // not the whole backlog (which would be a wall of text for a genuinely fresh install).
        assertEquals(listOf("1.1.14" to listOf("Bullet 14a")), pendingWhatsNew("", "1.1.14", fixture))

    @Test fun `blank lastSeen with an older current version shows that version's entry`() =
        assertEquals(listOf("1.1.12" to listOf("Bullet 12a")), pendingWhatsNew("", "1.1.12", fixture))

    @Test fun `blank lastSeen with no matching changelog entries shows nothing`() =
        assertEquals(emptyList(), pendingWhatsNew("", "1.0.0", fixture))

    @Test fun `known lastSeen shows every entry strictly newer, oldest first`() =
        assertEquals(
            listOf("1.1.13" to listOf("Bullet 13a", "Bullet 13b"), "1.1.14" to listOf("Bullet 14a")),
            pendingWhatsNew("1.1.12", "1.1.14", fixture),
        )

    @Test fun `known lastSeen equal to current shows nothing`() =
        assertEquals(emptyList(), pendingWhatsNew("1.1.14", "1.1.14", fixture))

    @Test fun `known lastSeen newer than any entry shows nothing`() =
        assertEquals(emptyList(), pendingWhatsNew("1.1.14", "1.1.20", fixture))

    @Test fun `skipping versions with no changelog entry between them is fine`() =
        assertEquals(
            listOf("1.1.14" to listOf("Bullet 14a")),
            pendingWhatsNew("1.1.13", "1.1.14", fixture),
        )
}
