package org.mozkey.inputmethod.settings

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Look of the app's own screens: a tinted page with rounded cards, built from
 * platform widgets only. Follows the system light/dark setting.
 */
class Ui(private val context: Context) {

    val night: Boolean = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    val page = if (night) 0xFF111315.toInt() else 0xFFF2F4F6.toInt()
    val card = if (night) 0xFF1D2023.toInt() else 0xFFFFFFFF.toInt()
    val text = if (night) 0xFFECEEF0.toInt() else 0xFF171A1D.toInt()
    val secondary = if (night) 0xFFA3A9B0.toInt() else 0xFF5C656E.toInt()
    val accent = if (night) 0xFF4DB6AC.toInt() else 0xFF2B7A73.toInt()
    val onAccent = if (night) 0xFF10201E.toInt() else 0xFFFFFFFF.toInt()
    val divider = if (night) 0xFF2B2F33.toInt() else 0xFFE4E8EC.toInt()
    private val ripple = if (night) 0x33FFFFFF else 0x1F000000
    private val trackOff = if (night) 0xFF4A4F55.toInt() else 0xFFC5CBD1.toInt()
    private val thumbOff = if (night) 0xFFB8BEC4.toInt() else 0xFFFFFFFF.toInt()

    fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    /**
     * Prepares [activity] (no title bar, page color, system-bar icon contrast) and
     * returns the vertical container to fill. Call before adding any content.
     */
    fun createPage(activity: Activity): LinearLayout {
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val window = activity.window
        window.decorView.setBackgroundColor(page)
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Before Android 15 the bars are opaque; match them to the page.
            @Suppress("DEPRECATION")
            window.statusBarColor = page
            @Suppress("DEPRECATION")
            window.navigationBarColor = page
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(24f))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(page)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Edge to edge is enforced since Android 15: keep content clear of the
        // system bars, the display cutout and the keyboard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        activity.setContentView(scroll)
        activity.actionBar?.hide()
        return content
    }

    fun header(title: String, subtitle: String?): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4f), dp(20f), dp(4f), dp(8f))
        addView(TextView(context).apply {
            text = title
            setTextColor(this@Ui.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (subtitle != null) {
            addView(TextView(context).apply {
                text = subtitle
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(2f), 0, 0)
            })
        }
    }

    fun sectionTitle(title: String): View = TextView(context).apply {
        text = title
        setTextColor(accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
        setPadding(dp(8f), dp(22f), dp(8f), dp(8f))
    }

    /** Rounded card holding [rows], separated by hairlines. */
    fun card(vararg rows: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(card)
            cornerRadius = dp(18f).toFloat()
        }
        clipToOutline = true
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                addView(
                    View(context).apply { setBackgroundColor(divider) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f).coerceAtLeast(1)).apply {
                        marginStart = dp(16f)
                        marginEnd = dp(16f)
                    },
                )
            }
            addView(row)
        }
    }

    private fun rippleBackground() =
        RippleDrawable(ColorStateList.valueOf(ripple), null, ColorDrawable(0xFFFFFFFF.toInt()))

    private fun titleBlock(title: String, subtitle: String?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            setTextColor(this@Ui.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        if (subtitle != null) {
            addView(TextView(context).apply {
                text = subtitle
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(2f), 0, 0)
                tag = TAG_SUBTITLE
            })
        }
    }

    private fun rowContainer(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60f)
        setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
    }

    /** Row that opens something. [subtitle] can be changed later with [setSubtitle]. */
    fun actionRow(title: String, subtitle: String?, onClick: () -> Unit): LinearLayout = rowContainer().apply {
        background = rippleBackground()
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(titleBlock(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = "›"
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(12f), 0, dp(2f), 0)
        })
    }

    fun setSubtitle(row: View, subtitle: String, highlighted: Boolean) {
        val view = row.findViewWithTag<TextView>(TAG_SUBTITLE) ?: return
        view.text = subtitle
        view.setTextColor(if (highlighted) accent else secondary)
    }

    /** Row with a switch; tapping anywhere on the row toggles it. */
    fun toggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val switch = Switch(context).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(accent, thumbOff))
            trackTintList = ColorStateList(states, intArrayOf((accent and 0x00FFFFFF) or 0x80000000.toInt(), trackOff))
            // The row handles the tap, so the switch itself is not a second target.
            isClickable = false
            isFocusable = false
        }
        return rowContainer().apply {
            background = rippleBackground()
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener {
                switch.isChecked = !switch.isChecked
                onChange(switch.isChecked)
            }
            addView(titleBlock(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12f)
            })
        }
    }

    /** Free-form content placed inside a card with the standard row padding. */
    fun block(vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        children.forEach { addView(it) }
    }

    fun label(textValue: String, sizeSp: Float = 16f, color: Int = text): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    fun tint(slider: SeekBar) {
        slider.progressTintList = ColorStateList.valueOf(accent)
        slider.thumbTintList = ColorStateList.valueOf(accent)
        slider.progressBackgroundTintList = ColorStateList.valueOf(trackOff)
    }

    /** Filled, pill-shaped button. */
    fun primaryButton(textValue: String, onClick: () -> Unit): TextView = pill(textValue, accent, onAccent, onClick)

    /** Quiet, pill-shaped button. */
    fun secondaryButton(textValue: String, onClick: () -> Unit): TextView = pill(textValue, divider, text, onClick)

    private fun pill(textValue: String, fill: Int, foreground: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = textValue
            setTextColor(foreground)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = dp(44f)
            setPadding(dp(20f), 0, dp(20f), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(ripple),
                GradientDrawable().apply {
                    setColor(fill)
                    cornerRadius = dp(22f).toFloat()
                },
                null,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private companion object {
        const val TAG_SUBTITLE = "subtitle"
    }
}
