package org.mozkey.inputmethod.settings

import android.content.Context
import android.content.SharedPreferences

/** User settings shared by the IME service and the settings screen (same process). */
class Prefs(context: Context) {

    enum class KanaLayout { FLICK, QWERTY }

    val raw: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var kanaLayout: KanaLayout
        get() = if (raw.getString(KEY_KANA_LAYOUT, "flick") == "qwerty") KanaLayout.QWERTY else KanaLayout.FLICK
        set(value) = raw.edit().putString(KEY_KANA_LAYOUT, if (value == KanaLayout.QWERTY) "qwerty" else "flick").apply()

    var liveConversion: Boolean
        get() = raw.getBoolean(KEY_LIVE_CONVERSION, true)
        set(value) = raw.edit().putBoolean(KEY_LIVE_CONVERSION, value).apply()

    var zenz: Boolean
        get() = raw.getBoolean(KEY_ZENZ, true)
        set(value) = raw.edit().putBoolean(KEY_ZENZ, value).apply()

    /** Per-request budget for Zenz. Phones are slower than the desktop default of 180 ms. */
    var zenzTimeoutMsec: Int
        get() = raw.getInt(KEY_ZENZ_TIMEOUT, DEFAULT_ZENZ_TIMEOUT_MSEC).coerceIn(100, 3000)
        set(value) = raw.edit().putInt(KEY_ZENZ_TIMEOUT, value.coerceIn(100, 3000)).apply()

    var haptic: Boolean
        get() = raw.getBoolean(KEY_HAPTIC, true)
        set(value) = raw.edit().putBoolean(KEY_HAPTIC, value).apply()

    /** Keyboard height in percent of the default key height. */
    var keyboardHeightPercent: Int
        get() = raw.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT_PERCENT)
            .coerceIn(MIN_KEYBOARD_HEIGHT_PERCENT, MAX_KEYBOARD_HEIGHT_PERCENT)
        set(value) = raw.edit().putInt(
            KEY_KEYBOARD_HEIGHT,
            value.coerceIn(MIN_KEYBOARD_HEIGHT_PERCENT, MAX_KEYBOARD_HEIGHT_PERCENT),
        ).apply()

    /** Shows the flick targets around a pressed flick key. */
    var flickPreview: Boolean
        get() = raw.getBoolean(KEY_FLICK_PREVIEW, true)
        set(value) = raw.edit().putBoolean(KEY_FLICK_PREVIEW, value).apply()

    /** One of the Theme.ID_* values. */
    var keyboardTheme: String
        get() = raw.getString(KEY_KEYBOARD_THEME, DEFAULT_KEYBOARD_THEME) ?: DEFAULT_KEYBOARD_THEME
        set(value) = raw.edit().putString(KEY_KEYBOARD_THEME, value).apply()

    /** Draws a key shape behind every key; off gives a flat, borderless keyboard. */
    var keyBorders: Boolean
        get() = raw.getBoolean(KEY_KEY_BORDERS, true)
        set(value) = raw.edit().putBoolean(KEY_KEY_BORDERS, value).apply()

    var incognito: Boolean
        get() = raw.getBoolean(KEY_INCOGNITO, false)
        set(value) = raw.edit().putBoolean(KEY_INCOGNITO, value).apply()

    /** Offers phrases committed before as candidates, matched by reading prefix. */
    var phraseHistory: Boolean
        get() = raw.getBoolean(KEY_PHRASE_HISTORY, true)
        set(value) = raw.edit().putBoolean(KEY_PHRASE_HISTORY, value).apply()

    companion object {
        private const val FILE = "mozkey"
        const val KEY_KANA_LAYOUT = "kana_layout"
        const val KEY_LIVE_CONVERSION = "live_conversion"
        const val KEY_ZENZ = "zenz"
        const val KEY_ZENZ_TIMEOUT = "zenz_timeout_msec"
        const val KEY_HAPTIC = "haptic"
        const val KEY_INCOGNITO = "incognito"
        const val KEY_PHRASE_HISTORY = "phrase_history"
        const val KEY_KEYBOARD_HEIGHT = "keyboard_height_percent"
        const val KEY_FLICK_PREVIEW = "flick_preview"
        const val KEY_KEYBOARD_THEME = "keyboard_theme"
        const val KEY_KEY_BORDERS = "key_borders"
        const val DEFAULT_KEYBOARD_THEME = "system"
        const val DEFAULT_ZENZ_TIMEOUT_MSEC = 400
        const val DEFAULT_KEYBOARD_HEIGHT_PERCENT = 100
        const val MIN_KEYBOARD_HEIGHT_PERCENT = 70
        const val MAX_KEYBOARD_HEIGHT_PERCENT = 150
    }
}
