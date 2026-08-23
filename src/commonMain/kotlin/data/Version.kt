package data

/**
 * True if [latest] is a newer dotted version string than [current] (e.g. "1.1.14" > "1.1.9").
 * A prerelease suffix (e.g. "1.1.15-beta.1") is stripped before comparing -- the numeric parser
 * below silently drops any non-numeric dot-segment ("15-beta" fails toIntOrNull), which used to
 * make "1.1.15-beta.1" parse as [1, 1, 1] and compare as *older* than a plain "1.1.14" instead of
 * newer. Comparing prerelease versions of the same base (e.g. "-beta.1" vs "-beta.2") isn't
 * distinguished by this -- both compare equal to their shared base -- which is fine here since
 * only one prerelease is ever live at a time.
 */
fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split(".").mapNotNull { it.toIntOrNull() }
    val l = parts(latest)
    val c = parts(current)
    for (i in 0 until maxOf(l.size, c.size)) {
        val diff = (l.getOrElse(i) { 0 }) - (c.getOrElse(i) { 0 })
        if (diff != 0) return diff > 0
    }
    return false
}
