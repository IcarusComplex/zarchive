package collection

import data.CollectionFormat
import data.CollectionGroupSummary
import data.CollectionRepo
import data.SettingsStore
import data.mergeSeenGroups
import data.parseManaBoxCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import network.createDriveFile
import network.downloadDriveFile
import network.findDriveFile
import network.updateDriveFile
import sync.SyncEngine

private const val KEY_FORMAT = "collection.format"
private const val KEY_DRIVE_FILE_NAME = "collection.driveFileName"
private const val KEY_INCLUDED_GROUPS = "collection.includedGroups"
private const val KEY_SEEN_GROUPS = "collection.seenGroups"
private const val KEY_LAST_IMPORTED_AT = "collection.lastImportedAt"

/**
 * Owns collection-import configuration (format, which groups count as "owned", Drive file name)
 * and orchestrates fetch-and-apply, the same way [SyncEngine] owns list/result sync config and
 * treats its repos as dumb storage. Deliberately **not** called from [SyncEngine.syncNow] --
 * per-project decision, collection import is a separate, explicitly-triggered action (its own
 * settings dialog / "Import now" button), not folded into the automatic Drive blob merge.
 */
class CollectionImportEngine(
    private val collectionRepo: CollectionRepo,
    private val syncEngine: SyncEngine,
) {
    private val json = Json { ignoreUnknownKeys = true }

    var format: CollectionFormat
        get() = runCatching {
            CollectionFormat.valueOf(SettingsStore.getSetting(KEY_FORMAT, CollectionFormat.MANABOX.name))
        }.getOrDefault(CollectionFormat.MANABOX)
        set(value) = SettingsStore.setSetting(KEY_FORMAT, value.name)

    var driveFileName: String
        get() = SettingsStore.getSetting(KEY_DRIVE_FILE_NAME, "").ifBlank { format.defaultFileName }
        set(value) = SettingsStore.setSetting(KEY_DRIVE_FILE_NAME, value)

    val lastImportedAt: Long?
        get() = SettingsStore.getSetting(KEY_LAST_IMPORTED_AT, "").toLongOrNull()

    private var includedGroups: Set<String>
        get() = readStringSet(KEY_INCLUDED_GROUPS)
        set(value) = writeStringSet(KEY_INCLUDED_GROUPS, value)

    private var seenGroups: Set<String>
        get() = readStringSet(KEY_SEEN_GROUPS)
        set(value) = writeStringSet(KEY_SEEN_GROUPS, value)

    private val _groups = MutableStateFlow<List<CollectionGroupSummary>>(emptyList())
    val groups: StateFlow<List<CollectionGroupSummary>> = _groups.asStateFlow()

    // Lowercase+trimmed card names from rows in an included group -- exact-match lookup set for
    // the "in collection" UI icon. Never fuzzy: callers must normalize the same way before `in`.
    private val _ownedCardNames = MutableStateFlow<Set<String>>(emptySet())
    val ownedCardNames: StateFlow<Set<String>> = _ownedCardNames.asStateFlow()

    init { recomputeDerived() }

    private fun recomputeDerived() {
        val rows = collectionRepo.rows.value
        val included = includedGroups
        _groups.value = rows.groupBy { it.groupName }
            .map { (name, groupRows) ->
                CollectionGroupSummary(
                    name = name,
                    type = groupRows.first().groupType,
                    cardCount = groupRows.map { it.cardName.trim().lowercase() }.distinct().size,
                    included = name in included,
                )
            }
            .sortedBy { it.name.lowercase() }
        _ownedCardNames.value = rows
            .filter { it.groupName in included }
            .map { it.cardName.trim().lowercase() }
            .toSet()
    }

    fun setGroupIncluded(groupName: String, included: Boolean) {
        includedGroups = if (included) includedGroups + groupName else includedGroups - groupName
        recomputeDerived()
    }

    /** Bulk action backing the "Exclude lists"/"Exclude binders" (and their invert) buttons. */
    fun setGroupsOfTypeIncluded(groupType: String, included: Boolean) {
        val namesOfType = collectionRepo.rows.value
            .filter { it.groupType.equals(groupType, ignoreCase = true) }
            .map { it.groupName }
            .toSet()
        includedGroups = if (included) includedGroups + namesOfType else includedGroups - namesOfType
        recomputeDerived()
    }

    /** Fetches [driveFileName] from the same "ZArchive" Drive folder used for list/result sync. */
    suspend fun importFromDrive(): Result<Int> {
        val access = syncEngine.resolveAccess().getOrElse { return Result.failure(it) }
        val file = findDriveFile(access.accessToken, access.folderId, driveFileName)
            ?: return Result.failure(Exception(
                "No \"$driveFileName\" file found. Google Drive's restricted access means ZArchive " +
                    "can only see files it uploaded itself -- files dragged into the folder via Drive's " +
                    "website aren't visible here. Use \"Import from file\" once (on a desktop) instead; " +
                    "it uploads to Drive for you."
            ))
        val text = downloadDriveFile(access.accessToken, file.id)
            ?: return Result.failure(Exception("Couldn't download $driveFileName"))
        return importFromText(text)
    }

    /**
     * Shared apply path for both Drive and local-file import. After a successful local-file import,
     * also pushes the raw text to Drive (best-effort, failure is swallowed) so the app "owns" that
     * Drive file going forward -- see [importFromDrive]'s doc for why that's required for Drive-based
     * import to ever find it (drive.file scope only sees files ZArchive itself created).
     */
    suspend fun importFromText(text: String, alsoUploadToDrive: Boolean = false): Result<Int> = runCatching {
        val rows = when (format) {
            CollectionFormat.MANABOX -> parseManaBoxCollection(text)
        }
        if (rows.isEmpty()) {
            throw Exception("No rows found -- check this is a valid ${format.label} export")
        }
        val (newIncluded, newSeen) = mergeSeenGroups(includedGroups, seenGroups, rows)
        includedGroups = newIncluded
        seenGroups = newSeen
        collectionRepo.replaceRows(rows)
        SettingsStore.setSetting(KEY_LAST_IMPORTED_AT, System.currentTimeMillis().toString())
        recomputeDerived()
        if (alsoUploadToDrive && syncEngine.isConnected) {
            runCatching { uploadToDrive(text) }
        }
        _ownedCardNames.value.size
    }

    /** Creates or overwrites [driveFileName] in the "ZArchive" Drive folder with [text]. */
    suspend fun uploadToDrive(text: String): Result<Unit> {
        val access = syncEngine.resolveAccess().getOrElse { return Result.failure(it) }
        val existing = findDriveFile(access.accessToken, access.folderId, driveFileName)
        val ok = if (existing == null) {
            createDriveFile(access.accessToken, access.folderId, driveFileName, text, mimeType = "text/csv") != null
        } else {
            updateDriveFile(access.accessToken, existing.id, text, mimeType = "text/csv") != null
        }
        return if (ok) Result.success(Unit) else Result.failure(Exception("Couldn't upload $driveFileName to Drive"))
    }

    private fun readStringSet(key: String): Set<String> =
        runCatching { json.decodeFromString<List<String>>(SettingsStore.getSetting(key, "[]")) }
            .getOrDefault(emptyList())
            .toSet()

    private fun writeStringSet(key: String, value: Set<String>) {
        SettingsStore.setSetting(key, json.encodeToString(value.toList()))
    }
}
