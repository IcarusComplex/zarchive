package network

import data.BuildInfo
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import java.io.File

private const val REPO = "IcarusComplex/zarchive"
private val ghJson = Json { ignoreUnknownKeys = true }

// URLs for opening pre-labelled GitHub issues in the browser — no token required.
const val CRASH_REPORT_URL =
    "https://github.com/$REPO/issues/new?labels=bug%2Ccrash-report&title=%5BCrash+Report%5D"
const val BUG_REPORT_URL =
    "https://github.com/$REPO/issues/new?labels=bug"

data class UpdateInfo(
    val tag: String,
    val releaseUrl: String,
    /** Direct download URL for the Windows zip asset, or null if no asset was found. */
    val downloadUrl: String?,
)

suspend fun checkForUpdate(includePrerelease: Boolean): UpdateInfo? {
    val client = HttpClient(OkHttp)
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
        val tag     = release?.get("tag_name")?.jsonPrimitive?.content ?: return@runCatching null
        val htmlUrl = release["html_url"]?.jsonPrimitive?.content    ?: return@runCatching null
        if (!isNewerVersion(tag.trimStart('v'), BuildInfo.VERSION)) return@runCatching null

        // Pick the platform-appropriate zip from the release assets.
        val os = System.getProperty("os.name", "").orEmpty().lowercase()
        val platformKey = when {
            os.contains("mac") -> "macos"
            os.contains("win") -> "windows"
            else               -> null
        }
        val zips = release["assets"]?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.filter { it["name"]?.jsonPrimitive?.content?.endsWith(".zip") == true }
            ?: emptyList()
        val downloadUrl = (if (platformKey != null)
            zips.firstOrNull { it["name"]?.jsonPrimitive?.content?.contains(platformKey) == true }
                ?: zips.firstOrNull()
        else
            zips.firstOrNull()
        )?.get("browser_download_url")?.jsonPrimitive?.content

        UpdateInfo(tag, htmlUrl, downloadUrl)
    }.also { client.close() }.getOrNull()
}

/**
 * Downloads [url] to [dest], calling [onProgress] with a 0..1 fraction as bytes arrive.
 * Follows redirects (GitHub release assets redirect to CDN).
 */
suspend fun downloadRelease(url: String, dest: File, onProgress: (Float) -> Unit) {
    val client = HttpClient(OkHttp) {
        install(HttpRedirect) { checkHttpMethod = false }
        // Large file — no timeout on the response body read, only on connect.
        install(HttpTimeout) { connectTimeoutMillis = 15_000 }
    }
    runCatching {
        val resp = client.get(url) {
            header(HttpHeaders.UserAgent, "ZArchive/${BuildInfo.VERSION}")
            header(HttpHeaders.Accept, "application/octet-stream")
        }
        if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}")
        val total    = resp.contentLength() ?: -1L
        var received = 0L
        var lastProgressMs = 0L
        val channel  = resp.bodyAsChannel()
        val buf      = ByteArray(1_048_576) // 1 MB — fewer trips through the readAvailable loop
        dest.outputStream().buffered(1_048_576).use { out ->
            // readAvailable is a suspend fun — it responds to coroutine cancellation,
            // unlike toInputStream() which blocks the thread indefinitely.
            while (!channel.isClosedForRead) {
                val n = channel.readAvailable(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                received += n
                // Throttle to ≤2 UI updates/sec. When Content-Length is absent (chunked CDN
                // response), pass NaN so the UI shows an indeterminate bar instead of 0%.
                val now = System.currentTimeMillis()
                if (now - lastProgressMs >= 500) {
                    onProgress(if (total > 0) received.toFloat() / total else Float.NaN)
                    lastProgressMs = now
                }
            }
        }
        onProgress(1f)
    }.also { client.close() }.getOrThrow()
}

internal fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(v: String) = v.split(".").mapNotNull { it.toIntOrNull() }
    val l = parts(latest)
    val c = parts(current)
    for (i in 0 until maxOf(l.size, c.size)) {
        val diff = (l.getOrElse(i) { 0 }) - (c.getOrElse(i) { 0 })
        if (diff != 0) return diff > 0
    }
    return false
}
