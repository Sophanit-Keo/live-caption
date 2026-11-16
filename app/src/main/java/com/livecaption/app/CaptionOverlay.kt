package com.livecaption.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
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

    private val secondary = TextView(context).apply {
        setTextColor(Color.argb(190, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp * 0.75f)
        maxLines = 2
        visibility = View.GONE
    }
    private val primary = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        maxLines = 3
    }
    private val closeButton = TextView(context).apply {
        text = "✕"
        setTextColor(Color.argb(200, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        contentDescription = context.getString(R.string.close)
        setOnClickListener { onClose() }
    }
    private val root = FrameLayout(context).apply {
        setBackgroundResource(R.drawable.overlay_background)
        val pad = dp(14)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, dp(10), dp(48), dp(12))
                addView(secondary)
                addView(primary)
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
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

    init {
        installDrag()
    }

    fun show() {
        if (attached) return
        windowManager.addView(root, params)
        attached = true
        setStatus(context.getString(R.string.listening))
    }

    fun hide() {
        handler.removeCallbacks(fadeOut)
        if (!attached) return
        windowManager.removeView(root)
        attached = false
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
        primary.text = primaryText
        secondary.text = secondaryText
        secondary.visibility = if (secondaryText.isEmpty()) View.GONE else View.VISIBLE
        root.alpha = if (primaryText.isEmpty() && secondaryText.isEmpty()) 0.35f else 1f
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

    private companion object {
        const val IDLE_CLEAR_MS = 8_000L
    }
}
