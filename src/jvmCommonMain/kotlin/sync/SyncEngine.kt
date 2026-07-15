package sync

import data.SavedSearchList
import data.SearchListRepo
import data.SearchResultRepo
import data.SettingsStore
import data.SyncedResultRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import network.GoogleAuthConfig
import network.GoogleOAuthFlow
import network.createDriveFile
import network.downloadDriveFile
import network.fetchGoogleAccountEmail
import network.findDriveFile
import network.findOrCreateDriveFolder
import network.getDriveFileMetadata
import network.refreshGoogleAccessToken
import network.revokeGoogleToken
import network.updateDriveFile

private const val KEY_REFRESH_TOKEN = "googleDrive.refreshToken"
private const val KEY_ACCOUNT_EMAIL = "googleDrive.accountEmail"
private const val KEY_FOLDER_ID = "googleDrive.folderId"

/**
 * Orchestrates one full sync round-trip: download the current remote blob, merge with local repo
 * state via [SyncMerge], write the merged result back to both the local DB and Drive. Everything
 * network/DB-specific is delegated (repos, [network.GoogleDriveService], [network.GoogleAuthService]);
 * this class is just the glue plus the optimistic-concurrency retry loop (CLAUDE.md decision #4).
 */
class SyncEngine(
    private val searchListRepo: SearchListRepo,
    private val searchResultRepo: SearchResultRepo,
    private val oauthFlow: GoogleOAuthFlow = GoogleOAuthFlow(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConnected: Boolean
        get() = SettingsStore.getSetting(KEY_REFRESH_TOKEN, "").isNotBlank()

    val accountEmail: String?
        get() = SettingsStore.getSetting(KEY_ACCOUNT_EMAIL, "").ifBlank { null }

    /** Runs the interactive consent flow and stores the resulting refresh token. */
    suspend fun connect(): Result<Unit> {
        val tokens = oauthFlow.authenticate(GoogleAuthConfig.SCOPE)
            ?: return Result.failure(Exception("Sign-in was cancelled or the token exchange failed"))
        val refreshToken = tokens.refreshToken
            ?: return Result.failure(Exception(
                "Google didn't return a refresh token. If you've connected ZArchive before, " +
                    "remove its access at https://myaccount.google.com/permissions and try again."
            ))
        SettingsStore.setSetting(KEY_REFRESH_TOKEN, refreshToken)
        fetchGoogleAccountEmail(tokens.accessToken)?.let { SettingsStore.setSetting(KEY_ACCOUNT_EMAIL, it) }
        return Result.success(Unit)
    }

    /** Best-effort revoke on Google's side; local state is cleared regardless of whether that succeeds. */
    suspend fun disconnect() {
        val refreshToken = SettingsStore.getSetting(KEY_REFRESH_TOKEN, "").ifBlank { null }
        if (refreshToken != null) revokeGoogleToken(refreshToken)
        SettingsStore.setSetting(KEY_REFRESH_TOKEN, "")
        SettingsStore.setSetting(KEY_ACCOUNT_EMAIL, "")
        SettingsStore.setSetting(KEY_FOLDER_ID, "")
    }

    /** A resolved, ready-to-use (access token, shared "ZArchive" Drive folder id) pair. */
    data class DriveAccess(val accessToken: String, val folderId: String)

    /**
     * Refreshes the access token and resolves the shared "ZArchive" Drive folder, caching the
     * folder id in settings. Reused by [syncNow] and by `collection.CollectionImportEngine` (a
     * separate, explicitly-triggered feature -- see its own class doc) so collection import rides
     * on the same connected Google account without a second OAuth flow.
     */
    suspend fun resolveAccess(): Result<DriveAccess> {
        val refreshToken = SettingsStore.getSetting(KEY_REFRESH_TOKEN, "").ifBlank { null }
            ?: return Result.failure(Exception("Not connected"))
        val tokens = refreshGoogleAccessToken(oauthFlow.refreshClientId, oauthFlow.refreshClientSecret, refreshToken)
            ?: return Result.failure(Exception("Couldn't refresh Google access -- try reconnecting"))
        val accessToken = tokens.accessToken

        val folderId = SettingsStore.getSetting(KEY_FOLDER_ID, "").ifBlank { null }
            ?: findOrCreateDriveFolder(accessToken, GoogleAuthConfig.SYNC_FOLDER_NAME)?.id?.also {
                SettingsStore.setSetting(KEY_FOLDER_ID, it)
            }
            ?: return Result.failure(Exception("Couldn't access the Drive folder"))

        return Result.success(DriveAccess(accessToken, folderId))
    }

    suspend fun syncNow(): Result<Unit> {
        val access = resolveAccess().getOrElse { return Result.failure(it) }
        val accessToken = access.accessToken
        val folderId = access.folderId

        // Bounded retries against the rare case where the blob changes on Drive between our
        // download and our upload (two devices syncing within the same few-hundred-ms window).
        repeat(3) {
            val existingFile = findDriveFile(accessToken, folderId, GoogleAuthConfig.SYNC_BLOB_FILE_NAME)
            val remoteBlob = existingFile
                ?.let { downloadDriveFile(accessToken, it.id) }
                ?.let { text -> runCatching { json.decodeFromString<SyncBlob>(text) }.getOrNull() }
                ?: SyncBlob()

            val now = System.currentTimeMillis()
            val mergedLists = SyncMerge.merge(
                local = searchListRepo.allForSync().map { it.toSynced() },
                remote = remoteBlob.lists,
                identity = { SyncMerge.Identity(it.syncId, it.name, it.updatedAt, it.deleted) },
                withName = { l, n -> l.copy(name = n) },
            ).let { SyncMerge.purgeOldTombstones(it, now) { r -> SyncMerge.Identity(r.syncId, r.name, r.updatedAt, r.deleted) } }

            val mergedResults = SyncMerge.merge(
                local = searchResultRepo.allForSync().map { it.toSynced() },
                remote = remoteBlob.results,
                identity = { SyncMerge.Identity(it.syncId, it.name, it.savedAt, it.deleted) },
                withName = { r, n -> r.copy(name = n) },
            ).let { SyncMerge.purgeOldTombstones(it, now) { r -> SyncMerge.Identity(r.syncId, r.name, r.savedAt, r.deleted) } }

            mergedLists.forEach { searchListRepo.applyRemote(it.toSavedSearchList()) }
            mergedResults.forEach { searchResultRepo.applyRemote(it.toSyncedResultRecord()) }

            val content = json.encodeToString(SyncBlob(lists = mergedLists, results = mergedResults))

            if (existingFile == null) {
                if (createDriveFile(accessToken, folderId, GoogleAuthConfig.SYNC_BLOB_FILE_NAME, content) != null) {
                    return Result.success(Unit)
                }
            } else {
                // Optimistic-concurrency check: if modifiedTime moved since we downloaded, someone
                // else won the race -- loop around and re-download/re-merge/retry instead of
                // clobbering their write.
                val stillCurrent = getDriveFileMetadata(accessToken, existingFile.id)?.modifiedTime == existingFile.modifiedTime
                if (stillCurrent && updateDriveFile(accessToken, existingFile.id, content) != null) {
                    return Result.success(Unit)
                }
            }
        }
        return Result.failure(Exception("Sync conflict -- gave up after 3 attempts, try again shortly"))
    }
}

private fun SavedSearchList.toSynced() = SyncedList(
    syncId = syncId ?: java.util.UUID.randomUUID().toString(),
    name = name,
    cards = cards,
    updatedAt = updatedAt,
    deleted = deleted,
    deletedAt = deletedAt,
)

private fun SyncedList.toSavedSearchList() = SavedSearchList(
    id = 0, // ignored by applyRemote -- matching/updating is done by syncId
    name = name,
    cards = cards,
    updatedAt = updatedAt,
    syncId = syncId,
    deleted = deleted,
    deletedAt = deletedAt,
)

private fun SyncedResultRecord.toSynced() = SyncedResult(
    syncId = syncId ?: java.util.UUID.randomUUID().toString(),
    name = name,
    description = description,
    savedAt = savedAt,
    cards = cards,
    results = results,
    excludedCards = excludedCards,
    uncheckedLines = uncheckedLines,
    pinnedListings = pinnedListings,
    deleted = deleted,
    deletedAt = deletedAt,
)

private fun SyncedResult.toSyncedResultRecord() = SyncedResultRecord(
    id = 0, // ignored by applyRemote -- matching/updating is done by syncId
    syncId = syncId,
    name = name,
    description = description,
    savedAt = savedAt,
    cards = cards,
    results = results,
    excludedCards = excludedCards,
    uncheckedLines = uncheckedLines,
    pinnedListings = pinnedListings,
    deleted = deleted,
    deletedAt = deletedAt,
)
