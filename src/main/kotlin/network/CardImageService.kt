package network

import data.normalizeCardName
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Resolves a store listing title to its official Scryfall card art and caches the image on disk.
 *
 * Resolution order:
 *   1. Disk cache hit  →  return immediately.
 *   2. Batch /cards/collection with {name, set}  →  gets the canonical printing.
 *   3. Treatment override  →  for showcase / borderless / extended-art / etched listings the batch
 *      result shows the wrong art; we run a targeted /cards/search with the appropriate Scryfall
 *      filter to find the treatment-specific image.
 *   4. Fuzzy /cards/named fallback for anything still unresolved.
 *
 * Disk cache key includes treatment so showcase and default art for the same card coexist.
 */
class CardImageService : AutoCloseable {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 12_000 }
        install(DefaultRequest) {
            header(HttpHeaders.UserAgent, "zarchive/1.0 (SA MTG singles price tool)")
            header(HttpHeaders.Accept, "application/json")
        }
    }

    // Per-user cache under the home dir so it works regardless of the app's install location
    // (a packaged build's working dir may be read-only). ~/.zarchive/images/<sha1>.jpg
    private val dir = File(System.getProperty("user.home"), ".zarchive/images").also { it.mkdirs() }

    // Treatment keyword regex → Scryfall search filter
    private val TREATMENT_FILTERS = listOf(
        Regex("""(?i)\bshowcase\b""")                         to "frame:showcase",
        Regex("""(?i)\bextended[-\s]?art\b""")                to "frame:extendedart",
        Regex("""(?i)\bborderless\b""")                       to "border:borderless",
        Regex("""(?i)\betched\b""")                           to "finish:etched",
        Regex("""(?i)\bfull[-\s]?art\b""")                   to "is:full",
        Regex("""(?i)\bretro\b""")                            to "frame:retro",
        Regex("""(?i)\bgalaxy[-\s]?foil\b""")                 to "frame:galaxy",
        Regex("""(?i)\balternate?[-\s]?art\b|alt[-\s]?art\b""") to "unique:art",
    )

    private data class TitleMeta(val name: String, val setCode: String?, val treatment: String?) {
        // Cache key includes treatment so showcase and default art don't collide on disk.
        val cacheKey: String get() = buildString {
            append(name.lowercase())
            if (setCode != null) append("|$setCode")
            if (treatment != null) append("|$treatment")
        }
    }

    private val SET_CODE_RE = Regex("""\[([A-Z0-9]{2,6})\]""")

    private fun extractMeta(title: String): TitleMeta {
        val setCode   = SET_CODE_RE.find(title)?.groupValues?.get(1)?.lowercase()
        val treatment = TREATMENT_FILTERS.firstOrNull { (re, _) -> re.containsMatchIn(title) }?.second
        return TitleMeta(normalizeCardName(title), setCode, treatment)
    }

    private fun fileFor(meta: TitleMeta): File {
        val sha = MessageDigest.getInstance("SHA-1")
            .digest(meta.cacheKey.lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$sha.jpg")
    }

    /** Map of listing title → local image file path. */
    suspend fun resolveImages(titles: Collection<String>): Map<String, String> = coroutineScope {
        val titleToMeta = titles.associateWith { extractMeta(it) }.filterValues { it.name.isNotBlank() }
        val uniqueMetas = titleToMeta.values.distinctBy { it.cacheKey }

        val metaToPath = mutableMapOf<String, String>()
        val toFetch    = mutableListOf<TitleMeta>()
        for (meta in uniqueMetas) {
            val f = fileFor(meta)
            if (f.exists() && f.length() > 0) metaToPath[meta.cacheKey] = f.absolutePath
            else toFetch += meta
        }

        if (toFetch.isNotEmpty()) {
            val urls = resolveImageUrls(toFetch)
            val sem  = Semaphore(6)
            val downloaded = toFetch.mapNotNull { meta ->
                val url = urls[meta.cacheKey] ?: return@mapNotNull null
                async(Dispatchers.IO) {
                    sem.withPermit {
                        runCatching {
                            val bytes = client.get(url).readBytes()
                            if (bytes.isNotEmpty()) {
                                val f = fileFor(meta)
                                f.writeBytes(bytes)
                                meta.cacheKey to f.absolutePath
                            } else null
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
            metaToPath.putAll(downloaded)
        }

        buildMap {
            for ((title, meta) in titleToMeta) {
                metaToPath[meta.cacheKey]?.let { put(title, it) }
            }
        }
    }

    private suspend fun resolveImageUrls(metas: List<TitleMeta>): Map<String, String> {
        val result   = mutableMapOf<String, String>()
        val notFound = mutableListOf<TitleMeta>()

        // 1. Batch /cards/collection — gets canonical (default) art for each name+set
        for (chunk in metas.chunked(75)) {
            val requestBody = buildJsonObject {
                put("identifiers", buildJsonArray {
                    chunk.forEach { meta ->
                        add(buildJsonObject {
                            put("name", meta.name)
                            if (meta.setCode != null) put("set", meta.setCode)
                        })
                    }
                })
            }.toString()

            val resp = runCatching {
                client.post("https://api.scryfall.com/cards/collection") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }.bodyAsText()
            }.getOrNull()

            val byNameSet = mutableMapOf<Pair<String, String?>, String>()
            if (resp != null) {
                runCatching { Json.parseToJsonElement(resp).jsonObject["data"]?.jsonArray }
                    .getOrNull()?.forEach { entry ->
                        val obj  = entry.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@forEach
                        val set  = obj["set"]?.jsonPrimitive?.contentOrNull?.lowercase()
                        val img  = imageUrlOf(obj) ?: return@forEach
                        byNameSet[name to set] = img
                        byNameSet.putIfAbsent(name to null, img)
                    }
            }

            for (meta in chunk) {
                val key = meta.name.lowercase()
                val img = byNameSet[key to meta.setCode] ?: byNameSet[key to null]
                if (img != null) result[meta.cacheKey] = img else notFound += meta
            }
            delay(100)
        }

        // 2. Treatment override — replace the canonical art with the treatment-specific art.
        //    Runs for every meta that has a detected treatment (showcase, borderless, etc.),
        //    regardless of whether the batch step found a result.
        for (meta in metas.filter { it.treatment != null }) {
            val url = treatmentSearch(meta.name, meta.setCode, meta.treatment!!) ?: continue
            result[meta.cacheKey] = url
            delay(80)
        }

        // 3. Fuzzy fallback for anything still missing
        for (meta in notFound) {
            if (result.containsKey(meta.cacheKey)) continue
            fuzzyResolve(meta.name)?.let { result[meta.cacheKey] = it }
            delay(100)
        }
        return result
    }

    // Scryfall /cards/search with treatment filter, e.g.:
    //   !"Ragavan, Nimble Pilferer" set:mh2 frame:showcase
    private suspend fun treatmentSearch(name: String, setCode: String?, scryfallFilter: String): String? {
        val q = buildString {
            append("!\""); append(name); append("\"")
            if (setCode != null) { append(" set:"); append(setCode) }
            append(" "); append(scryfallFilter)
        }
        val url = "https://api.scryfall.com/cards/search?q=${URLEncoder.encode(q, "UTF-8")}&unique=art"
        val resp = runCatching { client.get(url).bodyAsText() }.getOrNull() ?: return null
        val obj  = runCatching { Json.parseToJsonElement(resp).jsonObject }.getOrNull() ?: return null
        if (obj["object"]?.jsonPrimitive?.contentOrNull == "error") return null
        return obj["data"]?.jsonArray?.firstOrNull()?.jsonObject?.let { imageUrlOf(it) }
    }

    private suspend fun fuzzyResolve(name: String): String? {
        val url  = "https://api.scryfall.com/cards/named?fuzzy=" + URLEncoder.encode(name, "UTF-8")
        val resp = runCatching { client.get(url).bodyAsText() }.getOrNull() ?: return null
        val obj  = runCatching { Json.parseToJsonElement(resp).jsonObject }.getOrNull() ?: return null
        if (obj["object"]?.jsonPrimitive?.contentOrNull == "error") return null
        return imageUrlOf(obj)
    }

    private fun imageUrlOf(obj: JsonObject): String? =
        obj["image_uris"]?.jsonObject?.get("normal")?.jsonPrimitive?.contentOrNull
            ?: obj["card_faces"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("image_uris")?.jsonObject?.get("normal")?.jsonPrimitive?.contentOrNull

    override fun close() { client.close() }
}
