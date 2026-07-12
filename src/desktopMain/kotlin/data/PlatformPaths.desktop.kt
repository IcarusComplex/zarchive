package data

import java.io.File

actual object PlatformPaths {
    private val home = System.getProperty("user.home")

    actual val imagesDir: File =
        File(home, ".zarchive/images").also { it.mkdirs() }

    actual val setsIndexFile: File =
        File(home, ".zarchive/sets.json")

    actual val debugDumpDir: File =
        File(home, "zarchive-debug").also { it.mkdirs() }
}
