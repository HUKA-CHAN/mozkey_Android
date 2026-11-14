package org.mozkey.inputmethod.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.mozkey.inputmethod.R
import org.mozkey.inputmethod.engine.MozcEngine
import org.mozkey.inputmethod.zenz.ZenzManager
import java.io.IOException

/**
 * Launcher / settings screen. Built in code with platform widgets only, so the
 * app has no dependency besides the protobuf runtime.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Activities are edge to edge on Android 15+ and can no longer opt out
        // when targeting API 36+, so keep the content clear of the system bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        content.addView(heading(getString(R.string.settings_setup)))
        content.addView(button(getString(R.string.settings_enable)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        content.addView(button(getString(R.string.settings_select)) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        })

        content.addView(heading(getString(R.string.settings_input)))
        content.addView(
            toggle(getString(R.string.settings_qwerty), prefs.kanaLayout == Prefs.KanaLayout.QWERTY) { checked ->
                prefs.kanaLayout = if (checked) Prefs.KanaLayout.QWERTY else Prefs.KanaLayout.FLICK
            }
        )
        content.addView(toggle(getString(R.string.settings_live_conversion), prefs.liveConversion) {
            prefs.liveConversion = it
        })
        content.addView(toggle(getString(R.string.settings_zenz), prefs.zenz) { prefs.zenz = it })
        content.addView(note(getString(R.string.settings_zenz_note)))
        content.addView(toggle(getString(R.string.settings_incognito), prefs.incognito) { prefs.incognito = it })
        content.addView(toggle(getString(R.string.settings_haptic), prefs.haptic) { prefs.haptic = it })

        content.addView(heading(getString(R.string.settings_appearance)))
        addKeyboardHeightSlider(content)
        content.addView(toggle(getString(R.string.settings_flick_preview), prefs.flickPreview) {
            prefs.flickPreview = it
        })

        content.addView(heading(getString(R.string.settings_status)))
        content.addView(note(statusText()))

        setContentView(scroll)
    }

    /** Slider for the keyboard height, in steps of 5 % of the default. */
    private fun addKeyboardHeightSlider(content: LinearLayout) {
        val label = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        fun show(percent: Int) {
            label.text = getString(R.string.settings_keyboard_height, percent)
        }
        val steps = (Prefs.MAX_KEYBOARD_HEIGHT_PERCENT - Prefs.MIN_KEYBOARD_HEIGHT_PERCENT) / HEIGHT_STEP
        val slider = SeekBar(this).apply {
            max = steps
            progress = (prefs.keyboardHeightPercent - Prefs.MIN_KEYBOARD_HEIGHT_PERCENT) / HEIGHT_STEP
            minimumHeight = dp(48f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val percent = Prefs.MIN_KEYBOARD_HEIGHT_PERCENT + progress * HEIGHT_STEP
                    show(percent)
                    // Applied while dragging, so an open keyboard resizes live.
                    if (fromUser) prefs.keyboardHeightPercent = percent
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        show(prefs.keyboardHeightPercent)
        content.addView(label)
        content.addView(slider)
        content.addView(button(getString(R.string.settings_keyboard_height_reset)) {
            prefs.keyboardHeightPercent = Prefs.DEFAULT_KEYBOARD_HEIGHT_PERCENT
            slider.progress =
                (Prefs.DEFAULT_KEYBOARD_HEIGHT_PERCENT - Prefs.MIN_KEYBOARD_HEIGHT_PERCENT) / HEIGHT_STEP
        })
        content.addView(note(getString(R.string.settings_keyboard_height_note)))
    }

    private fun statusText(): String {
        val dictionary = hasAsset(MozcEngine.DATA_ASSET)
        val model = hasAsset(ZenzManager.MODEL_ASSET)
        return buildString {
            append(getString(if (dictionary) R.string.status_dictionary_ok else R.string.status_dictionary_missing))
            append('\n')
            append(getString(if (model) R.string.status_model_ok else R.string.status_model_missing))
        }
    }

    private fun hasAsset(path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (e: IOException) {
        false
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun heading(text: String): View = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(24f), 0, dp(8f))
    }

    private fun note(text: String): View = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        alpha = 0.75f
        setPadding(0, dp(2f), 0, dp(8f))
    }

    private fun button(text: String, onClick: () -> Unit): View = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private companion object {
        const val HEIGHT_STEP = 5
    }

    private fun toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit): View =
        Switch(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isChecked = checked
            minHeight = dp(52f)
            setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> onChange(value) }
        }
}
