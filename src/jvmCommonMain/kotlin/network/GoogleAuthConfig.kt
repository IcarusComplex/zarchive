package network

/**
 * Google Cloud Console OAuth client identifiers. These are *not* secrets for installed/native app
 * client types (Desktop app, Android) -- both are public clients that rely on PKCE, not a
 * confidential client secret, for security. This is a different situation from CLAUDE.md's "never
 * bundle a PAT/GitHub token" rule, which is about a genuinely sensitive bearer credential.
 *
 * Replace the placeholder values below with the real client IDs from your own Google Cloud project
 * (APIs & Services -> Credentials) before Phase 6 end-to-end testing:
 *   - One "Desktop app" client, used by [DESKTOP_CLIENT_ID]/[DESKTOP_CLIENT_SECRET].
 *   - Two "Android" clients (Android clients get no secret), one per package name + signing SHA-1:
 *     release ("co.za.zarchive") -> [ANDROID_CLIENT_ID_RELEASE], debug ("co.za.zarchive.debug")
 *     -> [ANDROID_CLIENT_ID_DEBUG].
 * Consent screen: External, scope = drive.file only, publishing status "In production" (see
 * CLAUDE.md decision #3 -- "Testing" imposes a hard 7-day refresh-token expiry).
 */
object GoogleAuthConfig {
    const val DESKTOP_CLIENT_ID = "REPLACE_WITH_DESKTOP_CLIENT_ID.apps.googleusercontent.com"
    const val DESKTOP_CLIENT_SECRET = "REPLACE_WITH_DESKTOP_CLIENT_SECRET"
    const val ANDROID_CLIENT_ID_RELEASE = "REPLACE_WITH_RELEASE_ANDROID_CLIENT_ID.apps.googleusercontent.com"
    const val ANDROID_CLIENT_ID_DEBUG = "REPLACE_WITH_DEBUG_ANDROID_CLIENT_ID.apps.googleusercontent.com"

    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val SYNC_FOLDER_NAME = "ZArchive"
    const val SYNC_BLOB_FILE_NAME = "zarchive-sync.json"
}
