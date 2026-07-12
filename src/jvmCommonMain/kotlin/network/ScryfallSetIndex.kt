package network

import data.PlatformPaths
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Caches the full Scryfall set catalogue (~/.zarchive/sets.json, refreshed every 7 days)
 * and resolves a store-supplied set hint to the canonical Scryfall set code.
 *
 * Matching strategy (in order):
 *  1. Exact set-name match (case-insensitive).
 *  2. Hint is contained in a set name — takes the shortest (most specific) match.
 *     Handles "Warhammer 40,000" → "Warhammer 40,000 Commander".
 *  3. A set name is contained in the hint — takes the longest match.
 *     Handles extra brand/category words the store prepended.
 *
 * "Universes Beyond: " is stripped as a pure brand prefix before matching.
 *
 * Also exposes [childCodesOf] for parent→child set fallback (e.g. "blb" → ["blc"]) so the
 * image service can try Commander/companion sets when a card isn't found in the base set.
 */
class ScryfallSetIndex {

    private val cacheFile = PlatformPaths.setsIndexFile
    private val mutex = Mutex()

    // set name (lowercase, trimmed) → Scryfall set code
    private var nameToCode: Map<String, String> = emptyMap()
    // all known set codes (lowercase) for fast membership checks
    private var codeSet: Set<String> = emptySet()
    // parent set code → list of direct child set codes (Commander precons, etc.)
    private var parentToChildren: Map<String, List<String>> = emptyMap()
    private var ready = false

    /** Returns the Scryfall set code that best matches [hint], or null if no match found. */
    suspend fun findCode(hint: String): String? {
        ensureLoaded()
        if (nameToCode.isEmpty()) return null

        // Strip "Universes Beyond: " brand prefix added by WotC/stores but absent from Scryfall names
        var h = hint.lowercase().trim()
        for (prefix in listOf("universes beyond: ", "universes beyond ")) {
            if (h.startsWith(prefix)) { h = h.removePrefix(prefix).trim(); break }
        }
        if (h.isBlank()) return null

        // 1. Exact match
        nameToCode[h]?.let { return it }

        // 2. h is contained in a known set name (hint too short/abbreviated)
        //    e.g. "Warhammer 40,000" in "Warhammer 40,000 Commander" → "40k"
        //    Prefer the shortest (most specific) matching set name.
        nameToCode.entries
            .filter { (name, _) -> name.length > h.length && name.contains(h) }
            .minByOrNull { (name, _) -> name.length }
            ?.value?.let { return it }

        // 3. A known set name is contained in h (hint has extra prefix/suffix words)
        //    e.g. "Doctor Who" in "Doctor Who Foil" or brand prefix already stripped above.
        //    Prefer the longest (most specific) matching set name.
        nameToCode.entries
            .filter { (name, _) -> name.length >= 8 && h.contains(name) }
            .maxByOrNull { (name, _) -> name.length }
            ?.value?.let { return it }

        return null
    }

    /** Returns true if [code] is a known Scryfall set code (case-insensitive). */
    suspend fun isKnownCode(code: String): Boolean {
        ensureLoaded()
        return codeSet.contains(code.lowercase())
    }

    /**
     * Returns direct child set codes for [parentCode] (e.g. Commander precons for a base set).
     * Used as fallback: if a card isn't found in the base set, try its Commander siblings.
     * Example: "blb" → ["blc"] (Bloomburrow Commander)
     */
    suspend fun childCodesOf(parentCode: String): List<String> {
        ensureLoaded()
        return parentToChildren[parentCode.lowercase()] ?: emptyList()
    }

    private suspend fun ensureLoaded() {
        if (ready) return
        mutex.withLock {
            if (ready) return
            val (names, children) = withContext(Dispatchers.IO) {
                loadFromCache() ?: fetchFromScryfall()
            } ?: (emptyMap<String, String>() to emptyMap<String, List<String>>())
            nameToCode = names
            parentToChildren = children
            codeSet = names.values.map { it.lowercase() }.toSet()
            ready = true
        }
    }

    private fun loadFromCache(): Pair<Map<String, String>, Map<String, List<String>>>? {
        if (!cacheFile.exists()) return null
        val ageDays = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(cacheFile.lastModified()), Instant.now()
        )
        if (ageDays > 7) return null
        return runCatching { parseSetJson(cacheFile.readText()) }.getOrNull()
    }

    private suspend fun fetchFromScryfall(): Pair<Map<String, String>, Map<String, List<String>>>? {
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 12_000 }
        }
        return try {
            val body = client.get("https://api.scryfall.com/sets") {
                header(HttpHeaders.UserAgent, "zarchive/1.0 (SA MTG singles price tool)")
                header(HttpHeaders.Accept, "application/json")
            }.bodyAsText()
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(body)
            parseSetJson(body)
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }

    private fun parseSetJson(json: String): Pair<Map<String, String>, Map<String, List<String>>>? =
        runCatching {
            val sets = Json.parseToJsonElement(json).jsonObject["data"]?.jsonArray
                ?: return@runCatching null

            val nameToCode  = mutableMapOf<String, String>()
            val childrenOf  = mutableMapOf<String, MutableList<String>>()

            for (el in sets) {
                val obj    = el.jsonObject
                // Skip token and minigame sets — stores never sell singles from them, and
                // their names (e.g. "Warhammer 40,000 Tokens") would shadow the real set
                // names in substring matching ("Warhammer 40,000 Commander").
                val type   = obj["set_type"]?.jsonPrimitive?.contentOrNull
                if (type == "token" || type == "minigame") continue
                val name   = obj["name"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: continue
                val code   = obj["code"]?.jsonPrimitive?.contentOrNull              ?: continue
                val parent = obj["parent_set_code"]?.jsonPrimitive?.contentOrNull

                nameToCode[name] = code
                if (parent != null) {
                    childrenOf.getOrPut(parent.lowercase()) { mutableListOf() }.add(code)
                }
            }

            nameToCode to childrenOf
        }.getOrNull()
}
