package data

import java.io.File

/**
 * Per-platform cache/data directories. Desktop keeps today's `~/.zarchive/...` paths unchanged
 * (zero behavior change for existing users); Android wires to app-private storage
 * (`Context.filesDir`/`Context.cacheDir`) via `androidapp.ZArchiveApplication`'s context holder.
 */
expect object PlatformPaths {
    /** Scryfall card-art disk cache: `<dir>/<sha1(name)>.jpg`. */
    val imagesDir: File

    /** Cached Scryfall set catalogue, refreshed every 7 days. */
    val setsIndexFile: File

    /** Store-response debug dumps (429s, Warren API captures) — only written when debug-enabled. */
    val debugDumpDir: File
}
