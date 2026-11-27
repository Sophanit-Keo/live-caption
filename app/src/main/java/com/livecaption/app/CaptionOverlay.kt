package com.livecaption.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** State of the overlay's voice button. */
enum class VoiceButton {
    /** No voice for this session (captions only). */
    HIDDEN,

    /** Translations are spoken. */
    ON,

    /** Voice is on but waits for headphones. */
    WAITING_FOR_HEADPHONES,

    /** Muted by the user. */
    OFF,
}

/**
 * The floating captions drawn on top of every app. Drag them to move them; tap them for a
 * toolbar (pause, voice, text size, style, original text, lock, settings, close).
 * Appearance changes made here are saved in [prefs], so they also apply next time.
 */
class CaptionOverlay(
    private val context: Context,
    private val prefs: Prefs,
    private val actions: Actions,
) {
    interface Actions {
        fun onClose()
        fun onVoiceToggle()
        fun onPauseToggle()
        fun onLockChanged(locked: Boolean)
        fun onOpenSettings()
        fun onOpenTranscript()
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val clearIdle = Runnable { subtitles.clearCaption() }
    private val hideControls = Runnable { setControlsVisible(false) }

    private val subtitles = SubtitleView(context)

    private val pauseButton = iconButton(R.drawable.ic_pause, R.string.control_pause) { actions.onPauseToggle() }
    private val voiceButton = iconButton(R.drawable.ic_voice_on, R.string.voice_toggle) { actions.onVoiceToggle() }
    private val originalButton = iconButton(R.drawable.ic_subtitles, R.string.control_original) {
        prefs.showOriginal = !prefs.showOriginal
    }
    private val controls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(2))
        addView(pauseButton)
        addView(voiceButton)
        addView(divider())
        addView(textButton("A−", 14f, R.string.control_smaller) { prefs.textSizeSp -= TEXT_STEP_SP })
        addView(textButton("A+", 18f, R.string.control_bigger) { prefs.textSizeSp += TEXT_STEP_SP })
        addView(textButton("Aa", 16f, R.string.control_style) { prefs.subtitleStyle = prefs.subtitleStyle.next() })
        addView(originalButton)
        addView(divider())
        addView(iconButton(R.drawable.ic_notes, R.string.control_transcript) { actions.onOpenTranscript() })
        addView(iconButton(R.drawable.ic_lock, R.string.control_lock) { setLocked(true) })
        addView(iconButton(R.drawable.ic_settings, R.string.control_settings) { actions.onOpenSettings() })
        addView(iconButton(R.drawable.ic_close, R.string.close) { actions.onClose() })
    }
    private val controlsBar = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundResource(R.drawable.controls_background)
        addView(controls)
        visibility = View.GONE
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        // Above the captions, so showing it never moves the captions.
        addView(
            controlsBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6) },
        )
        addView(subtitles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val params = WindowManager.LayoutParams(
        boxWidth(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = prefs.overlayY.takeIf { it >= 0 } ?: dp(72)
    }

    private var attached = false
    var isLocked = false
        private set

    init {
        installGestures()
        applyAppearance()
        fitControls()
    }

    fun show() {
        if (attached) return
        windowManager.addView(root, params)
        attached = true
        setStatus(context.getString(R.string.overlay_first_hint))
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        if (!attached) return
        windowManager.removeView(root)
        attached = false
    }

    /** Shows a status message (e.g. "Listening…") in place of captions. */
    fun setStatus(message: String) {
        handler.removeCallbacks(clearIdle)
        subtitles.setMessage(message)
    }

    /** Shows captions; [original] is the smaller line above (null to hide). */
    fun setCaption(main: SentenceSnapshot, original: SentenceSnapshot?) {
        subtitles.setCaption(main, original)
        // Clear stale subtitles once speech has stopped for a while.
        handler.removeCallbacks(clearIdle)
        handler.postDelayed(clearIdle, IDLE_CLEAR_MS)
    }

    fun setIndicator(state: CaptionIndicator) = subtitles.setIndicator(state)

    fun setPaused(paused: Boolean) {
        pauseButton.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        pauseButton.contentDescription = context.getString(if (paused) R.string.control_resume else R.string.control_pause)
    }

    fun setVoiceButton(state: VoiceButton) {
        voiceButton.visibility = if (state == VoiceButton.HIDDEN) View.GONE else View.VISIBLE
        voiceButton.setImageResource(
            when (state) {
                VoiceButton.OFF -> R.drawable.ic_voice_off
                VoiceButton.WAITING_FOR_HEADPHONES -> R.drawable.ic_headphones
                else -> R.drawable.ic_voice_on
            },
        )
        voiceButton.alpha = if (state == VoiceButton.WAITING_FOR_HEADPHONES) 0.5f else 1f
        voiceButton.contentDescription = context.getString(
            when (state) {
                VoiceButton.OFF -> R.string.voice_turn_on
                VoiceButton.WAITING_FOR_HEADPHONES -> R.string.voice_waiting_headphones
                else -> R.string.voice_turn_off
            },
        )
    }

    /** Re-reads the look of the captions from settings. */
    fun applyAppearance() {
        subtitles.apply(prefs.appearance())
        originalButton.alpha = if (prefs.showOriginal) 1f else 0.45f
    }

    /**
     * Locked captions let touches through to the app underneath (for video controls under
     * the captions). They are unlocked from the notification or by opening Live Caption.
     */
    fun setLocked(locked: Boolean) {
        if (isLocked == locked) return
        isLocked = locked
        setControlsVisible(false)
        params.flags = if (locked) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (attached) windowManager.updateViewLayout(root, params)
        if (locked) setStatus(context.getString(R.string.overlay_locked_hint))
        actions.onLockChanged(locked)
    }

    /** The screen rotated or resized: keep the box on screen and readable. */
    fun onConfigurationChanged() {
        params.width = boxWidth()
        fitControls()
        params.y = params.y.coerceIn(0, maxY())
        if (attached) windowManager.updateViewLayout(root, params)
    }

    private fun setControlsVisible(visible: Boolean) {
        handler.removeCallbacks(hideControls)
        controlsBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) handler.postDelayed(hideControls, CONTROLS_VISIBLE_MS)
    }

    /** Any toolbar use keeps it open a little longer. */
    private fun touchedControls() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, CONTROLS_VISIBLE_MS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installGestures() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawY = 0f
        var downY = 0
        var dragging = false
        subtitles.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    downY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downRawY
                    if (!dragging && abs(dy) > slop) dragging = true
                    if (dragging && attached) {
                        // Gravity is BOTTOM, so y grows upward.
                        params.y = (downY - dy).toInt().coerceIn(0, maxY())
                        windowManager.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) prefs.overlayY = params.y
                    else setControlsVisible(controlsBar.visibility != View.VISIBLE)
                }
            }
            true
        }
        // Accessibility: a click on the captions toggles the toolbar too.
        subtitles.setOnClickListener { setControlsVisible(controlsBar.visibility != View.VISIBLE) }
        subtitles.contentDescription = context.getString(R.string.overlay_description)
    }

    private fun iconButton(icon: Int, description: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(description)
        setBackgroundResource(R.drawable.control_ripple)
        layoutParams = LinearLayout.LayoutParams(dp(BUTTON_MAX_DP), dp(44))
        setOnClickListener {
            touchedControls()
            onClick()
        }
    }

    private fun textButton(label: String, sizeSp: Float, description: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        contentDescription = context.getString(description)
        setBackgroundResource(R.drawable.control_ripple)
        layoutParams = LinearLayout.LayoutParams(dp(BUTTON_MAX_DP), dp(44))
        setOnClickListener {
            touchedControls()
            onClick()
        }
    }

    /** Narrows the toolbar buttons on small screens, so all of them (Close included) fit. */
    private fun fitControls() {
        val buttons = (0 until controls.childCount).map { controls.getChildAt(it) }.filterNot { it is Divider }
        val fixed = dp(12) + (controls.childCount - buttons.size) * dp(7) // padding + dividers
        val size = ((boxWidth() - fixed) / buttons.size.coerceAtLeast(1)).coerceIn(dp(BUTTON_MIN_DP), dp(BUTTON_MAX_DP))
        for (b in buttons) b.layoutParams = (b.layoutParams as LinearLayout.LayoutParams).apply { width = size }
    }

    private class Divider(context: Context) : View(context)

    private fun divider() = Divider(context).apply {
        setBackgroundColor(Color.argb(60, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(24)).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        }
    }

    private fun boxWidth(): Int {
        val metrics = context.resources.displayMetrics
        return minOf((metrics.widthPixels * 0.96f).toInt(), dp(720))
    }

    private fun maxY(): Int =
        (context.resources.displayMetrics.heightPixels - root.height.coerceAtLeast(dp(80))).coerceAtLeast(0)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val IDLE_CLEAR_MS = 8_000L
        const val CONTROLS_VISIBLE_MS = 6_000L
        const val TEXT_STEP_SP = 2f
        const val BUTTON_MAX_DP = 44
        const val BUTTON_MIN_DP = 32
    }
}
