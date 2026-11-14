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

    var incognito: Boolean
        get() = raw.getBoolean(KEY_INCOGNITO, false)
        set(value) = raw.edit().putBoolean(KEY_INCOGNITO, value).apply()

    companion object {
        private const val FILE = "mozkey"
        const val KEY_KANA_LAYOUT = "kana_layout"
        const val KEY_LIVE_CONVERSION = "live_conversion"
        const val KEY_ZENZ = "zenz"
        const val KEY_ZENZ_TIMEOUT = "zenz_timeout_msec"
        const val KEY_HAPTIC = "haptic"
        const val KEY_INCOGNITO = "incognito"
        const val DEFAULT_ZENZ_TIMEOUT_MSEC = 400
    }
}
