package com.bopomofo.t9ime.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.bopomofo.t9ime.R

enum class AppTheme(val id: String, val displayName: String) {
    FOLLOW_SYSTEM("system", "跟隨系統"),
    LIGHT("light", "簡潔純白"),
    DARK("dark", "黑曜極致"),
    OCEAN("ocean", "蔚藍海洋"),
    FOREST("forest", "台灣森林綠")
}

data class ThemeColors(
    val bg: Int,
    val keyBg: Int,
    val keyPressed: Int,
    val actionKeyBg: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val candidateBg: Int,
    val candidateText: Int,
    val accent: Int
)

object ThemeManager {
    private const val PREF_NAME = "ime_prefs"
    private const val PREF_THEME = "pref_theme"

    fun getCurrentTheme(context: Context): AppTheme {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString(PREF_THEME, AppTheme.FOLLOW_SYSTEM.id) ?: AppTheme.FOLLOW_SYSTEM.id
        return AppTheme.values().find { it.id == id } ?: AppTheme.FOLLOW_SYSTEM
    }

    fun setTheme(context: Context, theme: AppTheme) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_THEME, theme.id).apply()
    }

    fun getThemeColors(context: Context, theme: AppTheme): ThemeColors {
        val isSystemNight = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        return when (theme) {
            AppTheme.FOLLOW_SYSTEM -> {
                if (isSystemNight) getThemeColors(context, AppTheme.DARK)
                else getThemeColors(context, AppTheme.LIGHT)
            }
            AppTheme.LIGHT -> ThemeColors(
                bg = Color.parseColor("#F4F4F6"),
                keyBg = Color.parseColor("#FFFFFF"),
                keyPressed = Color.parseColor("#E2E4E8"),
                actionKeyBg = Color.parseColor("#E8EAED"),
                textPrimary = Color.parseColor("#202124"),
                textSecondary = Color.parseColor("#70757A"),
                candidateBg = Color.parseColor("#FFFFFF"),
                candidateText = Color.parseColor("#1A73E8"),
                accent = Color.parseColor("#1A73E8")
            )
            AppTheme.DARK -> ThemeColors(
                bg = Color.parseColor("#1E1F22"),
                keyBg = Color.parseColor("#2B2D31"),
                keyPressed = Color.parseColor("#3F4248"),
                actionKeyBg = Color.parseColor("#232428"),
                textPrimary = Color.parseColor("#F2F3F5"),
                textSecondary = Color.parseColor("#949BA4"),
                candidateBg = Color.parseColor("#1E1F22"),
                candidateText = Color.parseColor("#58A6FF"),
                accent = Color.parseColor("#58A6FF")
            )
            AppTheme.OCEAN -> ThemeColors(
                bg = Color.parseColor("#0B192C"),
                keyBg = Color.parseColor("#1E3E62"),
                keyPressed = Color.parseColor("#2D5789"),
                actionKeyBg = Color.parseColor("#152D4A"),
                textPrimary = Color.parseColor("#F1F6F9"),
                textSecondary = Color.parseColor("#9BA4B5"),
                candidateBg = Color.parseColor("#0E2238"),
                candidateText = Color.parseColor("#00D2D3"),
                accent = Color.parseColor("#00D2D3")
            )
            AppTheme.FOREST -> ThemeColors(
                bg = Color.parseColor("#14281D"),
                keyBg = Color.parseColor("#274735"),
                keyPressed = Color.parseColor("#356048"),
                actionKeyBg = Color.parseColor("#1B3527"),
                textPrimary = Color.parseColor("#E8F5E9"),
                textSecondary = Color.parseColor("#A3C9A8"),
                candidateBg = Color.parseColor("#172F22"),
                candidateText = Color.parseColor("#4EBA6F"),
                accent = Color.parseColor("#4EBA6F")
            )
        }
    }

    /**
     * 應用色彩至鍵盤視圖階層
     */
    fun applyTheme(root: View, theme: AppTheme) {
        val colors = getThemeColors(root.context, theme)
        root.setBackgroundColor(colors.bg)

        // 遞迴設定按鈕與文字色（自訂 SwipeKeyButton / Button / TextView）
        applyRecursive(root, colors)
    }

    private fun applyRecursive(view: View, colors: ThemeColors) {
        when (view) {
            is Button -> {
                view.setTextColor(colors.textPrimary)
            }
            is TextView -> {
                // 排除特定指示器或保留候選字色彩
                if (view.id != R.id.candidate_more_indicator) {
                    // 若是一般標籤
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i), colors)
            }
        }
    }
}
