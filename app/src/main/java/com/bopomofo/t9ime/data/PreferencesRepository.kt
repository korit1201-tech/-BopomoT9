package com.bopomofo.t9ime.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 集中管理輸入法偏好設定的資料儲存庫 (PreferencesRepository)
 * 解決鍵值字串散落與避免魔術字串拼寫錯誤，內建安全例外防護
 */
object PreferencesRepository {
    private const val PREF_NAME = "ime_prefs"

    // 鍵值常數定義
    const val KEY_VIBRATION_ENABLED = "pref_vibration_enabled"
    const val KEY_VIBRATION_STRENGTH = "pref_vibration_strength"
    const val KEY_VIBRATION_MS = "pref_vibration_ms"
    const val KEY_KEYBOARD_HEIGHT_DP = "pref_keyboard_height_dp"
    const val KEY_ONE_HANDED_MODE = "pref_one_handed_mode"
    const val KEY_THEME = "pref_theme"

    // 預設值
    const val DEFAULT_VIBRATION_ENABLED = true
    const val DEFAULT_VIBRATION_STRENGTH = 30
    const val DEFAULT_VIBRATION_MS = 25
    const val DEFAULT_KEYBOARD_HEIGHT_DP = 240
    const val DEFAULT_ONE_HANDED_MODE = "full"
    const val DEFAULT_THEME = "system"

    private fun getPrefs(context: Context): SharedPreferences? {
        return try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    fun isVibrationEnabled(context: Context): Boolean {
        return try {
            getPrefs(context)?.getBoolean(KEY_VIBRATION_ENABLED, DEFAULT_VIBRATION_ENABLED) ?: DEFAULT_VIBRATION_ENABLED
        } catch (_: Exception) {
            DEFAULT_VIBRATION_ENABLED
        }
    }

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        try {
            getPrefs(context)?.edit()?.putBoolean(KEY_VIBRATION_ENABLED, enabled)?.apply()
        } catch (_: Exception) {}
    }

    fun getVibrationStrength(context: Context): Int {
        return try {
            getPrefs(context)?.getInt(KEY_VIBRATION_STRENGTH, DEFAULT_VIBRATION_STRENGTH) ?: DEFAULT_VIBRATION_STRENGTH
        } catch (_: Exception) {
            DEFAULT_VIBRATION_STRENGTH
        }
    }

    fun setVibrationStrength(context: Context, strength: Int) {
        try {
            getPrefs(context)?.edit()?.putInt(KEY_VIBRATION_STRENGTH, strength)?.apply()
        } catch (_: Exception) {}
    }

    fun getVibrationMs(context: Context): Int {
        return try {
            getPrefs(context)?.getInt(KEY_VIBRATION_MS, DEFAULT_VIBRATION_MS) ?: DEFAULT_VIBRATION_MS
        } catch (_: Exception) {
            DEFAULT_VIBRATION_MS
        }
    }

    fun setVibrationMs(context: Context, ms: Int) {
        try {
            getPrefs(context)?.edit()?.putInt(KEY_VIBRATION_MS, ms)?.apply()
        } catch (_: Exception) {}
    }

    fun getKeyboardHeightDp(context: Context): Int {
        return try {
            getPrefs(context)?.getInt(KEY_KEYBOARD_HEIGHT_DP, DEFAULT_KEYBOARD_HEIGHT_DP) ?: DEFAULT_KEYBOARD_HEIGHT_DP
        } catch (_: Exception) {
            DEFAULT_KEYBOARD_HEIGHT_DP
        }
    }

    fun setKeyboardHeightDp(context: Context, dp: Int) {
        try {
            getPrefs(context)?.edit()?.putInt(KEY_KEYBOARD_HEIGHT_DP, dp)?.apply()
        } catch (_: Exception) {}
    }

    fun getOneHandedMode(context: Context): String {
        return try {
            getPrefs(context)?.getString(KEY_ONE_HANDED_MODE, DEFAULT_ONE_HANDED_MODE) ?: DEFAULT_ONE_HANDED_MODE
        } catch (_: Exception) {
            DEFAULT_ONE_HANDED_MODE
        }
    }

    fun setOneHandedMode(context: Context, mode: String) {
        try {
            getPrefs(context)?.edit()?.putString(KEY_ONE_HANDED_MODE, mode)?.apply()
        } catch (_: Exception) {}
    }

    fun getTheme(context: Context): String {
        return try {
            getPrefs(context)?.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
        } catch (_: Exception) {
            DEFAULT_THEME
        }
    }

    fun setTheme(context: Context, themeId: String) {
        try {
            getPrefs(context)?.edit()?.putString(KEY_THEME, themeId)?.apply()
        } catch (_: Exception) {}
    }
}
