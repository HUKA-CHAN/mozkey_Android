package org.mozkey.inputmethod.keyboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Horizontal candidate strip with a small conversion-status dot on the left
 * (the Android counterpart of Mozkey's conversion status indicator).
 */
class CandidateBarView(context: Context) : LinearLayout(context) {

    class Candidate(val id: Int, val value: String, val description: String?)

    enum class Status { NONE, ZENZ_PENDING, ZENZ_APPLIED }

    var onCandidateSelected: ((Candidate) -> Unit)? = null

    private var theme = Theme.from(context)
    private val statusDot = View(context)
    private val scroll = HorizontalScrollView(context)
    private val strip = LinearLayout(context)
    private val chips = ArrayList<TextView>()
    private var candidates: List<Candidate> = emptyList()
    private var status = Status.NONE

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        val dotSize = dp(8f)
        addView(statusDot, LayoutParams(dotSize, dotSize).apply {
            marginStart = dp(8f)
            marginEnd = dp(4f)
        })

        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        strip.orientation = HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        scroll.addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        applyTheme()
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    fun refreshTheme() {
        theme = Theme.from(context)
        applyTheme()
        setCandidates(candidates, -1)
    }

    private fun applyTheme() {
        setBackgroundColor(theme.background)
        updateStatusDot()
    }

    fun setStatus(newStatus: Status) {
        if (status != newStatus) {
            status = newStatus
            updateStatusDot()
        }
    }

    private fun updateStatusDot() {
        val color = when (status) {
            Status.NONE -> 0
            Status.ZENZ_PENDING -> Theme.ZENZ_PENDING
            Status.ZENZ_APPLIED -> Theme.ZENZ_APPLIED
        }
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        statusDot.contentDescription = when (status) {
            Status.NONE -> null
            Status.ZENZ_PENDING -> "Zenz 処理中"
            Status.ZENZ_APPLIED -> "Zenz 適用済み"
        }
    }

    /** Replaces the list. [focusedIndex] is highlighted when it is a valid index. */
    fun setCandidates(list: List<Candidate>, focusedIndex: Int) {
        val limited = if (list.size > MAX_CANDIDATES) list.subList(0, MAX_CANDIDATES) else list
        candidates = limited

        while (chips.size < limited.size) {
            val chip = createChip()
            chips.add(chip)
            strip.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        for (i in chips.indices) {
            val chip = chips[i]
            if (i < limited.size) {
                val candidate = limited[i]
                chip.text = candidate.value
                chip.contentDescription =
                    if (candidate.description.isNullOrEmpty()) candidate.value
                    else "${candidate.value} ${candidate.description}"
                chip.tag = candidate
                chip.setTextColor(theme.text)
                chip.setBackgroundColor(if (i == focusedIndex) theme.candidateFocus else 0)
                chip.visibility = View.VISIBLE
            } else {
                chip.tag = null
                chip.visibility = View.GONE
            }
        }
        if (focusedIndex in limited.indices) {
            val focused = chips[focusedIndex]
            scroll.post { scroll.smoothScrollTo((focused.left - dp(24f)).coerceAtLeast(0), 0) }
        } else {
            scroll.scrollTo(0, 0)
        }
    }

    private fun createChip(): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        maxLines = 1
        minWidth = dp(44f)
        setPadding(dp(14f), 0, dp(14f), 0)
        isClickable = true
        setOnClickListener { view ->
            (view.tag as? Candidate)?.let { onCandidateSelected?.invoke(it) }
        }
    }

    companion object {
        /** Enough for several screens of scrolling without creating hundreds of views. */
        private const val MAX_CANDIDATES = 60
    }
}
