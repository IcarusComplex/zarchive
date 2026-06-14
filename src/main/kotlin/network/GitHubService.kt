package network

import data.BuildInfo
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private const val REPO = "IcarusComplex/zarchive"
private val ghJson = Json { ignoreUnknownKeys = true }

data class UpdateInfo(val tag: String, val releaseUrl: String)

suspend fun checkForUpdate(includePrerelease: Boolean): UpdateInfo? {
    val client = HttpClient(CIO)
    return runCatching {
        val url = if (includePrerelease)
            "https://api.github.com/repos/$REPO/releases"
        else
            "https://api.github.com/repos/$REPO/releases/latest"
        val resp = client.get(url) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "ZArchive/${BuildInfo.VERSION}")
        }
        if (!resp.status.isSuccess()) return@runCatching null
        val body = resp.bodyAsText()
        val release: JsonObject? = if (includePrerelease) {
            ghJson.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject
        } else {
            ghJson.parseToJsonElement(body).jsonObject
        }
        val tag = release?.get("tag_name")?.jsonPrimitive?.content ?: return@runCatching null
        val htmlUrl = release["html_url"]?.jsonPrimitive?.content ?: return@runCatching null
        if (isNewerVersion(tag.trimStart('v'), BuildInfo.VERSION)) UpdateInfo(tag, htmlUrl) else null
    }.also { client.close() }.getOrNull()
}

suspend fun createCrashIssue(description: String, crashLog: String): Boolean {
    val token = BuildInfo.GITHUB_CRASH_TOKEN
    if (token.isBlank()) return false
    val client = HttpClient(CIO)
    return runCatching {
        val body = buildJsonObject {
            put("title", "[Crash Report] User-submitted crash")
            put("body", "## What I was doing\n\n${description.ifBlank { "*(not provided)*" }}\n\n## Crash Log\n\n```\n$crashLog\n```")
            putJsonArray("labels") { add("bug"); add("crash-report") }
        }.toString()
        val resp = client.post("https://api.github.com/repos/$REPO/issues") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "ZArchive/${BuildInfo.VERSION}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.status.isSuccess()
    }.also { client.close() }.getOrElse { false }
}

private fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(v: String) = v.split(".").mapNotNull { it.toIntOrNull() }
    val l = parts(latest)
    val c = parts(current)
    for (i in 0 until maxOf(l.size, c.size)) {
        val diff = (l.getOrElse(i) { 0 }) - (c.getOrElse(i) { 0 })
        if (diff != 0) return diff > 0
    }
    return false
}
