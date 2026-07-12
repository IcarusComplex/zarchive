package androidapp

import android.app.Application
import android.content.Context
import data.PlatformPaths

/**
 * Holds a static application Context reference so shared (jvmCommonMain) code — which has no
 * Activity/Context of its own — can resolve Android's app-private storage dirs via
 * `data.PlatformPaths`. Registered in AndroidManifest.xml as the app's `<application android:name>`.
 */
class ZArchiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        installCrashLogger()
    }

    companion object {
        lateinit var appContext: Context
            private set

        // Mirrors desktop's Main.kt installCrashLogger()/readAndClearCrashLog() -- same format,
        // same "read once then clear" contract -- just wired from Application.onCreate() instead
        // of main(), and written to PlatformPaths.debugDumpDir (Android's app-private cache dir)
        // instead of ~/zarchive-debug/. PlatformActions.android.kt's crashLogFile points at the
        // same file.
        private fun installCrashLogger() {
            val crashLog = PlatformPaths.debugDumpDir.resolve("crash.log")
            Thread.setDefaultUncaughtExceptionHandler { thread, e ->
                runCatching {
                    crashLog.parentFile?.mkdirs()
                    val ts = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    crashLog.appendText("[$ts] Thread: ${thread.name}\n${e.stackTraceToString()}\n\n")
                }
            }
        }

        // Reads and deletes the crash log from the previous session, if any.
        fun readAndClearCrashLog(): String? {
            val crashLog = PlatformPaths.debugDumpDir.resolve("crash.log")
            if (!crashLog.exists() || crashLog.length() == 0L) return null
            return runCatching {
                val content = crashLog.readText().trim()
                if (!crashLog.delete()) crashLog.writeText("")
                content.ifEmpty { null }
            }.getOrNull()
        }
    }
}
