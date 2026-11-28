package org.mozkey.inputmethod.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Software keyboard: draws a [KeyboardLayout] and turns touches into [KeyAction]s.
 *
 * Supports tap, four-direction flick, key repeat and long press. A second finger
 * landing while the first is still down commits the first key, so fast two-thumb
 * typing on the QWERTY layouts does not drop characters.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKey(action: KeyAction)

        /** Returns true when the long press was handled and the key must not fire on release. */
        fun onLongPress(action: KeyAction): Boolean
    }

    var listener: Listener? = null
    var hapticEnabled = true

    /** Shows the flick targets around a pressed flick key. */
    var flickPreviewEnabled = true

    /** Space above / below this view that the flick guide may draw into, in pixels. */
    var overflowTop = 0f
    var overflowBottom = 0f

    /** User-selected multiplier for the key height (1.0 = default). */
    var heightScale = 1f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    private var theme = Theme.from(context)
    private var keyboardLayout: KeyboardLayout = KeyboardLayouts.forMode(KeyboardMode.KANA_FLICK)
    private val placed = ArrayList<PlacedKey>()

    private var shifted = false
    private var enterLabel: String = KeyboardLayouts.LABEL_ENTER
    private var spaceLabel: String? = null

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val gap = dp(3f)
    private val padding = dp(4f)
    private val corner = dp(7f)
    private val flickThreshold = dp(18f)
    private val previewRect = RectF()

    private val timer = Handler(Looper.getMainLooper())
    private var active: ActiveTouch? = null

    private class PlacedKey(val key: Key, val rect: RectF)

    private class ActiveTouch(val pointerId: Int, val placed: PlacedKey, val downX: Float, val downY: Float) {
        /** -1 = center, otherwise Key.LEFT..Key.DOWN. */
        var direction = -1
        var consumed = false
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val touch = active ?: return
            if (!touch.placed.key.repeatable) return
            fire(touch.placed.key.action)
            timer.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val longPressRunnable = Runnable {
        val touch = active ?: return@Runnable
        if (touch.direction != -1 || touch.consumed) return@Runnable
        if (listener?.onLongPress(touch.placed.key.action) == true) {
            touch.consumed = true
            invalidate()
        }
    }

    fun setLayout(newLayout: KeyboardLayout) {
        if (keyboardLayout === newLayout) return
        cancelTouch()
        keyboardLayout = newLayout
        requestLayout()
        invalidate()
    }

    fun setShifted(value: Boolean) {
        if (shifted != value) {
            shifted = value
            invalidate()
        }
    }

    fun setEnterLabel(label: String) {
        if (enterLabel != label) {
            enterLabel = label
            invalidate()
        }
    }

    /** Overrides the space key label (for example "変換" while composing); null restores it. */
    fun setSpaceLabel(label: String?) {
        if (spaceLabel != label) {
            spaceLabel = label
            invalidate()
        }
    }

    fun refreshTheme() {
        theme = Theme.from(context)
        invalidate()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun rowHeightPx(): Float {
        // Shrink the keys on short (landscape) windows so the app stays visible.
        val screenHeightDp = resources.configuration.screenHeightDp
        val scale = if (screenHeightDp in 1 until 480) 0.78f else 1f
        return dp(keyboardLayout.rowHeightDp * scale * heightScale)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (rowHeightPx() * keyboardLayout.rows.size + padding * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placeKeys(w)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        placeKeys(width)
    }

    private fun placeKeys(viewWidth: Int) {
        placed.clear()
        if (viewWidth <= 0) return
        val rowHeight = rowHeightPx()
        val available = viewWidth - padding * 2
        // All rows share the unit width of the widest row so columns line up.
        val maxWeight = keyboardLayout.rows.maxOf { row -> row.sumOf { it.weight.toDouble() } }.toFloat()
        val unit = available / maxWeight
        var top = padding
        for (row in keyboardLayout.rows) {
            val rowWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            var left = padding + (available - rowWeight * unit) / 2f
            for (key in row) {
                val keyWidth = key.weight * unit
                placed.add(
                    PlacedKey(
                        key,
                        RectF(left + gap / 2, top + gap / 2, left + keyWidth - gap / 2, top + rowHeight - gap / 2),
                    )
                )
                left += keyWidth
            }
            top += rowHeight
        }
    }

    private fun displayLabel(key: Key): String {
        val action = key.action
        if (action is KeyAction.Function) {
            when (action.key) {
                FunctionKey.ENTER -> return enterLabel
                FunctionKey.SPACE -> spaceLabel?.let { return it }
                else -> {}
            }
        }
        if (shifted && action is KeyAction.Text) return key.label.uppercase()
        return key.label
    }

    override fun onDraw(canvas: Canvas) {
        // Fill only this view's own area: the parent does not clip its children, so
        // Canvas.drawColor() would also paint over the candidate bar above.
        keyPaint.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), keyPaint)
        outlinePaint.color = theme.hintText
        outlinePaint.strokeWidth = dp(1f)
        val touch = active
        val guide = flickPreviewEnabled
        for (item in placed) {
            val key = item.key
            val rect = item.rect
            val pressed = touch != null && touch.placed === item
            val shiftOn = shifted && (key.action as? KeyAction.Function)?.key == FunctionKey.SHIFT

            // With the flick guide the pressed key stands for "no flick" and is
            // highlighted only while the center is selected.
            val centerSelected = pressed && key.hasFlicks && (!guide || touch.direction == -1)
            // The enter key is the one accent-colored key, as on most keyboards.
            val isEnter = (key.action as? KeyAction.Function)?.key == FunctionKey.ENTER
            val accented = centerSelected || shiftOn || (isEnter && !pressed)
            keyPaint.color = when {
                accented -> theme.accent
                pressed -> theme.pressedKey
                key.functional -> theme.functionalKey
                else -> theme.key
            }
            // Borderless themes draw a key shape only where it carries meaning.
            if (theme.keyBorders || accented || pressed) {
                canvas.drawRoundRect(rect, corner, corner, keyPaint)
            }

            var label = displayLabel(key)
            if (pressed && !guide && touch.direction >= 0) {
                key.flicks.getOrNull(touch.direction)?.let { label = it.label }
            }

            labelPaint.color = if (accented) theme.onAccent else theme.text
            val base = rect.height() * if (key.functional || label.length > 1) 0.30f else 0.42f
            labelPaint.textSize = base
            // Long labels ("space", "小゛゜") must not overflow the key.
            val maxWidth = rect.width() - dp(6f)
            val measured = labelPaint.measureText(label)
            if (measured > maxWidth) labelPaint.textSize = base * maxWidth / measured
            val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(label, rect.centerX(), baseline, labelPaint)

            if (key.hasFlicks && !pressed) drawFlickHints(canvas, key, rect)
        }
        if (flickPreviewEnabled && touch != null && touch.placed.key.hasFlicks) {
            drawFlickPreview(canvas, touch)
        }
    }

    /**
     * Flick guide: one bubble per flick target, placed on the side of the pressed
     * key the finger has to move to, with the currently selected one highlighted.
     * Bubbles may extend over the candidate bar (top row) and into the padding
     * below the keys; the parent view disables child clipping for that.
     */
    private fun drawFlickPreview(canvas: Canvas, touch: ActiveTouch) {
        val key = touch.placed.key
        val rect = touch.placed.rect
        val minimum = rect.height() * 0.45f

        for (direction in Key.LEFT..Key.DOWN) {
            val flick = key.flicks.getOrNull(direction) ?: continue
            when (direction) {
                Key.LEFT -> previewRect.set(rect.left - gap - rect.width(), rect.top, rect.left - gap, rect.bottom)
                Key.RIGHT -> previewRect.set(rect.right + gap, rect.top, rect.right + gap + rect.width(), rect.bottom)
                Key.UP -> {
                    // Room above: the rest of this view plus the candidate bar.
                    val room = rect.top - gap + overflowTop
                    val bubble = rect.height().coerceAtMost(room).coerceAtLeast(minimum)
                    val bottom = rect.top - gap
                    previewRect.set(rect.left, bottom - bubble, rect.right, bottom)
                }
                else -> {
                    // Room below: the rest of this view plus the padding under it.
                    // Without enough room the bubble overlaps the lower part of the key.
                    val room = height - rect.bottom - gap + overflowBottom
                    val bubble = rect.height().coerceAtMost(room).coerceAtLeast(minimum)
                    val top = (rect.bottom + gap).coerceAtMost(height + overflowBottom - bubble)
                    previewRect.set(rect.left, top, rect.right, top + bubble)
                }
            }
            // Keep the side bubbles inside the keyboard.
            if (previewRect.left < 0f) previewRect.offset(-previewRect.left, 0f)
            if (previewRect.right > width) previewRect.offset(width - previewRect.right, 0f)

            val selected = touch.direction == direction
            keyPaint.color = if (selected) theme.accent else theme.key
            canvas.drawRoundRect(previewRect, corner, corner, keyPaint)
            if (!selected) canvas.drawRoundRect(previewRect, corner, corner, outlinePaint)

            labelPaint.color = if (selected) theme.onAccent else theme.text
            labelPaint.textSize = minOf(rect.height() * 0.42f, previewRect.height() * 0.62f)
            val baseline = previewRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(flick.label, previewRect.centerX(), baseline, labelPaint)
        }
    }

    private fun drawFlickHints(canvas: Canvas, key: Key, rect: RectF) {
        hintPaint.color = theme.hintText
        hintPaint.textSize = rect.height() * 0.17f
        val inset = hintPaint.textSize * 0.75f
        val middle = rect.centerY() - (hintPaint.descent() + hintPaint.ascent()) / 2f
        key.flicks.getOrNull(Key.LEFT)?.let { canvas.drawText(it.label, rect.left + inset, middle, hintPaint) }
        key.flicks.getOrNull(Key.RIGHT)?.let { canvas.drawText(it.label, rect.right - inset, middle, hintPaint) }
        key.flicks.getOrNull(Key.UP)?.let {
            canvas.drawText(it.label, rect.centerX(), rect.top + inset - hintPaint.ascent() * 0.35f, hintPaint)
        }
        key.flicks.getOrNull(Key.DOWN)?.let {
            canvas.drawText(it.label, rect.centerX(), rect.bottom - inset * 0.45f, hintPaint)
        }
    }

    private fun findKey(x: Float, y: Float): PlacedKey? {
        var best: PlacedKey? = null
        var bestDistance = Float.MAX_VALUE
        for (item in placed) {
            val r = item.rect
            // Distance to the key rectangle; touches in the gaps go to the nearest key.
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = item
            }
        }
        return best
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // A new finger commits whatever the previous finger was holding.
                releaseActive(fire = true)
                val index = event.actionIndex
                val item = findKey(event.getX(index), event.getY(index)) ?: return true
                val touch = ActiveTouch(event.getPointerId(index), item, event.getX(index), event.getY(index))
                active = touch
                if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (item.key.repeatable) {
                    fire(item.key.action)
                    touch.consumed = true
                    timer.postDelayed(repeatRunnable, REPEAT_START_MS)
                } else {
                    timer.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val touch = active ?: return true
                val index = event.findPointerIndex(touch.pointerId)
                if (index < 0 || !touch.placed.key.hasFlicks) return true
                val dx = event.getX(index) - touch.downX
                val dy = event.getY(index) - touch.downY
                val direction = when {
                    abs(dx) < flickThreshold && abs(dy) < flickThreshold -> -1
                    abs(dx) >= abs(dy) -> if (dx < 0) Key.LEFT else Key.RIGHT
                    else -> if (dy < 0) Key.UP else Key.DOWN
                }
                if (direction != touch.direction) {
                    touch.direction = direction
                    if (direction != -1) timer.removeCallbacks(longPressRunnable)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val touch = active ?: return true
                if (event.getPointerId(event.actionIndex) == touch.pointerId) {
                    releaseActive(fire = true)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    private fun releaseActive(fire: Boolean) {
        val touch = active ?: return
        active = null
        timer.removeCallbacks(repeatRunnable)
        timer.removeCallbacks(longPressRunnable)
        if (fire && !touch.consumed) {
            val key = touch.placed.key
            val flick = if (touch.direction >= 0) key.flicks.getOrNull(touch.direction) else null
            // A flick toward a direction without a target falls back to the center.
            fire(flick?.action ?: key.action)
        }
        invalidate()
    }

    private fun cancelTouch() = releaseActive(fire = false)

    private fun fire(action: KeyAction) {
        listener?.onKey(action)
    }

    override fun onDetachedFromWindow() {
        cancelTouch()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_INTERVAL_MS = 55L
        private const val LONG_PRESS_MS = 500L
    }
}
