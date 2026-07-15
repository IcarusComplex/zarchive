package network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Minimal Google Drive v3 REST client, styled after [GitHubService.kt]: a fresh `HttpClient(OkHttp)`
 * per call, manual `JsonObject` navigation (no typed serialization plugin), `runCatching` + explicit
 * close. Every call takes a valid (already-refreshed) access token -- token lifecycle lives in
 * [GoogleAuthService.kt] / the sync engine, not here.
 *
 * Scoped to `drive.file` (see CLAUDE.md decision #2): these calls only ever see files/folders this
 * app itself created, never the user's whole Drive.
 */
private val driveJson = Json { ignoreUnknownKeys = true }
private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

data class DriveFileMeta(val id: String, val name: String, val modifiedTime: String?)

/** Finds an app-created, non-trashed folder named [name] directly under "My Drive" root. */
suspend fun findDriveFolder(accessToken: String, name: String): DriveFileMeta? {
    val query = "mimeType='application/vnd.google-apps.folder' and name='${escapeQuery(name)}'" +
        " and trashed=false and 'root' in parents"
    return queryFirstFile(accessToken, query)
}

suspend fun createDriveFolder(accessToken: String, name: String): DriveFileMeta? {
    val client = HttpClient(OkHttp)
    return runCatching {
        val resp = client.post(FILES_URL) {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"${escapeJson(name)}","mimeType":"application/vnd.google-apps.folder"}""")
        }
        if (!resp.status.isSuccess()) return@runCatching null
        parseFileMeta(driveJson.parseToJsonElement(resp.bodyAsText()).jsonObject)
    }.also { client.close() }.getOrNull()
}

suspend fun findOrCreateDriveFolder(accessToken: String, name: String): DriveFileMeta? =
    findDriveFolder(accessToken, name) ?: createDriveFolder(accessToken, name)

/** Finds a non-trashed file named [name] directly inside folder [folderId]. */
suspend fun findDriveFile(accessToken: String, folderId: String, name: String): DriveFileMeta? {
    val query = "name='${escapeQuery(name)}' and '$folderId' in parents and trashed=false"
    return queryFirstFile(accessToken, query)
}

/** Re-fetches just the metadata (in particular `modifiedTime`) for an already-known file id. */
suspend fun getDriveFileMetadata(accessToken: String, fileId: String): DriveFileMeta? {
    val client = HttpClient(OkHttp)
    return runCatching {
        val resp = client.get("$FILES_URL/$fileId") {
            bearerAuth(accessToken)
            parameter("fields", "id,name,modifiedTime")
        }
        if (!resp.status.isSuccess()) return@runCatching null
        parseFileMeta(driveJson.parseToJsonElement(resp.bodyAsText()).jsonObject)
    }.also { client.close() }.getOrNull()
}

suspend fun downloadDriveFile(accessToken: String, fileId: String): String? {
    val client = HttpClient(OkHttp)
    return runCatching {
        val resp = client.get("$FILES_URL/$fileId") {
            bearerAuth(accessToken)
            parameter("alt", "media")
        }
        if (!resp.status.isSuccess()) return@runCatching null
        resp.bodyAsText()
    }.also { client.close() }.getOrNull()
}

/** Creates a new file with [content] inside folder [folderId]. Defaults to JSON; pass [mimeType] for other content (e.g. CSV). */
suspend fun createDriveFile(accessToken: String, folderId: String, name: String, content: String, mimeType: String = "application/json"): DriveFileMeta? {
    val client = HttpClient(OkHttp)
    return runCatching {
        val boundary = "zarchive_${System.nanoTime()}"
        val metadata = """{"name":"${escapeJson(name)}","parents":["$folderId"],"mimeType":"$mimeType"}"""
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--").append(boundary).append("\r\n")
            append("Content-Type: $mimeType; charset=UTF-8\r\n\r\n")
            append(content)
            append("\r\n--").append(boundary).append("--")
        }
        val resp = client.post("$UPLOAD_URL?uploadType=multipart") {
            bearerAuth(accessToken)
            header(HttpHeaders.ContentType, "multipart/related; boundary=$boundary")
            setBody(body)
        }
        if (!resp.status.isSuccess()) return@runCatching null
        parseFileMeta(driveJson.parseToJsonElement(resp.bodyAsText()).jsonObject)
    }.also { client.close() }.getOrNull()
}

/** Overwrites [fileId]'s content in place (metadata/parents untouched). Returns the new modifiedTime. */
suspend fun updateDriveFile(accessToken: String, fileId: String, content: String, mimeType: String = "application/json"): DriveFileMeta? {
    val client = HttpClient(OkHttp)
    return runCatching {
        val resp = client.patch("$UPLOAD_URL/$fileId?uploadType=media") {
            bearerAuth(accessToken)
            contentType(ContentType.parse(mimeType))
            setBody(content)
        }
        if (!resp.status.isSuccess()) return@runCatching null
        getDriveFileMetadata(accessToken, fileId)
    }.also { client.close() }.getOrNull()
}

private suspend fun queryFirstFile(accessToken: String, query: String): DriveFileMeta? {
    val client = HttpClient(OkHttp)
    return runCatching {
        val resp = client.get(FILES_URL) {
            bearerAuth(accessToken)
            parameter("q", query)
            parameter("fields", "files(id,name,modifiedTime)")
            parameter("spaces", "drive")
        }
        if (!resp.status.isSuccess()) return@runCatching null
        val files = driveJson.parseToJsonElement(resp.bodyAsText()).jsonObject["files"]?.jsonArray
        files?.firstOrNull()?.jsonObject?.let(::parseFileMeta)
    }.also { client.close() }.getOrNull()
}

private fun parseFileMeta(obj: JsonObject): DriveFileMeta? {
    val id = obj["id"]?.jsonPrimitive?.content ?: return null
    return DriveFileMeta(
        id = id,
        name = obj["name"]?.jsonPrimitive?.content ?: "",
        modifiedTime = obj["modifiedTime"]?.jsonPrimitive?.content,
    )
}

// Drive's `q` parameter uses single-quoted string literals; escape embedded quotes/backslashes.
private fun escapeQuery(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
private fun escapeJson(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
