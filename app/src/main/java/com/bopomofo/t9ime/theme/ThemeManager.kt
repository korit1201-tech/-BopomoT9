package com.bopomofo.t9ime.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.bopomofo.t9ime.R

enum class AppTheme(val id: String, val displayName: String) {
    FOLLOW_SYSTEM("system", "跟隨系統"),
    LIGHT("light", "簡潔純白"),
    DARK("dark", "黑曜極致"),
    SKY("sky", "晴空蔚藍"),
    SAKURA("sakura", "櫻花粉黛"),
    SUNSET("sunset", "日落暖橙"),
    MINT("mint", "薄荷青翠"),
    PARCHMENT("parchment", "復古羊皮")
}

data class ThemeColors(
    val bg: Int,
    val keyBg: Int,
    val keyPressed: Int,
    val actionKeyBg: Int,
    val stroke: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val candidateBg: Int,
    val candidateText: Int,
    val accent: Int
)

object ThemeManager {
    private const val PREF_NAME = "ime_prefs"
    private const val PREF_THEME = "pref_theme"

    private val ACTION_KEY_IDS = setOf(
        R.id.btn_backspace,
        R.id.btn_clear,
        R.id.btn_symbol_drawer,
        R.id.btn_candidate_expand,
        R.id.btn_candidate_grid_close,
        R.id.btn_mode_123,
        R.id.btn_qwerty_toggle,
        R.id.btn_lang_toggle,
        R.id.btn_sym_at,
        R.id.btn_sym_1,
        R.id.btn_sym_2,
        R.id.btn_sym_3,
        R.id.btn_sym_4,
        R.id.btn_sym_5,
        R.id.btn_handwriting_clear
    )

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
                bg = Color.parseColor("#E5E7EB"),
                keyBg = Color.parseColor("#FFFFFF"),
                keyPressed = Color.parseColor("#D1D5DB"),
                actionKeyBg = Color.parseColor("#D1D5DB"),
                stroke = Color.parseColor("#CBD5E1"),
                textPrimary = Color.parseColor("#111827"),
                textSecondary = Color.parseColor("#4B5563"),
                candidateBg = Color.parseColor("#FFFFFF"),
                candidateText = Color.parseColor("#2563EB"),
                accent = Color.parseColor("#2563EB")
            )
            AppTheme.DARK -> ThemeColors(
                bg = Color.parseColor("#0F0F11"),
                keyBg = Color.parseColor("#212124"),
                keyPressed = Color.parseColor("#38383D"),
                actionKeyBg = Color.parseColor("#18181B"),
                stroke = Color.parseColor("#2E2E33"),
                textPrimary = Color.parseColor("#F9FAFB"),
                textSecondary = Color.parseColor("#9CA3AF"),
                candidateBg = Color.parseColor("#18181B"),
                candidateText = Color.parseColor("#60A5FA"),
                accent = Color.parseColor("#60A5FA")
            )
            AppTheme.SKY -> ThemeColors(
                bg = Color.parseColor("#BAE6FD"),
                keyBg = Color.parseColor("#FFFFFF"),
                keyPressed = Color.parseColor("#E0F2FE"),
                actionKeyBg = Color.parseColor("#7DD3FC"),
                stroke = Color.parseColor("#38BDF8"),
                textPrimary = Color.parseColor("#0369A1"),
                textSecondary = Color.parseColor("#0284C7"),
                candidateBg = Color.parseColor("#E0F2FE"),
                candidateText = Color.parseColor("#0284C7"),
                accent = Color.parseColor("#0284C7")
            )
            AppTheme.SAKURA -> ThemeColors(
                bg = Color.parseColor("#FCE7F3"),
                keyBg = Color.parseColor("#FFFFFF"),
                keyPressed = Color.parseColor("#FDF2F8"),
                actionKeyBg = Color.parseColor("#FBCFE8"),
                stroke = Color.parseColor("#F472B6"),
                textPrimary = Color.parseColor("#9D174D"),
                textSecondary = Color.parseColor("#BE185D"),
                candidateBg = Color.parseColor("#FDF2F8"),
                candidateText = Color.parseColor("#DB2777"),
                accent = Color.parseColor("#DB2777")
            )
            AppTheme.SUNSET -> ThemeColors(
                bg = Color.parseColor("#FFEDD5"),
                keyBg = Color.parseColor("#FFFFFF"),
                keyPressed = Color.parseColor("#FFF7ED"),
                actionKeyBg = Color.parseColor("#FED7AA"),
                stroke = Color.parseColor("#FB923C"),
                textPrimary = Color.parseColor("#9A3412"),
                textSecondary = Color.parseColor("#C2410C"),
                candidateBg = Color.parseColor("#FFF7ED"),
                candidateText = Color.parseColor("#EA580C"),
                accent = Color.parseColor("#EA580C")
            )
            AppTheme.MINT -> ThemeColors(
                bg = Color.parseColor("#D1FAE5"),
                keyBg = Color.parseColor("#FFFFFF"),
                keyPressed = Color.parseColor("#ECFDF5"),
                actionKeyBg = Color.parseColor("#A7F3D0"),
                stroke = Color.parseColor("#34D399"),
                textPrimary = Color.parseColor("#065F46"),
                textSecondary = Color.parseColor("#047857"),
                candidateBg = Color.parseColor("#ECFDF5"),
                candidateText = Color.parseColor("#059669"),
                accent = Color.parseColor("#059669")
            )
            AppTheme.PARCHMENT -> ThemeColors(
                bg = Color.parseColor("#EADBC8"),
                keyBg = Color.parseColor("#FAF6F0"),
                keyPressed = Color.parseColor("#F3EBE1"),
                actionKeyBg = Color.parseColor("#DAC0A3"),
                stroke = Color.parseColor("#B59A7A"),
                textPrimary = Color.parseColor("#43281C"),
                textSecondary = Color.parseColor("#5A3E2B"),
                candidateBg = Color.parseColor("#FAF6F0"),
                candidateText = Color.parseColor("#7F4F24"),
                accent = Color.parseColor("#7F4F24")
            )
        }
    }

    private fun createKeyDrawable(bgColor: Int, pressedColor: Int, strokeColor: Int, radiusPx: Float): StateListDrawable {
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(bgColor)
            setStroke(2, strokeColor)
        }
        val pressed = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(pressedColor)
            setStroke(2, strokeColor)
        }
        val stateList = StateListDrawable()
        stateList.addState(intArrayOf(android.R.attr.state_pressed), pressed)
        stateList.addState(intArrayOf(), normal)
        return stateList
    }

    /**
     * 應用色彩至鍵盤視圖階層
     */
    fun applyTheme(root: View, theme: AppTheme) {
        val colors = getThemeColors(root.context, theme)
        root.setBackgroundColor(colors.bg)

        val density = root.resources.displayMetrics.density
        val radiusPx = 6f * density

        applyRecursive(root, colors, radiusPx)
    }

    private fun applyRecursive(view: View, colors: ThemeColors, radiusPx: Float) {
        when {
            view.id == R.id.candidate_scroll || view.id == R.id.candidate_container -> {
                view.setBackgroundColor(colors.candidateBg)
            }
            view is Button -> {
                val isAction = view.id in ACTION_KEY_IDS
                val bg = if (isAction) colors.actionKeyBg else colors.keyBg
                view.background = createKeyDrawable(bg, colors.keyPressed, colors.stroke, radiusPx)
                view.setTextColor(colors.textPrimary)
            }
            view is TextView -> {
                if (view.id == R.id.candidate_more_indicator) {
                    view.setTextColor(colors.accent)
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i), colors, radiusPx)
            }
        }
    }
}
