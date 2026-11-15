package org.mozkey.inputmethod.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import org.mozkey.inputmethod.keyboard.Theme

/** Miniature of a keyboard theme: background, two keys, a function key and the accent key. */
class ThemeSwatchView(context: Context, private val theme: Theme, private val ringColor: Int) : View(context) {

    var selectedSwatch = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(64f).toInt(), dp(48f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp(3f)
        val radius = dp(10f)
        rect.set(inset, inset, width - inset, height - inset)
        fill.color = theme.background
        canvas.drawRoundRect(rect, radius, radius, fill)

        // Two rows of three keys; the last key of the second row is the accent key.
        val pad = dp(7f)
        val gap = dp(3f)
        val keyWidth = (rect.width() - pad * 2 - gap * 2) / 3f
        val keyHeight = (rect.height() - pad * 2 - gap) / 2f
        val keyRadius = dp(3f)
        for (row in 0..1) {
            for (column in 0..2) {
                val left = rect.left + pad + column * (keyWidth + gap)
                val top = rect.top + pad + row * (keyHeight + gap)
                fill.color = when {
                    row == 1 && column == 2 -> theme.accent
                    row == 1 && column == 0 -> theme.functionalKey
                    else -> theme.key
                }
                canvas.drawRoundRect(left, top, left + keyWidth, top + keyHeight, keyRadius, keyRadius, fill)
            }
        }

        stroke.strokeWidth = dp(if (selectedSwatch) 2.5f else 1f)
        stroke.color = if (selectedSwatch) ringColor else theme.divider
        val half = stroke.strokeWidth / 2f
        rect.inset(-inset + half, -inset + half)
        canvas.drawRoundRect(rect, radius + inset, radius + inset, stroke)
    }
}
