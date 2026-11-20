package com.livecaption.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView

/** How the caption box looks. */
enum class SubtitleStyle {
    /** White text on a translucent dark box. */
    BOX,

    /** No box; white text with a black outline, like film subtitles. */
    OUTLINE,

    /** Yellow text on a solid black box, for the best readability. */
    CONTRAST,
    ;

    fun next(): SubtitleStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): SubtitleStyle = entries.firstOrNull { it.name == name } ?: BOX
    }
}

data class SubtitleAppearance(
    val style: SubtitleStyle = SubtitleStyle.BOX,
    val textSizeSp: Float = 20f,
    val boxOpacity: Float = 0.7f,
    val lines: Int = 2,
    val centered: Boolean = true,
    val showOriginal: Boolean = true,
    /** Each sentence on its own line(s), newest at the bottom, instead of one running text. */
    val sentences: Boolean = true,
)

/** The small dot in the corner of the captions. */
enum class CaptionIndicator { LISTENING, SPEAKING, CONNECTING, PAUSED }

/** Draws text at reduced opacity, outline included. A span object can mark only one range. */
private class DimSpan(private val factor: Float) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.alpha = (tp.alpha * factor).toInt()
    }
}

/** Words that may still change. */
private const val PENDING_ALPHA = 0.62f

/** Earlier sentences, kept above the newest one. */
private const val EARLIER_ALPHA = 0.72f

fun CaptionText.styled(): CharSequence {
    if (pendingStart >= text.length) return text
    return SpannableString(text).apply {
        setSpan(DimSpan(PENDING_ALPHA), pendingStart.coerceAtLeast(0), text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/**
 * Text with an optional outline, that always shows its newest lines: when the text is taller
 * than the view, it scrolls to the bottom instead of cutting off the latest words.
 */
class OutlineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    var outlineWidthPx = 0f
        set(value) {
            field = value
            invalidate()
        }
    var outlineColor = Color.BLACK

    private var drawingOutline = false

    private val stickToBottom = ViewTreeObserver.OnPreDrawListener {
        val l = layout ?: return@OnPreDrawListener true
        val visible = height - totalPaddingTop - totalPaddingBottom
        val target = (l.height - visible).coerceAtLeast(0)
        if (scrollY != target) {
            scrollTo(0, target)
            false // Draw again with the new position.
        } else {
            true
        }
    }

    init {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(stickToBottom)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(stickToBottom)
        super.onDetachedFromWindow()
    }

    // Changing the colour for the outline pass must not schedule another frame.
    override fun invalidate() {
        if (!drawingOutline) super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (outlineWidthPx > 0f) {
            val fill = textColors
            drawingOutline = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidthPx
            paint.strokeJoin = Paint.Join.ROUND
            setTextColor(outlineColor)
            super.onDraw(canvas)
            paint.style = Paint.Style.FILL
            setTextColor(fill)
            drawingOutline = false
        }
        super.onDraw(canvas)
    }
}

/**
 * The caption box: original text (small, one line) above the translation (large, several
 * lines). Used by the floating overlay and by the preview in settings.
 */
class SubtitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val secondary = OutlineTextView(context).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        visibility = GONE
    }
    private val primary = OutlineTextView(context).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.08f)
    }
    private val textColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(secondary, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(primary, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val dot = View(context)
    private val boxBackground = GradientDrawable().apply { cornerRadius = dp(14f) }
    private var pulse: ObjectAnimator? = null
    private var appearance = SubtitleAppearance()
    private var indicator = CaptionIndicator.LISTENING
    private var empty = true
    private var idleAlphaTarget = 1f
    private var shownMain = SentenceSnapshot.EMPTY
    private var shownOriginal: SentenceSnapshot? = null
    private var message: String? = null

    /** Widest the box gets, so lines stay easy to read on tablets and in landscape. */
    var maxBoxWidthPx = dp(720f).toInt()

    init {
        background = boxBackground
        addView(textColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            dot,
            LayoutParams(dp(8f).toInt(), dp(8f).toInt(), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(7f).toInt()
                topMargin = dp(7f).toInt()
            },
        )
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        apply(appearance)
        setIndicator(CaptionIndicator.LISTENING)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (width > maxBoxWidthPx) MeasureSpec.makeMeasureSpec(maxBoxWidthPx, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }

    fun apply(a: SubtitleAppearance) {
        appearance = a
        val textPx = sp(a.textSizeSp)
        primary.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        secondary.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx * 0.72f)
        // A fixed height keeps the box from jumping as words come and go.
        primary.setLines(a.lines)
        val gravity = (if (a.centered) Gravity.CENTER_HORIZONTAL else Gravity.START) or Gravity.BOTTOM
        primary.gravity = gravity
        secondary.gravity = gravity

        when (a.style) {
            SubtitleStyle.BOX -> {
                boxBackground.setColor(Color.argb((a.boxOpacity * 255).toInt(), 12, 12, 14))
                setTextColors(Color.WHITE, Color.argb(200, 235, 235, 240), outline = 0f)
            }
            SubtitleStyle.OUTLINE -> {
                boxBackground.setColor(Color.TRANSPARENT)
                setTextColors(Color.WHITE, Color.argb(230, 235, 235, 240), outline = textPx * 0.13f)
            }
            SubtitleStyle.CONTRAST -> {
                boxBackground.setColor(Color.argb(250, 0, 0, 0))
                setTextColors(Color.rgb(255, 230, 0), Color.argb(220, 255, 255, 255), outline = 0f)
            }
        }
        // Room on the left for the status dot, in every style.
        val horizontal = dp(if (a.style == SubtitleStyle.OUTLINE) 16f else 18f).toInt()
        textColumn.setPadding(horizontal, dp(8f).toInt(), horizontal, dp(10f).toInt())
        renderCaption()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Which sentences fit depends on the width: fit them again (after this layout pass).
        if (w != oldw && appearance.sentences) post { renderCaption() }
    }

    private fun setTextColors(main: Int, original: Int, outline: Float) {
        primary.setTextColor(main)
        secondary.setTextColor(original)
        primary.outlineWidthPx = outline
        secondary.outlineWidthPx = outline * 0.8f
        // Soft shadow helps the box styles too, over bright video.
        val shadow = if (outline > 0f) 0f else dp(2f)
        primary.setShadowLayer(shadow, 0f, dp(1f), Color.argb(160, 0, 0, 0))
    }

    /** Shows [main] (the translation, or the transcript) and [original] in the smaller line above. */
    fun setCaption(main: SentenceSnapshot, original: SentenceSnapshot?) {
        message = null
        shownMain = main
        shownOriginal = original
        renderCaption()
    }

    fun clearCaption() = setCaption(SentenceSnapshot.EMPTY, null)

    /** A dim message in place of captions ("Listening…", "Paused"). */
    fun setMessage(text: String) {
        message = text
        renderCaption()
    }

    private fun renderCaption() {
        val msg = message
        if (msg != null) {
            primary.text = CaptionText(msg, 0).styled()
            secondary.text = ""
            secondary.visibility = GONE
            empty = false
            updateIdleLook()
            return
        }
        val original = shownOriginal
        if (appearance.sentences) {
            primary.text = fitSentences(shownMain)
            secondary.text = original?.newest()?.styled() ?: ""
        } else {
            primary.text = shownMain.continuous().styled()
            secondary.text = original?.continuous()?.styled() ?: ""
        }
        secondary.visibility = if (appearance.showOriginal && secondary.text.isNotEmpty()) VISIBLE else GONE
        empty = shownMain.isEmpty && (original == null || original.isEmpty)
        updateIdleLook()
    }

    /**
     * Sentence by sentence: the newest sentence, plus as many earlier ones (whole, each on its
     * own line, a little dimmer) as fit in the chosen number of lines.
     */
    private fun fitSentences(s: SentenceSnapshot): CharSequence {
        val items = ArrayList<CaptionText>()
        s.done.takeLast(MAX_SENTENCES_SHOWN).forEach { items += CaptionText(it) }
        if (s.current.isNotEmpty()) items += CaptionText(s.current, s.pendingStart)
        if (items.isEmpty()) return ""
        val width = primary.width - primary.totalPaddingLeft - primary.totalPaddingRight
        val from = if (width <= 0) items.size - 1 else Sentences.fitNewest(items.size, appearance.lines) { start ->
            lineCount(items.subList(start, items.size).joinToString("\n") { it.text }, width)
        }
        val out = SpannableStringBuilder()
        for (i in from until items.size) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(items[i].text)
        }
        val newest = items.last()
        val newestStart = out.length - newest.text.length
        if (newestStart > 0) out.setSpan(DimSpan(EARLIER_ALPHA), 0, newestStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (newest.pendingStart < newest.text.length) {
            out.setSpan(DimSpan(PENDING_ALPHA), newestStart + newest.pendingStart.coerceAtLeast(0), out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /** Lines [text] takes in the translation line at [width], laid out the way the view does. */
    private fun lineCount(text: String, width: Int): Int =
        StaticLayout.Builder.obtain(text, 0, text.length, primary.paint, width)
            .setLineSpacing(primary.lineSpacingExtra, primary.lineSpacingMultiplier)
            .setIncludePad(primary.includeFontPadding)
            .setBreakStrategy(primary.breakStrategy)
            .setHyphenationFrequency(primary.hyphenationFrequency)
            .build()
            .lineCount

    fun setIndicator(state: CaptionIndicator) {
        indicator = state
        (dot.background as GradientDrawable).setColor(
            when (state) {
                CaptionIndicator.LISTENING -> Color.argb(150, 255, 255, 255)
                CaptionIndicator.SPEAKING -> Color.rgb(76, 217, 100)
                CaptionIndicator.CONNECTING -> Color.rgb(255, 179, 0)
                CaptionIndicator.PAUSED -> Color.argb(110, 160, 160, 160)
            },
        )
        pulse?.cancel()
        dot.alpha = 1f
        if (state == CaptionIndicator.SPEAKING || state == CaptionIndicator.CONNECTING) {
            pulse = ObjectAnimator.ofFloat(dot, ALPHA, 1f, 0.3f).apply {
                duration = 650
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    /** Without text the box fades back, but stays findable (and tappable). */
    private fun updateIdleLook() {
        val target = if (empty) 0.4f else 1f
        // Compare with where the fade is heading, not where it is now: text that arrives while
        // the box is still fading out must turn the fade around.
        if (idleAlphaTarget != target) {
            idleAlphaTarget = target
            textColumn.animate().cancel()
            textColumn.animate().alpha(target).setDuration(220).start()
        }
        boxBackground.alpha = if (empty && appearance.style != SubtitleStyle.CONTRAST) 110 else 255
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private companion object {
        /** Earlier sentences considered for the box; more never fit anyway. */
        const val MAX_SENTENCES_SHOWN = 4
    }
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
