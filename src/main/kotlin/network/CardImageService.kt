package network

import data.NOISE_RE
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

    // Scryfall set catalogue — resolves store-supplied set names to canonical set codes.
    // Loaded lazily and cached at ~/.zarchive/sets.json (refreshed every 7 days).
    private val setIndex = ScryfallSetIndex()

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

    private data class TitleMeta(
        val name: String,
        val setCode: String?,
        val setName: String?,   // full set name extracted from "Card - Set Name" or "Card (Set Name)"
        val treatment: String?,
    ) {
        // Cache key includes set and treatment so different printings coexist on disk.
        val cacheKey: String get() = buildString {
            append(name.lowercase())
            if (setCode != null) append("|$setCode")
            else if (setName != null) append("|${setName.lowercase()}")
            if (treatment != null) append("|$treatment")
        }
    }

    // Matches both "[RNA]" and "[PCY - 45]" (set code + optional collector number).
    private val SET_CODE_RE  = Regex("""\[([A-Z0-9]{2,6})(?:\s*[-/]\s*\d+)?\]""")
    // Matches content inside parens or after " - " that isn't a condition/noise/treatment keyword.
    // Used to detect set names like "(Multiverse Legends)" or "- Commander Masters".
    private val PAREN_SUFFIX_RE   = Regex("""\(([^)]{4,})\)""")
    private val DASH_SUFFIX_RE    = Regex(""" - (.{4,})$""")
    // Shopify stores like D20 Battleground encode set names in square brackets: "Card [Set Name]"
    // SET_CODE_RE already captures short codes like [RNA]; this catches longer names ([Multiverse Legends]).
    private val BRACKET_SET_NAME_RE = Regex("""\[([^\]]{4,})\]\s*$""")
    private val TREATMENT_WORD_RE = Regex(
        """(?i)\b(showcase|extended.?art|borderless|etched|full.?art|retro|galaxy.?foil|alt(?:ernate)?.?art)\b"""
    )
    // Rejects bare collector number tokens like "#242" from square-bracket suffixes.
    private val COLLECTOR_NUM_RE = Regex("""^#\d+$""")
    // Rejects "CODE-number" tokens like "WHO-823" from paren/bracket groups — these are collector
    // references, not set names. The code portion is handled separately by PAREN_SET_CODE_RE.
    private val CODE_NUMBER_RE = Regex("""^[A-Z]{2,6}-\d+$""")
    // Matches "CODE-number" notation anywhere in a title, e.g. "WHO-823" or "(WHO-823 - ...)".
    // The code must start at a non-alphanumeric boundary (prevents matching mid-word).
    // Validated against the set index before use to filter out non-set-code matches.
    private val PAREN_SET_CODE_RE = Regex("""(?<![A-Za-z0-9])([A-Z][A-Z0-9]{1,5})-\d+""")

    private fun extractSetNameHint(title: String): String? {
        for (re in listOf(PAREN_SUFFIX_RE, DASH_SUFFIX_RE, BRACKET_SET_NAME_RE)) {
            for (match in re.findAll(title)) {
                val candidate = match.groupValues.getOrNull(1)?.trim() ?: continue
                if (candidate.isBlank()) continue
                if (NOISE_RE.containsMatchIn(candidate)) continue
                if (TREATMENT_WORD_RE.containsMatchIn(candidate)) continue
                if (COLLECTOR_NUM_RE.matches(candidate)) continue
                if (CODE_NUMBER_RE.matches(candidate)) continue
                return candidate
            }
        }
        return null
    }

    private fun extractMeta(title: String, externalSetHint: String? = null): TitleMeta {
        val setCode   = SET_CODE_RE.find(title)?.groupValues?.get(1)?.lowercase()
        val treatment = TREATMENT_FILTERS.firstOrNull { (re, _) -> re.containsMatchIn(title) }?.second
        val setName   = when {
            treatment != null -> null                   // treatment filter overrides set
            externalSetHint != null -> externalSetHint  // payload hint beats title parsing
            setCode == null -> extractSetNameHint(title)
            else -> null
        }
        return TitleMeta(normalizeCardName(title), setCode, setName, treatment)
    }

    private fun fileFor(meta: TitleMeta): File {
        val sha = MessageDigest.getInstance("SHA-1")
            .digest(meta.cacheKey.lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$sha.jpg")
    }

    /** Map of listing title → local image file path. No set hints; delegates to the hint overload. */
    suspend fun resolveImages(titles: Collection<String>): Map<String, String> =
        resolveImages(titles.associateWith { null })

    /** Map of listing title → local image file path. titleToHint supplies optional per-title set hints. */
    suspend fun resolveImages(titleToHint: Map<String, String?>): Map<String, String> = coroutineScope {
        val baseMetas = titleToHint.entries
            .associate { (title, hint) -> title to extractMeta(title, hint) }
            .filterValues { it.name.isNotBlank() }

        // Resolve store-supplied set names / embedded codes to Scryfall set codes.
        // Priority: 1) already have a set code (SET_CODE_RE bracket), 2) setName hint from
        // title/payload → findCode, 3) "(CODE-number...)" paren notation (e.g. WHO-823).
        val titleToMeta = buildMap<String, TitleMeta> {
            for ((title, meta) in baseMetas) {
                val resolved = when {
                    meta.setCode != null -> meta
                    meta.setName != null -> {
                        val code = setIndex.findCode(meta.setName)
                        if (code != null) meta.copy(setCode = code)
                        else {
                            // setName might be a "CODE-NUMBER" SKU (e.g. "RVR-347") — extract code
                            val extracted = PAREN_SET_CODE_RE.find(meta.setName)?.groupValues?.get(1)
                            if (extracted != null && setIndex.isKnownCode(extracted))
                                meta.copy(setCode = extracted.lowercase())
                            else meta
                        }
                    }
                    else -> {
                        val parenCode = PAREN_SET_CODE_RE.find(title)?.groupValues?.get(1)
                        if (parenCode != null && setIndex.isKnownCode(parenCode))
                            meta.copy(setCode = parenCode.lowercase())
                        else meta
                    }
                }
                put(title, resolved)
            }
        }

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

        // 1.5. Child-set fallback batch — if a card wasn't found in the resolved set (e.g. the store
        //      says "Bloomburrow" but the card is only in "Bloomburrow Commander"), try child sets
        //      using Scryfall's parent_set_code relationship.  Results are stored under the original
        //      meta's cacheKey (base set), which is intentional — any Bloomburrow-family art is fine.
        run {
            data class ChildQuery(val originalMeta: TitleMeta, val childCode: String)
            val childQueries = notFound
                .filter { it.setCode != null }
                .flatMap { meta ->
                    setIndex.childCodesOf(meta.setCode!!).map { ChildQuery(meta, it) }
                }
            if (childQueries.isNotEmpty()) {
                delay(100)
                for (chunk in childQueries.chunked(75)) {
                    val childBody = buildJsonObject {
                        put("identifiers", buildJsonArray {
                            chunk.forEach { (meta, childCode) ->
                                add(buildJsonObject {
                                    put("name", meta.name)
                                    put("set", childCode)
                                })
                            }
                        })
                    }.toString()
                    val childResp = runCatching {
                        client.post("https://api.scryfall.com/cards/collection") {
                            contentType(ContentType.Application.Json)
                            setBody(childBody)
                        }.bodyAsText()
                    }.getOrNull()
                    if (childResp != null) {
                        runCatching { Json.parseToJsonElement(childResp).jsonObject["data"]?.jsonArray }
                            .getOrNull()?.forEach { entry ->
                                val obj  = entry.jsonObject
                                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@forEach
                                val set  = obj["set"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@forEach
                                val img  = imageUrlOf(obj) ?: return@forEach
                                // Map back to original meta via (name, childCode) lookup
                                chunk.filter { it.originalMeta.name.lowercase() == name && it.childCode == set }
                                    .forEach { q ->
                                        if (!result.containsKey(q.originalMeta.cacheKey))
                                            result[q.originalMeta.cacheKey] = img
                                    }
                            }
                    }
                    delay(100)
                }
                // Remove from notFound anything that was resolved by child-set batch
                notFound.removeAll { result.containsKey(it.cacheKey) }
            }
        }

        // 2. Set-name override — runs when a set name is available but we don't have (or couldn't
        //    use) a set code. Uses Scryfall's e:"Set Name" operator.
        //    - setCode == null: batch found generic art; override with set-specific printing.
        //    - setCode != null but batch missed: fallback in case the code didn't match Scryfall.
        for (meta in metas.filter { it.setName != null && (it.setCode == null || !result.containsKey(it.cacheKey)) }) {
            setNameSearch(meta.name, meta.setName!!)?.let { result[meta.cacheKey] = it }
            delay(80)
        }

        // 3. Treatment override — replace the canonical art with the treatment-specific art.
        //    Runs for every meta that has a detected treatment (showcase, borderless, etc.),
        //    regardless of whether the batch step found a result.
        for (meta in metas.filter { it.treatment != null }) {
            val url = treatmentSearch(meta.name, meta.setCode, meta.treatment!!) ?: continue
            result[meta.cacheKey] = url
            delay(80)
        }

        // 4. Fuzzy fallback for anything still missing
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

    // Scryfall set-name search — resolves the exact printing for a known set name.
    // "Friday Night Magic Promos" is a store-level category, not a Scryfall set name; use is:fnm.
    private suspend fun setNameSearch(name: String, setName: String): String? {
        val q = if (setName.contains("friday night magic", ignoreCase = true))
            "!\"$name\" is:fnm"
        else
            "!\"$name\" e:\"$setName\""
        val url = "https://api.scryfall.com/cards/search?q=${URLEncoder.encode(q, "UTF-8")}"
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
