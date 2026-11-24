package org.mozkey.inputmethod.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.mozkey.inputmethod.R
import org.mozkey.inputmethod.dictionary.UserDictionaryActivity
import org.mozkey.inputmethod.engine.Diagnostics
import org.mozkey.inputmethod.engine.MozcEngine
import org.mozkey.inputmethod.keyboard.Theme
import org.mozkey.inputmethod.zenz.ZenzManager
import java.io.IOException

/**
 * Launcher / settings screen. Built in code with platform widgets only, so the
 * app has no dependency besides the protobuf runtime.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private var enableRow: View? = null
    private var selectRow: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        val content = ui.createPage(this)

        content.addView(ui.header(getString(R.string.app_name), getString(R.string.settings_tagline)))

        // ---- セットアップ
        content.addView(ui.sectionTitle(getString(R.string.settings_setup)))
        val enable = ui.actionRow(getString(R.string.settings_enable), "") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        val select = ui.actionRow(getString(R.string.settings_select), "") {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        enableRow = enable
        selectRow = select
        content.addView(ui.card(enable, select))

        // ---- 試し打ち
        content.addView(ui.sectionTitle(getString(R.string.settings_try)))
        content.addView(ui.card(ui.block(tryField())))

        // ---- 入力
        content.addView(ui.sectionTitle(getString(R.string.settings_input)))
        content.addView(
            ui.card(
                ui.toggleRow(
                    getString(R.string.settings_qwerty), getString(R.string.settings_qwerty_note),
                    prefs.kanaLayout == Prefs.KanaLayout.QWERTY,
                ) { checked ->
                    prefs.kanaLayout = if (checked) Prefs.KanaLayout.QWERTY else Prefs.KanaLayout.FLICK
                },
                ui.toggleRow(
                    getString(R.string.settings_live_conversion), getString(R.string.settings_live_conversion_note),
                    prefs.liveConversion,
                ) { prefs.liveConversion = it },
                ui.toggleRow(
                    getString(R.string.settings_zenz), getString(R.string.settings_zenz_note), prefs.zenz,
                ) { prefs.zenz = it },
                ui.toggleRow(
                    getString(R.string.settings_incognito), getString(R.string.settings_incognito_note),
                    prefs.incognito,
                ) { prefs.incognito = it },
                ui.toggleRow(
                    getString(R.string.settings_phrase_history), getString(R.string.settings_phrase_history_note),
                    prefs.phraseHistory,
                ) { prefs.phraseHistory = it },
                ui.toggleRow(
                    getString(R.string.settings_show_ime_switch_key), getString(R.string.settings_show_ime_switch_key_note),
                    prefs.showImeSwitchKey,
                ) { prefs.showImeSwitchKey = it },
                ui.toggleRow(getString(R.string.settings_haptic), null, prefs.haptic) { prefs.haptic = it },
            )
        )

        // ---- キーボードの見た目
        content.addView(ui.sectionTitle(getString(R.string.settings_appearance)))
        content.addView(
            ui.card(
                ui.block(ui.label(getString(R.string.settings_theme)), themeGrid()),
                ui.toggleRow(
                    getString(R.string.settings_key_borders), getString(R.string.settings_key_borders_note),
                    prefs.keyBorders,
                ) { prefs.keyBorders = it },
                heightBlock(),
                ui.toggleRow(
                    getString(R.string.settings_flick_preview), null, prefs.flickPreview,
                ) { prefs.flickPreview = it },
            )
        )

        // ---- 辞書
        content.addView(ui.sectionTitle(getString(R.string.settings_dictionary)))
        content.addView(
            ui.card(
                ui.actionRow(getString(R.string.settings_dictionary_open), getString(R.string.settings_dictionary_note)) {
                    startActivity(Intent(this, UserDictionaryActivity::class.java))
                }
            )
        )

        // ---- 情報
        content.addView(ui.sectionTitle(getString(R.string.settings_status)))
        content.addView(ui.card(ui.block(ui.label(statusText(), 14f, ui.secondary))))

        content.addView(ui.sectionTitle(getString(R.string.settings_diagnostics)))
        val diagnostics = ui.label(diagnosticsText(), 12.5f, ui.secondary).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, ui.dp(12f), 0, ui.dp(8f))
        }
        content.addView(
            ui.card(
                ui.block(
                    ui.secondaryButton(getString(R.string.settings_diagnostics_refresh)) {
                        diagnostics.text = diagnosticsText()
                    },
                    diagnostics,
                    ui.label(getString(R.string.settings_diagnostics_note), 12.5f, ui.secondary),
                )
            )
        )
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
    }

    /** The picker is a system window, so returning from it shows up as a focus change. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSetupStatus()
    }

    private fun refreshSetupStatus() {
        val manager = getSystemService(InputMethodManager::class.java)
        val enabled = manager?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val selected = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.startsWith("$packageName/") == true
        } catch (e: SecurityException) {
            false
        }
        enableRow?.let {
            ui.setSubtitle(it, getString(if (enabled) R.string.setup_enabled else R.string.setup_not_enabled), enabled)
        }
        selectRow?.let {
            ui.setSubtitle(it, getString(if (selected) R.string.setup_selected else R.string.setup_not_selected), selected)
        }
    }

    /** Text field for trying the keyboard without leaving the settings. */
    private fun tryField(): View = EditText(this).apply {
        hint = getString(R.string.settings_try_hint)
        setTextColor(ui.text)
        setHintTextColor(ui.secondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 2
        gravity = Gravity.TOP or Gravity.START
        background = GradientDrawable().apply {
            setColor(ui.page)
            cornerRadius = ui.dp(12f).toFloat()
        }
        setPadding(ui.dp(14f), ui.dp(12f), ui.dp(14f), ui.dp(12f))
    }

    /** Grid of theme miniatures; tapping one applies it to the keyboard at once. */
    private fun themeGrid(): View {
        val choices = Theme.choices()
        val swatches = ArrayList<Pair<String, ThemeSwatchView>>()
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(10f), 0, 0)
        }
        for (rowChoices in choices.chunked(THEME_COLUMNS)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (choice in rowChoices) {
                val swatch = ThemeSwatchView(this, Theme.build(this, choice.id, keyBorders = true), ui.accent)
                swatch.selectedSwatch = choice.id == prefs.keyboardTheme
                swatches.add(choice.id to swatch)
                val label = getString(choice.labelRes)
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, ui.dp(6f), 0, ui.dp(6f))
                    isClickable = true
                    isFocusable = true
                    contentDescription = label
                    addView(swatch)
                    addView(ui.label(label, 11.5f, ui.secondary).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        setPadding(0, ui.dp(4f), 0, 0)
                    })
                    setOnClickListener {
                        prefs.keyboardTheme = choice.id
                        swatches.forEach { (id, view) -> view.selectedSwatch = id == choice.id }
                    }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            // Keep the last row aligned with the columns above it.
            repeat(THEME_COLUMNS - rowChoices.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            }
            grid.addView(row)
        }
        return grid
    }

    /** Slider for the keyboard height, in steps of 5 % of the default. */
    private fun heightBlock(): View {
        val label = ui.label("")
        fun show(percent: Int) {
            label.text = getString(R.string.settings_keyboard_height, percent)
        }
        val steps = (Prefs.MAX_KEYBOARD_HEIGHT_PERCENT - Prefs.MIN_KEYBOARD_HEIGHT_PERCENT) / HEIGHT_STEP
        val slider = SeekBar(this).apply {
            max = steps
            progress = (prefs.keyboardHeightPercent - Prefs.MIN_KEYBOARD_HEIGHT_PERCENT) / HEIGHT_STEP
            minimumHeight = ui.dp(48f)
            ui.tint(this)
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
        val reset = ui.secondaryButton(getString(R.string.settings_keyboard_height_reset)) {
            prefs.keyboardHeightPercent = Prefs.DEFAULT_KEYBOARD_HEIGHT_PERCENT
            slider.progress =
                (Prefs.DEFAULT_KEYBOARD_HEIGHT_PERCENT - Prefs.MIN_KEYBOARD_HEIGHT_PERCENT) / HEIGHT_STEP
        }
        val resetRow = LinearLayout(this).apply {
            gravity = Gravity.END
            addView(reset)
        }
        return ui.block(label, slider, resetRow)
    }

    /** Numbers and flags only; never any typed text. */
    private fun diagnosticsText(): String {
        val zenz = ZenzManager.current
        return buildString {
            append("service=").append(if (MozcEngine.current != null) "running" else "stopped").append('\n')
            append("engine_ready=").append(Diagnostics.engineReady).append('\n')
            append("config_live_conversion=").append(Diagnostics.configLiveConversion).append('\n')
            append("config_zenz=").append(Diagnostics.configZenz).append('\n')
            append("config_zenz_timeout_msec=").append(Diagnostics.configZenzTimeoutMsec).append('\n')
            append("zenz_state=").append(zenz?.state?.name ?: "-").append('\n')
            append("zenz_error=").append(zenz?.lastError ?: "-").append('\n')
            append("keys_sent=").append(Diagnostics.keysSent.get()).append('\n')
            append("live_conversion_callbacks=").append(Diagnostics.liveConversionCallbacks.get()).append('\n')
            append("zenz_callbacks=").append(Diagnostics.zenzCallbacks.get()).append('\n')
            append("zenz_pending_outputs=").append(Diagnostics.zenzPendingOutputs.get()).append('\n')
            append("zenz_applied_outputs=").append(Diagnostics.zenzAppliedOutputs.get()).append('\n')
            append("--- zenz runtime ---\n")
            append(zenz?.nativeStats()?.ifEmpty { "-" } ?: "-")
        }
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

    private companion object {
        const val HEIGHT_STEP = 5
        const val THEME_COLUMNS = 4
    }
}
