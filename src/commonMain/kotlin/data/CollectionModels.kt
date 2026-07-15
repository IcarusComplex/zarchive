package data

/**
 * Collection-tracker export formats we can import. Each entry pairs with a parser (e.g.
 * [parseManaBoxCollection]) that turns that tracker's export into [CollectionRow]s. Adding a new
 * tracker later is: a new enum entry + a new parser + a `when` branch in the import UI -- no
 * changes to storage ([CollectionRepo]) or the "which groups count as owned" logic below.
 */
enum class CollectionFormat(val label: String, val defaultFileName: String) {
    MANABOX("ManaBox", "ManaBox_Collection.csv"),
}

/**
 * One row from a collection-tracker export, normalised to a tracker-agnostic shape. [groupName]/
 * [groupType] are the tracker's own grouping concept (ManaBox: "Binder Name"/"Binder Type", values
 * "binder" or "list") -- which groups count as "owned" is a user-editable choice (see
 * [defaultGroupSelection]/[mergeSeenGroups]), not hardcoded here.
 */
data class CollectionRow(
    val groupName: String,
    val groupType: String,
    val cardName: String,
    val quantity: Int,
)

/** Read model for the group-inclusion checklist UI. */
data class CollectionGroupSummary(
    val name: String,
    val type: String,
    val cardCount: Int,
    val included: Boolean,
)

/**
 * Every group in a fresh import, all counted as "owned" by default -- nothing is auto-excluded
 * (not even ManaBox "list" rows like Wishlist/"Fetch Lands"). The bulk "Exclude lists"/"Exclude
 * binders" actions and the per-group checklist in the import UI are how the user narrows this down.
 */
fun defaultGroupSelection(rows: List<CollectionRow>): Set<String> =
    rows.map { it.groupName }.toSet()

/**
 * Re-import bookkeeping: groups the user has never seen before are auto-included (see
 * [defaultGroupSelection] -- nothing is excluded automatically); groups seen in a previous import
 * keep whatever the user last chose (via the checklist or the bulk exclude-lists/exclude-binders
 * actions), even if that diverges -- e.g. they excluded a binder, or a list stays included. Returns
 * (newIncluded, newSeen).
 */
fun mergeSeenGroups(
    previousIncluded: Set<String>,
    previousSeen: Set<String>,
    rows: List<CollectionRow>,
): Pair<Set<String>, Set<String>> {
    val groupNames = rows.map { it.groupName }.toSet()
    val newGroupNames = groupNames - previousSeen
    return Pair(previousIncluded + newGroupNames, previousSeen + groupNames)
}
