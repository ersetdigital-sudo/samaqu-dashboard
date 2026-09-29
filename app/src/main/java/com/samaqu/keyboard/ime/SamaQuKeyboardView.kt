package com.samaqu.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.samaqu.keyboard.R

/**
 * KeyboardView that paints its own Gboard-style key faces.
 *
 * The decompiled original reached into private framework fields (`mPaint`,
 * `mDrawPending`) via reflection; this rewrite avoids reflection entirely.
 *
 * Geometry: the keyboard XML uses zero gaps and rows that sum to exactly 100%
 * width, so the framework's key rectangles tile the keyboard edge to edge. The
 * visible spacing comes from drawing each face *inset* inside its rect, which is
 * why the deck can never overflow or clip the right-hand column.
 *
 * Drawing order matters: faces are painted first, then `super.onDraw` renders the
 * key labels on top. The layout sets `keyBackground` to transparent so the
 * framework does not paint over these faces. Because of that, the pressed state
 * has to be replicated here, driven by the IME's onPress/onRelease.
 */
class SamaQuKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : KeyboardView(context, attrs) {

    private val density = resources.displayMetrics.density

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_face)
    }

    private val facePressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_pressed)
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SHADOW
    }

    /** Tint used for the shift key while caps is on. */
    private val faceShiftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_purple_tint)
    }

    /** Corner radius and half the visual gap between neighbouring keys. */
    private val radius = density * 6f
    private val inset = density * 1.5f

    /**
     * True while caps is on. The layouts give every letter an explicit label, which
     * `setShifted` does not rewrite, so without this the shift tap had no visible effect.
     */
    var shiftActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Primary code of the key currently held down, or [NO_CODE]. */
    var pressedCode: Int = NO_CODE
        private set

    fun setPressedCode(code: Int) {
        if (pressedCode == code) return
        pressedCode = code
        invalidate()
    }

    // ------------------------------------------------------------- long press

    /**
     * Fired when Enter is held down long enough to mean "open the Quick Calculator".
     *
     * The framework's own long-press hook ([onLongPress]) only ever opens a popup keyboard, so
     * the timing is tracked here instead - and the tap that follows is left to the IME to
     * swallow through [enterLongPressAt].
     */
    var onEnterLongPress: (() -> Unit)? = null

    /** Uptime of the last Enter long press, so the release that follows it can be ignored. */
    var enterLongPressAt: Long = 0L
        private set

    fun clearEnterLongPress() {
        enterLongPressAt = 0L
    }

    private val longPressHandler = Handler(Looper.getMainLooper())

    /** Key the finger went down on, or null when it is between keys. */
    private var heldKey: Keyboard.Key? = null

    private val longPressRunnable = Runnable {
        val key = heldKey ?: return@Runnable
        if (key.codes.firstOrNull() != CODE_ENTER) return@Runnable

        enterLongPressAt = SystemClock.uptimeMillis()
        // A key that was long-pressed may not get an onRelease (the framework can abort it),
        // so the pressed face is restored here rather than left stuck in the accent colour.
        setPressedCode(NO_CODE)
        onEnterLongPress?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                heldKey = keyAt(event.x, event.y)
                if (heldKey?.codes?.firstOrNull() == CODE_ENTER) {
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
            }
            MotionEvent.ACTION_MOVE ->
                // Sliding off the key cancels it, exactly like the framework does.
                if (heldKey != null && keyAt(event.x, event.y) !== heldKey) abortLongPress()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> abortLongPress()
        }
        // The framework keeps doing its normal job: press feedback, repeat, tap on release.
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        abortLongPress()
        super.onDetachedFromWindow()
    }

    /**
     * Claims the framework's long press for Enter so it cannot run a behaviour of its own on
     * top of the calculator.
     */
    override fun onLongPress(key: Keyboard.Key): Boolean =
        key.codes.firstOrNull() == CODE_ENTER || super.onLongPress(key)

    private fun abortLongPress() {
        longPressHandler.removeCallbacks(longPressRunnable)
        heldKey = null
    }

    /** Key under a point, in this view's own coordinates. */
    private fun keyAt(x: Float, y: Float): Keyboard.Key? {
        val keys = keyboard?.keys ?: return null
        val px = x - paddingLeft
        val py = y - paddingTop
        return keys.firstOrNull {
            px >= it.x && px < it.x + it.width && py >= it.y && py < it.y + it.height
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawKeyFaces(canvas)
        // Framework pass: draws the (transparent) key background and the labels.
        super.onDraw(canvas)
    }

    private fun drawKeyFaces(canvas: Canvas) {
        val keys = keyboard?.keys ?: return

        val padLeft = paddingLeft.toFloat()
        val padTop = paddingTop.toFloat()

        for (key in keys) {
            val left = key.x + padLeft + inset
            val top = key.y + padTop + inset
            val right = left + key.width - inset * 2f
            val bottom = top + key.height - inset * 2f
            if (right <= left || bottom <= top) continue

            val pressed = key.codes[0] == pressedCode

            // A 1dp drop shadow makes the faces read as physical keys; it is
            // skipped while pressed so the key looks pushed in.
            if (!pressed) {
                canvas.drawRoundRect(
                    RectF(left, top + density, right, bottom + density),
                    radius, radius, shadowPaint
                )
            }

            val face = when {
                pressed -> facePressedPaint
                shiftActive && key.codes[0] == CODE_SHIFT -> faceShiftPaint
                else -> facePaint
            }

            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, face)
        }
    }

    companion object {
        const val CODE_SHIFT = -1
        const val CODE_DELETE = -5
        const val CODE_ENTER = -4
        const val CODE_SYMBOLS = -100
        const val CODE_QWERTY = -101
        const val CODE_EMOJI = -200

        const val NO_CODE = Int.MIN_VALUE

        private const val SHADOW = 0x14000000

        /**
         * Hold time before Enter counts as a long press. Slightly under Android's own 500ms
         * so the calculator feels immediate, but far above an ordinary tap.
         */
        private const val LONG_PRESS_MS = 400L
    }
}
