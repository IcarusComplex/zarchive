package data

import androidapp.ZArchiveApplication
import java.io.File

actual object PlatformPaths {
    private val filesDir: File get() = ZArchiveApplication.appContext.filesDir
    private val cacheDir: File get() = ZArchiveApplication.appContext.cacheDir

    actual val imagesDir: File
        get() = File(filesDir, "images").also { it.mkdirs() }

    actual val setsIndexFile: File
        get() = File(filesDir, "sets.json")

    actual val debugDumpDir: File
        get() = File(cacheDir, "debug").also { it.mkdirs() }
}
