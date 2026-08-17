package com.danmukey.runtime

import android.content.Context
import android.util.Log
import java.io.File

/** Removes full-screen files written by early debug builds before capture became memory-only. */
object ProjectionCapturePrivacy {
    fun clearLegacyPersistedFrames(context: Context) {
        val cleanup = LegacyProjectionArtifactCleaner.clear(context.cacheDir)
        val preferences = context.getSharedPreferences(
            ProjectionCaptureService.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val hadLegacyPath = preferences.contains(KEY_LEGACY_CAPTURE_PATH)
        val hadLegacySessionMarker = preferences.contains(KEY_LEGACY_SESSION_ACTIVE)
        val preferenceCleared = (!hadLegacyPath && !hadLegacySessionMarker) || preferences.edit()
            .remove(KEY_LEGACY_CAPTURE_PATH)
            .remove(KEY_LEGACY_SESSION_ACTIVE)
            .commit()

        if (cleanup.artifactsFound || hadLegacyPath || hadLegacySessionMarker) {
            Log.i(
                TAG,
                "Legacy projection cleanup artifactsDeleted=${cleanup.deleted} " +
                    "pathPreferenceCleared=$preferenceCleared",
            )
        }
        if (!cleanup.deleted || !preferenceCleared) {
            Log.w(TAG, "Legacy projection privacy cleanup did not fully complete")
        }
    }

    private const val KEY_LEGACY_CAPTURE_PATH = "last_capture_path"
    private const val KEY_LEGACY_SESSION_ACTIVE = "session_active"
    private const val TAG = "DanmuProjectionPrivacy"
}

internal object LegacyProjectionArtifactCleaner {
    fun clear(cacheDirectory: File): LegacyProjectionCleanupResult {
        val legacyDirectory = File(cacheDirectory, LEGACY_CAPTURE_DIRECTORY)
        val artifactsFound = legacyDirectory.exists()
        val deleted = !artifactsFound || runCatching { legacyDirectory.deleteRecursively() }
            .getOrDefault(false)
        return LegacyProjectionCleanupResult(
            artifactsFound = artifactsFound,
            deleted = deleted && !legacyDirectory.exists(),
        )
    }

    private const val LEGACY_CAPTURE_DIRECTORY = "projection"
}

internal data class LegacyProjectionCleanupResult(
    val artifactsFound: Boolean,
    val deleted: Boolean,
)
