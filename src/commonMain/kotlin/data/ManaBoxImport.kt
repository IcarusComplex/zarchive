package data

/**
 * Minimal RFC4180 CSV parser: quoted fields, `""` escaping, commas/newlines inside quotes, both
 * CRLF and bare-LF line endings, and a missing trailing newline on the last row. No existing CSV
 * dependency in this project -- this follows the same "roll a small manual parser" style already
 * used for [network.GoogleDriveService]'s hand-written JSON navigation rather than pulling in a
 * library for one narrow need.
 */
fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var field = StringBuilder()
    var row = mutableListOf<String>()
    var inQuotes = false
    var i = 0
    val n = text.length

    fun endField() { row.add(field.toString()); field = StringBuilder() }
    fun endRow() { endField(); rows.add(row); row = mutableListOf() }

    while (i < n) {
        val c = text[i]
        if (inQuotes) {
            when {
                c == '"' && i + 1 < n && text[i + 1] == '"' -> { field.append('"'); i += 2 }
                c == '"' -> { inQuotes = false; i++ }
                else -> { field.append(c); i++ }
            }
        } else {
            when (c) {
                '"' -> { inQuotes = true; i++ }
                ',' -> { endField(); i++ }
                '\r' -> { endRow(); i++; if (i < n && text[i] == '\n') i++ }
                '\n' -> { endRow(); i++ }
                else -> { field.append(c); i++ }
            }
        }
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()

    // Drop fully blank lines (a lone empty field from an empty source line).
    return rows.filterNot { it.size == 1 && it[0].isEmpty() }
}

/**
 * Parses a ManaBox collection export into tracker-agnostic [CollectionRow]s. Columns are resolved
 * by header name (not fixed position) so column reordering in a future ManaBox export doesn't
 * break this. Rows missing a binder/card name are skipped; a missing/non-numeric Quantity defaults
 * to 1.
 */
fun parseManaBoxCollection(text: String): List<CollectionRow> {
    val table = parseCsv(text)
    if (table.isEmpty()) return emptyList()
    val header = table.first()
    val binderNameIdx = header.indexOfFirst { it.equals("Binder Name", ignoreCase = true) }
    val binderTypeIdx = header.indexOfFirst { it.equals("Binder Type", ignoreCase = true) }
    val nameIdx = header.indexOfFirst { it.equals("Name", ignoreCase = true) }
    val quantityIdx = header.indexOfFirst { it.equals("Quantity", ignoreCase = true) }
    if (binderNameIdx < 0 || binderTypeIdx < 0 || nameIdx < 0) return emptyList()

    return table.drop(1).mapNotNull { fields ->
        val groupName = fields.getOrNull(binderNameIdx)?.trim().orEmpty()
        val groupType = fields.getOrNull(binderTypeIdx)?.trim().orEmpty()
        val cardName = fields.getOrNull(nameIdx)?.trim().orEmpty()
        if (groupName.isEmpty() || cardName.isEmpty()) return@mapNotNull null
        val quantity = quantityIdx.takeIf { it >= 0 }
            ?.let { fields.getOrNull(it)?.trim()?.toIntOrNull() } ?: 1
        CollectionRow(groupName, groupType, cardName, quantity)
    }
}
