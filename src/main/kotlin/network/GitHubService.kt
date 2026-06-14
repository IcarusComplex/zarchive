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

// URL for opening a pre-labelled new issue in the browser — no token required.
const val CRASH_REPORT_URL =
    "https://github.com/$REPO/issues/new?labels=bug%2Ccrash-report&title=%5BCrash+Report%5D"

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
