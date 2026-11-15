package org.mozkey.inputmethod.keyboard

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import org.mozkey.inputmethod.R
import org.mozkey.inputmethod.settings.Prefs

/** Colors of the keyboard. Chosen by the user in the settings screen. */
class Theme private constructor(
    val background: Int,
    val key: Int,
    val functionalKey: Int,
    val pressedKey: Int,
    val text: Int,
    val hintText: Int,
    val accent: Int,
    val onAccent: Int,
    val candidateFocus: Int,
    val divider: Int,
    /** False draws letter keys without a key shape, directly on the background. */
    val keyBorders: Boolean,
) {
    /** A theme the user can pick. */
    class Choice(val id: String, val labelRes: Int)

    companion object {
        // Mozkey's Zenz indicator colors (config.proto: zenz_color / zenz_pending_color).
        const val ZENZ_APPLIED = 0xFF338D85.toInt()
        const val ZENZ_PENDING = 0xFF847438.toInt()

        const val ID_SYSTEM = "system"
        const val ID_DYNAMIC = "dynamic"
        const val ID_LIGHT = "light"
        const val ID_DARK = "dark"
        const val ID_BLACK = "black"
        const val ID_BLUE = "blue"
        const val ID_GREEN = "green"
        const val ID_ROSE = "rose"
        const val ID_PURPLE = "purple"
        const val ID_SAND = "sand"
        const val ID_NAVY = "navy"

        /** Wallpaper-based colors exist since Android 12. */
        val dynamicColorsAvailable: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        fun choices(): List<Choice> = buildList {
            add(Choice(ID_SYSTEM, R.string.theme_system))
            if (dynamicColorsAvailable) add(Choice(ID_DYNAMIC, R.string.theme_dynamic))
            add(Choice(ID_LIGHT, R.string.theme_light))
            add(Choice(ID_DARK, R.string.theme_dark))
            add(Choice(ID_BLACK, R.string.theme_black))
            add(Choice(ID_BLUE, R.string.theme_blue))
            add(Choice(ID_GREEN, R.string.theme_green))
            add(Choice(ID_ROSE, R.string.theme_rose))
            add(Choice(ID_PURPLE, R.string.theme_purple))
            add(Choice(ID_SAND, R.string.theme_sand))
            add(Choice(ID_NAVY, R.string.theme_navy))
        }

        /** The theme currently selected in the settings. */
        fun from(context: Context): Theme {
            val prefs = Prefs(context)
            return build(context, prefs.keyboardTheme, prefs.keyBorders)
        }

        fun build(context: Context, id: String, keyBorders: Boolean): Theme {
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return when (id) {
                ID_LIGHT -> light(keyBorders)
                ID_DARK -> dark(keyBorders)
                ID_DYNAMIC ->
                    if (dynamicColorsAvailable) dynamic(context, night, keyBorders)
                    else if (night) dark(keyBorders) else light(keyBorders)
                ID_BLACK -> palette(
                    0xFF000000, 0xFF1C1C1E, 0xFF121214, 0xFF3A3A3C, 0xFFF2F2F2, 0xFF8E8E93,
                    0xFF4DB6AC, 0xFF06201D, 0xFF1F3A37, 0xFF2C2C2E, keyBorders,
                )
                ID_BLUE -> palette(
                    0xFFDDE7F5, 0xFFFFFFFF, 0xFFB9CCE8, 0xFF9FB7DB, 0xFF10203A, 0xFF566A8C,
                    0xFF2A62C9, 0xFFFFFFFF, 0xFFC5D8F7, 0xFFC3D2E8, keyBorders,
                )
                ID_GREEN -> palette(
                    0xFFDDEEDF, 0xFFFFFFFF, 0xFFB8D8BC, 0xFF9CC7A2, 0xFF10301A, 0xFF52745A,
                    0xFF24753D, 0xFFFFFFFF, 0xFFC4E6CA, 0xFFC2DCC6, keyBorders,
                )
                ID_ROSE -> palette(
                    0xFFF6E1E6, 0xFFFFFFFF, 0xFFEBC3CD, 0xFFDFA8B6, 0xFF3A1420, 0xFF875562,
                    0xFFBE3559, 0xFFFFFFFF, 0xFFF7CAD6, 0xFFEBCDD5, keyBorders,
                )
                ID_PURPLE -> palette(
                    0xFFE8E1F5, 0xFFFFFFFF, 0xFFCFC2EA, 0xFFB9A8DF, 0xFF22143A, 0xFF665889,
                    0xFF6C43C0, 0xFFFFFFFF, 0xFFDCCFF7, 0xFFD6CCEB, keyBorders,
                )
                ID_SAND -> palette(
                    0xFFF1E8D8, 0xFFFFFDF8, 0xFFDDCFB4, 0xFFCDBB98, 0xFF33281A, 0xFF7C6A4C,
                    0xFF946012, 0xFFFFFFFF, 0xFFF0DDB8, 0xFFE2D6BF, keyBorders,
                )
                ID_NAVY -> palette(
                    0xFF14213D, 0xFF24365E, 0xFF1B2A4C, 0xFF3A4F80, 0xFFEAF0FF, 0xFF93A3C7,
                    0xFF7FB0FF, 0xFF0B1730, 0xFF2C4578, 0xFF24365E, keyBorders,
                )
                else -> if (night) dark(keyBorders) else light(keyBorders)
            }
        }

        private fun palette(
            background: Long, key: Long, functionalKey: Long, pressedKey: Long, text: Long, hintText: Long,
            accent: Long, onAccent: Long, candidateFocus: Long, divider: Long, keyBorders: Boolean,
        ) = Theme(
            background.toInt(), key.toInt(), functionalKey.toInt(), pressedKey.toInt(), text.toInt(),
            hintText.toInt(), accent.toInt(), onAccent.toInt(), candidateFocus.toInt(), divider.toInt(),
            keyBorders,
        )

        private fun light(keyBorders: Boolean) = palette(
            0xFFE9ECEF, 0xFFFFFFFF, 0xFFCBD2D9, 0xFFB4BDC6, 0xFF15181B, 0xFF636C75,
            0xFF2B7A73, 0xFFFFFFFF, 0xFFCFE8E5, 0xFFD3D8DD, keyBorders,
        )

        private fun dark(keyBorders: Boolean) = palette(
            0xFF1E1F22, 0xFF3A3C41, 0xFF2A2C30, 0xFF55585F, 0xFFECEDEF, 0xFF9A9DA3,
            0xFF4DB6AC, 0xFF10201E, 0xFF2F4F4B, 0xFF3A3C41, keyBorders,
        )

        /** Material You: colors the system derives from the wallpaper (Android 12+). */
        private fun dynamic(context: Context, night: Boolean, keyBorders: Boolean): Theme {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return if (night) dark(keyBorders) else light(keyBorders)
            }
            fun color(id: Int): Int = context.getColor(id)
            return if (night) {
                Theme(
                    background = color(android.R.color.system_neutral1_900),
                    key = color(android.R.color.system_neutral2_700),
                    functionalKey = color(android.R.color.system_neutral2_800),
                    pressedKey = color(android.R.color.system_neutral2_600),
                    text = color(android.R.color.system_neutral1_50),
                    hintText = color(android.R.color.system_neutral2_300),
                    accent = color(android.R.color.system_accent1_200),
                    onAccent = color(android.R.color.system_accent1_800),
                    candidateFocus = color(android.R.color.system_accent1_700),
                    divider = color(android.R.color.system_neutral2_700),
                    keyBorders = keyBorders,
                )
            } else {
                Theme(
                    background = color(android.R.color.system_neutral1_100),
                    key = color(android.R.color.system_neutral1_10),
                    functionalKey = color(android.R.color.system_accent2_200),
                    pressedKey = color(android.R.color.system_accent2_300),
                    text = color(android.R.color.system_neutral1_900),
                    hintText = color(android.R.color.system_neutral2_600),
                    accent = color(android.R.color.system_accent1_600),
                    onAccent = color(android.R.color.system_accent1_0),
                    candidateFocus = color(android.R.color.system_accent1_100),
                    divider = color(android.R.color.system_neutral2_200),
                    keyBorders = keyBorders,
                )
            }
        }
    }
}
