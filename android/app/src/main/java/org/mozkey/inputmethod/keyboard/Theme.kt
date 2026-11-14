package org.mozkey.inputmethod.keyboard

import android.content.Context
import android.content.res.Configuration

/** Colors for the keyboard, following the system light/dark setting. */
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
) {
    companion object {
        // Mozkey's Zenz indicator colors (config.proto: zenz_color / zenz_pending_color).
        const val ZENZ_APPLIED = 0xFF338D85.toInt()
        const val ZENZ_PENDING = 0xFF847438.toInt()

        fun from(context: Context): Theme {
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (night) {
                Theme(
                    background = 0xFF1E1F22.toInt(),
                    key = 0xFF3A3C41.toInt(),
                    functionalKey = 0xFF2A2C30.toInt(),
                    pressedKey = 0xFF55585F.toInt(),
                    text = 0xFFECEDEF.toInt(),
                    hintText = 0xFF9A9DA3.toInt(),
                    accent = 0xFF4DB6AC.toInt(),
                    onAccent = 0xFF10201E.toInt(),
                    candidateFocus = 0xFF2F4F4B.toInt(),
                    divider = 0xFF3A3C41.toInt(),
                )
            } else {
                Theme(
                    background = 0xFFE9ECEF.toInt(),
                    key = 0xFFFFFFFF.toInt(),
                    functionalKey = 0xFFCBD2D9.toInt(),
                    pressedKey = 0xFFB4BDC6.toInt(),
                    text = 0xFF15181B.toInt(),
                    hintText = 0xFF7A838C.toInt(),
                    accent = 0xFF338D85.toInt(),
                    onAccent = 0xFFFFFFFF.toInt(),
                    candidateFocus = 0xFFCFE8E5.toInt(),
                    divider = 0xFFD3D8DD.toInt(),
                )
            }
        }
    }
}
