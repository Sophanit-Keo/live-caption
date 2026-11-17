package com.livecaption.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** The floating subtitle box drawn on top of every app. Drag it to move it. */
class CaptionOverlay(
    private val context: Context,
    textSizeSp: Float,
    private val onClose: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val fadeOut = Runnable { setTexts("", "") }

    private val secondaryView = TextView(context).apply {
        setTextColor(Color.argb(190, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp * 0.75f)
        maxLines = 2
        visibility = View.GONE
    }
    private val primaryView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        maxLines = 3
    }
    private val secondary = FadingText(secondaryView, goneWhenEmpty = true)
    private val primary = FadingText(primaryView, goneWhenEmpty = false)

    private val closeButton = TextView(context).apply {
        text = "✕"
        setTextColor(Color.argb(200, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        contentDescription = context.getString(R.string.close)
        setOnClickListener { onClose() }
    }

    /** The visible box. It sits inside [root] so it can slide without moving the window. */
    private val card = FrameLayout(context).apply {
        setBackgroundResource(R.drawable.overlay_background)
        val pad = dp(14)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, dp(10), dp(48), dp(12))
                addView(secondaryView)
                addView(primaryView)
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
    }
    private val root = FrameLayout(context).apply {
        addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        horizontalMargin = 0.03f
        y = dp(96)
    }

    private var attached = false
    private var dimmed = false

    init {
        installDrag()
        installRollUp()
    }

    fun show() {
        if (attached) return
        root.alpha = 0f
        windowManager.addView(root, params)
        attached = true
        dimmed = false
        root.animate().alpha(1f).setDuration(FADE_MS)
        setStatus(context.getString(R.string.listening))
    }

    fun hide() {
        handler.removeCallbacks(fadeOut)
        if (!attached) return
        attached = false
        root.animate().alpha(0f).setDuration(FADE_MS).withEndAction { windowManager.removeView(root) }
    }

    /** Shows a status message (e.g. "Listening…") in place of captions. */
    fun setStatus(message: String) {
        handler.removeCallbacks(fadeOut)
        setTexts(message, "")
    }

    /** Shows caption text; [secondaryText] is the smaller line above it (empty to hide). */
    fun setCaption(primaryText: String, secondaryText: String) {
        setTexts(primaryText, secondaryText)
        // Clear stale subtitles once speech has stopped for a while.
        handler.removeCallbacks(fadeOut)
        handler.postDelayed(fadeOut, IDLE_CLEAR_MS)
    }

    private fun setTexts(primaryText: String, secondaryText: String) {
        primary.set(primaryText)
        secondary.set(secondaryText)
        // An empty box stays on screen, dimmed, so it can still be dragged and closed.
        val dim = primaryText.isEmpty() && secondaryText.isEmpty()
        if (dim != dimmed && attached) {
            dimmed = dim
            root.animate().alpha(if (dim) IDLE_ALPHA else 1f).setDuration(if (dim) DIM_MS else FADE_MS)
        }
    }

    /**
     * When the text wraps onto another line the window grows upward at once.
     * Start the box where it was and slide it up, so the new line rolls in
     * from the bottom edge instead of the whole box jumping.
     */
    private fun installRollUp() {
        card.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val oldHeight = oldBottom - oldTop
            val grew = (bottom - top) - oldHeight
            if (grew > 0 && oldHeight > 0 && attached) {
                card.translationY += grew
                card.animate().translationY(0f).setDuration(ROLL_MS).setInterpolator(DecelerateInterpolator())
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawY = 0f
        var downY = 0
        var dragging = false
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    downY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downRawY
                    if (!dragging && abs(dy) > slop) dragging = true
                    if (dragging && attached) {
                        // Gravity is BOTTOM, so y grows upward.
                        val maxY = context.resources.displayMetrics.heightPixels - root.height
                        params.y = (downY - dy).toInt().coerceIn(0, maxY.coerceAtLeast(0))
                        windowManager.updateViewLayout(root, params)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    /**
     * A line of text that cross-fades when its content is replaced. Text that merely
     * grows (the sentence being spoken) is set directly, so words appear without flicker.
     */
    private class FadingText(private val view: TextView, private val goneWhenEmpty: Boolean) {
        private var target = ""
        private var fadingOut = false
        private var lastFade = 0L

        fun set(text: String) {
            if (text == target) return
            val previous = target
            target = text
            if (fadingOut) return // the fade in progress ends by showing the newest text

            val now = SystemClock.uptimeMillis()
            when {
                previous.isEmpty() -> {
                    view.alpha = 0f
                    apply()
                    view.animate().alpha(1f).setDuration(TEXT_IN_MS)
                }
                // Live translations can reword a sentence several times a second; don't fade each one.
                isContinuation(previous, text) || now - lastFade < MIN_FADE_GAP_MS -> apply()
                else -> {
                    fadingOut = true
                    lastFade = now
                    view.animate().alpha(0f).setDuration(TEXT_OUT_MS).withEndAction {
                        fadingOut = false
                        apply()
                        view.animate().alpha(1f).setDuration(TEXT_IN_MS)
                    }
                }
            }
        }

        private fun apply() {
            view.text = target
            if (goneWhenEmpty) view.visibility = if (target.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val IDLE_CLEAR_MS = 8_000L
        const val IDLE_ALPHA = 0.35f
        const val FADE_MS = 180L
        const val DIM_MS = 400L
        const val ROLL_MS = 180L
        const val TEXT_OUT_MS = 90L
        const val TEXT_IN_MS = 140L
        const val MIN_FADE_GAP_MS = 1_000L
    }
}
