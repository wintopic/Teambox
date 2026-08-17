package com.danmukey.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.danmukey.runtime.DanmuAccessibilityService
import com.danmukey.runtime.DanmuKeyboardService
import com.danmukey.shared.model.AppThemeMode
import com.danmukey.shared.model.AppThemePreference
import com.danmukey.shared.model.KeyboardAppearancePreference
import com.danmukey.shared.model.KeyboardColumnPreset
import com.danmukey.shared.model.KeyboardHeightPreset

/** Bridges ADB debug commands into the app's non-exported control channel. */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return

        if (intent?.action == ACTION_APPLY_APPEARANCE) {
            val preferences = context.getSharedPreferences(
                KeyboardAppearancePreference.STORAGE_NAME,
                Context.MODE_PRIVATE,
            )
            val editor = preferences.edit()
            intent.getStringExtra(EXTRA_THEME_MODE)?.let { value ->
                editor.putString(AppThemePreference.STORAGE_KEY, AppThemeMode.fromStorage(value).name)
            }
            intent.getStringExtra(EXTRA_KEYBOARD_HEIGHT)?.let { value ->
                editor.putString(
                    KeyboardAppearancePreference.HEIGHT_KEY,
                    KeyboardHeightPreset.fromStorage(value).name,
                )
            }
            intent.getStringExtra(EXTRA_KEYBOARD_COLUMNS)?.let { value ->
                editor.putString(
                    KeyboardAppearancePreference.COLUMN_KEY,
                    KeyboardColumnPreset.fromStorage(value).name,
                )
            }
            editor.apply()
            context.sendBroadcast(
                Intent(DanmuKeyboardService.ACTION_APPEARANCE_CHANGED).setPackage(context.packageName),
                DanmuAccessibilityService.CONTROL_PERMISSION,
            )
            return
        }

        val serviceAction = when (intent?.action) {
            ACTION_START_TEST_SEQUENCE -> DanmuAccessibilityService.ACTION_START_TEST_SEQUENCE
            ACTION_TARGET_PROFILES_CHANGED -> DanmuAccessibilityService.ACTION_TARGET_PROFILES_CHANGED
            ACTION_SHOW_TASK_CONTROL -> DanmuAccessibilityService.ACTION_SHOW_TASK_CONTROL
            ACTION_HIDE_TASK_CONTROL -> DanmuAccessibilityService.ACTION_HIDE_TASK_CONTROL
            ACTION_START_CALIBRATION -> DanmuAccessibilityService.ACTION_START_CALIBRATION
            ACTION_START_TEMPLATE_CAPTURE -> DanmuAccessibilityService.ACTION_START_TEMPLATE_CAPTURE
            ACTION_STOP_TASK -> DanmuAccessibilityService.ACTION_STOP_TASK
            else -> return
        }

        val serviceIntent = Intent(serviceAction).setPackage(context.packageName)
        if (intent.action in TEST_HOST_ONLY_ACTIONS) {
            serviceIntent.putExtra(
                DanmuAccessibilityService.EXTRA_REQUIRED_TARGET_PACKAGE,
                TEST_HOST_PACKAGE,
            )
        }
        context.sendBroadcast(serviceIntent)
    }

    companion object {
        const val ACTION_START_TEST_SEQUENCE =
            "com.danmukey.debug.action.START_TEST_SEQUENCE"
        const val ACTION_TARGET_PROFILES_CHANGED =
            "com.danmukey.debug.action.TARGET_PROFILES_CHANGED"
        const val ACTION_SHOW_TASK_CONTROL =
            "com.danmukey.debug.action.SHOW_TASK_CONTROL"
        const val ACTION_HIDE_TASK_CONTROL =
            "com.danmukey.debug.action.HIDE_TASK_CONTROL"
        const val ACTION_START_CALIBRATION =
            "com.danmukey.debug.action.START_CALIBRATION"
        const val ACTION_START_TEMPLATE_CAPTURE =
            "com.danmukey.debug.action.START_TEMPLATE_CAPTURE"
        const val ACTION_STOP_TASK =
            "com.danmukey.debug.action.STOP_TASK"
        const val ACTION_APPLY_APPEARANCE =
            "com.danmukey.debug.action.APPLY_APPEARANCE"
        const val EXTRA_THEME_MODE = "theme_mode"
        const val EXTRA_KEYBOARD_HEIGHT = "keyboard_height"
        const val EXTRA_KEYBOARD_COLUMNS = "keyboard_columns"

        private const val TEST_HOST_PACKAGE = "com.danmukey.testhost"
        private val TEST_HOST_ONLY_ACTIONS = setOf(
            ACTION_START_TEST_SEQUENCE,
            ACTION_START_TEMPLATE_CAPTURE,
        )
    }
}
