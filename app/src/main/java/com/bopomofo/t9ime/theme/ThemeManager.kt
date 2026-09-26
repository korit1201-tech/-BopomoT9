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
    OCEAN("ocean", "蔚藍海洋"),
    FOREST("forest", "台灣森林綠")
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
        R.id.btn_close_symbol_panel,
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
                bg = Color.parseColor("#ECEEF1"),
                keyBg = Color.parseColor("#FFFFFF"),
                keyPressed = Color.parseColor("#DCE0E5"),
                actionKeyBg = Color.parseColor("#DDE1E6"),
                stroke = Color.parseColor("#CFD4D9"),
                textPrimary = Color.parseColor("#1F2328"),
                textSecondary = Color.parseColor("#656D76"),
                candidateBg = Color.parseColor("#FFFFFF"),
                candidateText = Color.parseColor("#0969DA"),
                accent = Color.parseColor("#0969DA")
            )
            AppTheme.DARK -> ThemeColors(
                bg = Color.parseColor("#151718"),
                keyBg = Color.parseColor("#26292B"),
                keyPressed = Color.parseColor("#3B3F43"),
                actionKeyBg = Color.parseColor("#1E2022"),
                stroke = Color.parseColor("#363A3E"),
                textPrimary = Color.parseColor("#F0F2F5"),
                textSecondary = Color.parseColor("#8B949E"),
                candidateBg = Color.parseColor("#1B1D1F"),
                candidateText = Color.parseColor("#58A6FF"),
                accent = Color.parseColor("#58A6FF")
            )
            AppTheme.OCEAN -> ThemeColors(
                bg = Color.parseColor("#0A1420"),
                keyBg = Color.parseColor("#16283B"),
                keyPressed = Color.parseColor("#264566"),
                actionKeyBg = Color.parseColor("#0F1E2E"),
                stroke = Color.parseColor("#243D59"),
                textPrimary = Color.parseColor("#EBF3FA"),
                textSecondary = Color.parseColor("#8BAAC9"),
                candidateBg = Color.parseColor("#0E1B2B"),
                candidateText = Color.parseColor("#38BDF8"),
                accent = Color.parseColor("#38BDF8")
            )
            AppTheme.FOREST -> ThemeColors(
                bg = Color.parseColor("#0F1B14"),
                keyBg = Color.parseColor("#1E3326"),
                keyPressed = Color.parseColor("#2E4F3B"),
                actionKeyBg = Color.parseColor("#15241B"),
                stroke = Color.parseColor("#2D4A38"),
                textPrimary = Color.parseColor("#E8F5E9"),
                textSecondary = Color.parseColor("#8FB89B"),
                candidateBg = Color.parseColor("#14241B"),
                candidateText = Color.parseColor("#4ADE80"),
                accent = Color.parseColor("#4ADE80")
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
