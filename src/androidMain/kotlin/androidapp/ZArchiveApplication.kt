package androidapp

import android.app.Application
import android.content.Context

/**
 * Holds a static application Context reference so shared (jvmCommonMain) code — which has no
 * Activity/Context of its own — can resolve Android's app-private storage dirs via
 * `data.PlatformPaths`. Registered in AndroidManifest.xml as the app's `<application android:name>`.
 */
class ZArchiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
