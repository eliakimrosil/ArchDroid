package com.archdroid

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File
import java.util.Properties

data class TerminalThemePreset(
    val id: String,
    val name: String,
    val bgHex: String,
    val fgHex: String,
    val cursorHex: String,
    val topBarHex: String,
    val tabActiveHex: String,
    val tabInactiveHex: String,
    val extraKeysBgHex: String,
    val ansiColors: List<String>
) {
    val bgColor: Color get() = hexToColor(bgHex)
    val fgColor: Color get() = hexToColor(fgHex)
    val cursorColor: Color get() = hexToColor(cursorHex)
    val topBarColor: Color get() = hexToColor(topBarHex)
    val tabActiveColor: Color get() = hexToColor(tabActiveHex)
    val tabInactiveColor: Color get() = hexToColor(tabInactiveHex)
    val extraKeysBgColor: Color get() = hexToColor(extraKeysBgHex)

    private fun hexToColor(hex: String): Color {
        val clean = hex.removePrefix("#")
        val parsed = clean.toLong(16)
        val argb = if (clean.length == 6) 0xFF000000L or parsed else parsed
        return Color(argb)
    }
}

object TerminalThemeManager {

    private const val PREFS_NAME = "archdroid_prefs"
    private const val LEGACY_PREFS_NAME = "kimsystem_prefs"
    private const val PREF_KEY_THEME = "selected_theme_id"
    private const val PREF_KEY_CURSOR_STYLE = "cursor_style"
    private const val PREF_KEY_FONT_SIZE = "font_size_px"
    private const val PREF_KEY_FONT_FAMILY = "font_family_id"

    private fun getPrefs(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.all.isEmpty() && legacy.all.isNotEmpty()) {
            val editor = prefs.edit()
            legacy.all.forEach { (k, v) ->
                when (v) {
                    is String -> editor.putString(k, v)
                    is Int -> editor.putInt(k, v)
                    is Boolean -> editor.putBoolean(k, v)
                    is Float -> editor.putFloat(k, v)
                    is Long -> editor.putLong(k, v)
                }
            }
            editor.apply()
        }
        return prefs
    }

    val PRESETS = listOf(
        TerminalThemePreset(
            id = "arch_cyan",
            name = "Arch Linux Cyan",
            bgHex = "#0A0E14",
            fgHex = "#ECEFF4",
            cursorHex = "#1793D1",
            topBarHex = "#070A0F",
            tabActiveHex = "#1793D1",
            tabInactiveHex = "#1D2430",
            extraKeysBgHex = "#0D1117",
            ansiColors = listOf(
                "#1D2430", "#FF6B6B", "#51CF66", "#FCC419",
                "#1793D1", "#CC5DE8", "#20C997", "#ECEFF4",
                "#495057", "#FF8787", "#69DB7C", "#FFE066",
                "#4DABF7", "#DA77F2", "#38D9A9", "#FFFFFF"
            )
        ),
        TerminalThemePreset(
            id = "github_dark",
            name = "GitHub Dark",
            bgHex = "#0D1117",
            fgHex = "#C9D1D9",
            cursorHex = "#58A6FF",
            topBarHex = "#161B22",
            tabActiveHex = "#238636",
            tabInactiveHex = "#21262D",
            extraKeysBgHex = "#161B22",
            ansiColors = listOf(
                "#484F58", "#FF7B72", "#3FB950", "#D29922",
                "#58A6FF", "#BC8CFF", "#39C5CF", "#B1BAC4",
                "#6E7681", "#FFA198", "#56D364", "#E3B341",
                "#79C0FF", "#D2A8FF", "#56D4DD", "#F0F6FC"
            )
        ),
        TerminalThemePreset(
            id = "catppuccin_mocha",
            name = "Catppuccin Mocha",
            bgHex = "#1E1E2E",
            fgHex = "#CDD6F4",
            cursorHex = "#F5E0DC",
            topBarHex = "#181825",
            tabActiveHex = "#CBA6F7",
            tabInactiveHex = "#313244",
            extraKeysBgHex = "#181825",
            ansiColors = listOf(
                "#45475A", "#F38BA8", "#A6E3A1", "#F9E2AF",
                "#89B4FA", "#F5C2E7", "#94E2D5", "#BAC2DE",
                "#585B70", "#F38BA8", "#A6E3A1", "#F9E2AF",
                "#89B4FA", "#F5C2E7", "#94E2D5", "#A6ADC8"
            )
        ),
        TerminalThemePreset(
            id = "dracula",
            name = "Dracula",
            bgHex = "#282A36",
            fgHex = "#F8F8F2",
            cursorHex = "#F8F8F2",
            topBarHex = "#21222C",
            tabActiveHex = "#BD93F9",
            tabInactiveHex = "#44475A",
            extraKeysBgHex = "#21222C",
            ansiColors = listOf(
                "#000000", "#FF5555", "#50FA7B", "#F1FA8C",
                "#BD93F9", "#FF79C6", "#8BE9FD", "#BFBFBF",
                "#4D4D4D", "#FF6E6E", "#69FF94", "#FFFFA5",
                "#D6ACFF", "#FF92DF", "#A4FFFF", "#FFFFFF"
            )
        ),
        TerminalThemePreset(
            id = "nord",
            name = "Nord Arctic",
            bgHex = "#2E3440",
            fgHex = "#D8DEE9",
            cursorHex = "#88C0D0",
            topBarHex = "#242933",
            tabActiveHex = "#88C0D0",
            tabInactiveHex = "#3B4252",
            extraKeysBgHex = "#242933",
            ansiColors = listOf(
                "#3B4252", "#BF616A", "#A3BE8C", "#EBCB8B",
                "#81A1C1", "#B48EAD", "#88C0D0", "#E5E9F0",
                "#4C566A", "#BF616A", "#A3BE8C", "#EBCB8B",
                "#81A1C1", "#B48EAD", "#8FBCBB", "#ECEFF4"
            )
        ),
        TerminalThemePreset(
            id = "one_dark",
            name = "One Dark",
            bgHex = "#282C34",
            fgHex = "#ABB2BF",
            cursorHex = "#528BFF",
            topBarHex = "#21252B",
            tabActiveHex = "#61AFEF",
            tabInactiveHex = "#3E4451",
            extraKeysBgHex = "#21252B",
            ansiColors = listOf(
                "#282C34", "#E06C75", "#98C379", "#E5C07B",
                "#61AFEF", "#C678DD", "#56B6C2", "#ABB2BF",
                "#5C6370", "#E06C75", "#98C379", "#E5C07B",
                "#61AFEF", "#C678DD", "#56B6C2", "#FFFFFF"
            )
        ),
        TerminalThemePreset(
            id = "monokai_pro",
            name = "Monokai Pro",
            bgHex = "#2D2A2E",
            fgHex = "#FCFCFA",
            cursorHex = "#FFD866",
            topBarHex = "#221F22",
            tabActiveHex = "#A9DC76",
            tabInactiveHex = "#403E41",
            extraKeysBgHex = "#221F22",
            ansiColors = listOf(
                "#403E41", "#FF6188", "#A9DC76", "#FFD866",
                "#FC9867", "#AB9DF2", "#78DCE8", "#FCFCFA",
                "#727072", "#FF6188", "#A9DC76", "#FFD866",
                "#FC9867", "#AB9DF2", "#78DCE8", "#FFFFFF"
            )
        ),
        TerminalThemePreset(
            id = "matrix_retro",
            name = "Matrix Phosphor",
            bgHex = "#060A06",
            fgHex = "#00FF66",
            cursorHex = "#00FF66",
            topBarHex = "#030503",
            tabActiveHex = "#00DD55",
            tabInactiveHex = "#112211",
            extraKeysBgHex = "#030503",
            ansiColors = listOf(
                "#000000", "#008822", "#00FF66", "#55FF88",
                "#00AA44", "#00CC55", "#33FF77", "#88FFAA",
                "#003311", "#00AA33", "#00FF66", "#77FF99",
                "#00BB44", "#00EE66", "#55FFAA", "#AAFFCC"
            )
        ),
        TerminalThemePreset(
            id = "sakura_blossom",
            name = "🌸 Sakura Blossom",
            bgHex = "#1F1622",
            fgHex = "#FCE7F3",
            cursorHex = "#FF69B4",
            topBarHex = "#170F1A",
            tabActiveHex = "#F472B6",
            tabInactiveHex = "#2F2035",
            extraKeysBgHex = "#170F1A",
            ansiColors = listOf(
                "#36283C", "#FB7185", "#86EFAC", "#FDE047",
                "#C084FC", "#F472B6", "#67E8F9", "#FCE7F3",
                "#583F63", "#FDA4AF", "#A7F3D0", "#FEF08A",
                "#DDD6FE", "#FBCFE8", "#BAE6FD", "#FFFFFF"
            )
        ),
        TerminalThemePreset(
            id = "matcha_mochi",
            name = "🍵 Matcha Mochi",
            bgHex = "#141F1A",
            fgHex = "#E8F5E9",
            cursorHex = "#69F0AE",
            topBarHex = "#0E1713",
            tabActiveHex = "#4EBA74",
            tabInactiveHex = "#213329",
            extraKeysBgHex = "#0E1713",
            ansiColors = listOf(
                "#263B30", "#FF8A80", "#81C784", "#FFE082",
                "#80DEEA", "#CE93D8", "#A7FFEB", "#E8F5E9",
                "#3D5C4B", "#FFAB91", "#A5D6A7", "#FFF59D",
                "#B2EBF2", "#E1BEE7", "#69F0AE", "#FFFFFF"
            )
        )
    )

    fun getThemeById(id: String?): TerminalThemePreset {
        return PRESETS.find { it.id == id } ?: PRESETS.first()
    }

    fun loadSavedTheme(context: Context): TerminalThemePreset {
        val prefs = getPrefs(context)
        val id = prefs.getString(PREF_KEY_THEME, PRESETS.first().id)
        return getThemeById(id)
    }

    fun saveTheme(context: Context, theme: TerminalThemePreset) {
        val prefs = getPrefs(context)
        prefs.edit().putString(PREF_KEY_THEME, theme.id).apply()
    }

    fun applyTheme(
        context: Context,
        theme: TerminalThemePreset,
        sessions: List<TerminalSession>,
        terminalView: TerminalView?
    ) {
        val props = Properties()
        props.setProperty("background", theme.bgHex)
        props.setProperty("foreground", theme.fgHex)
        props.setProperty("cursor", theme.cursorHex)
        theme.ansiColors.forEachIndexed { i, c ->
            props.setProperty("color$i", c)
        }

        try {
            TerminalColors.COLOR_SCHEME.updateWith(props)
        } catch (_: Throwable) {}

        sessions.forEach { session ->
            try {
                session.emulator?.mColors?.reset()
                session.onColorsChanged()
            } catch (_: Throwable) {}
        }

        terminalView?.onScreenUpdated()
        terminalView?.invalidate()
        saveTheme(context, theme)
    }

    fun loadCursorStyle(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(PREF_KEY_CURSOR_STYLE, TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK)
    }

    fun saveCursorStyle(context: Context, style: Int) {
        val prefs = getPrefs(context)
        prefs.edit().putInt(PREF_KEY_CURSOR_STYLE, style).apply()
    }

    private val cursorStyleField by lazy {
        try {
            val field = TerminalEmulator::class.java.getDeclaredField("mCursorStyle")
            field.isAccessible = true
            field
        } catch (_: Throwable) {
            null
        }
    }

    fun applyCursorStyle(sessions: List<TerminalSession>, style: Int, terminalView: TerminalView?) {
        val field = cursorStyleField
        sessions.forEach { session ->
            try {
                session.emulator?.let { em ->
                    em.setCursorStyle()
                    field?.setInt(em, style)
                }
            } catch (_: Throwable) {}
        }
        terminalView?.mEmulator?.let { em ->
            try {
                em.setCursorStyle()
                field?.setInt(em, style)
            } catch (_: Throwable) {}
        }
        terminalView?.onScreenUpdated()
        terminalView?.invalidate()
    }

    fun loadFontSize(context: Context, defaultSize: Int = 34): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(PREF_KEY_FONT_SIZE, defaultSize)
    }

    fun saveFontSize(context: Context, size: Int) {
        val prefs = getPrefs(context)
        prefs.edit().putInt(PREF_KEY_FONT_SIZE, size).apply()
    }

    fun loadFontFamily(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(PREF_KEY_FONT_FAMILY, "monospace") ?: "monospace"
    }

    fun saveFontFamily(context: Context, family: String) {
        val prefs = getPrefs(context)
        prefs.edit().putString(PREF_KEY_FONT_FAMILY, family).apply()
    }

    fun resolveTypeface(context: Context): Typeface {
        // 1. Check for custom font placed in /sdcard/font.ttf or /sdcard/.termux/font.ttf
        try {
            val custom1 = File("/sdcard/font.ttf")
            if (custom1.exists() && custom1.canRead()) {
                return Typeface.createFromFile(custom1)
            }
            val custom2 = File("/sdcard/.termux/font.ttf")
            if (custom2.exists() && custom2.canRead()) {
                return Typeface.createFromFile(custom2)
            }
        } catch (_: Throwable) {}

        val family = loadFontFamily(context)
        return when (family) {
            "monospace_bold" -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            "courier" -> Typeface.create("courier", Typeface.NORMAL)
            "sans_mono" -> Typeface.create("monospace", Typeface.NORMAL)
            else -> Typeface.MONOSPACE
        }
    }
}
