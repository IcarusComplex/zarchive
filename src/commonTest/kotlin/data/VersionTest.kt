package data

import kotlin.test.*

class VersionTest {

    @Test fun `plain patch bump is newer`() =
        assertTrue(isNewerVersion("1.1.14", "1.1.13"))

    @Test fun `plain patch bump is not newer in reverse`() =
        assertFalse(isNewerVersion("1.1.13", "1.1.14"))

    @Test fun `equal versions are not newer`() =
        assertFalse(isNewerVersion("1.1.14", "1.1.14"))

    @Test fun `double-digit patch compares numerically, not lexicographically`() =
        assertTrue(isNewerVersion("1.1.10", "1.1.9"))

    @Test fun `minor bump beats any patch`() =
        assertTrue(isNewerVersion("1.2.0", "1.1.99"))

    @Test fun `prerelease suffix is stripped before comparing`() =
        // Regression: "1.1.15-beta.1" used to parse as [1, 1, 1] (the non-numeric "15-beta"
        // segment was silently dropped), comparing as *older* than "1.1.14" -- meaning an
        // opted-in prerelease check would report "already up to date" and never offer the beta.
        assertTrue(isNewerVersion("1.1.15-beta.1", "1.1.14"))

    @Test fun `prerelease of the current version is not newer`() =
        assertFalse(isNewerVersion("1.1.14-beta.1", "1.1.14"))

    @Test fun `two prereleases of the same base compare equal`() =
        assertFalse(isNewerVersion("1.1.15-beta.2", "1.1.15-beta.1"))
}
