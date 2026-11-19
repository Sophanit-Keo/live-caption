package com.livecaption.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the subtitle UI to PNG files (build/screenshots) for review. Only runs with
 * `./gradlew testDebugUnitTest -Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class SubtitleScreenshotTest {

    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_LiveCaption)
    private val density get() = context.resources.displayMetrics.density
    private val widthPx get() = (393 * density).toInt()

    private val khmer = "សួស្តី តើថ្ងៃនេះអ្នកសុខសប្បាយជាទេ? ខ្ញុំចង់ទៅផ្សារ"
    private val english = "Hello, how are you today? I want to go to the market"

    @Test
    fun subtitleStyles() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        for (style in SubtitleStyle.entries) {
            column.addView(
                TextView(context).apply {
                    text = style.name.lowercase().replaceFirstChar { it.uppercase() }
                    setTextColor(Color.WHITE)
                    textSize = 13f
                },
            )
            val view = SubtitleView(context)
            view.apply(SubtitleAppearance(style = style, textSizeSp = 20f, lines = 2))
            // The last words are still being spoken: drawn dimmer.
            view.setText(CaptionText(khmer, khmer.lastIndexOf(' ') + 1), CaptionText(english))
            view.setIndicator(if (style == SubtitleStyle.BOX) CaptionIndicator.SPEAKING else CaptionIndicator.LISTENING)
            column.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = (20 * density).toInt() },
            )
        }
        save(column, "subtitle-styles.png", backdrop = true)
    }

    @Test
    fun overlayWithControls() {
        val prefs = Prefs(context)
        val overlay = CaptionOverlay(context, prefs, NoActions)
        overlay.show()
        overlay.setVoiceButton(VoiceButton.ON)
        overlay.setIndicator(CaptionIndicator.SPEAKING)
        overlay.setCaption(CaptionText(khmer), CaptionText(english))
        CaptionOverlay::class.java.getDeclaredMethod("setControlsVisible", Boolean::class.java)
            .apply { isAccessible = true }
            .invoke(overlay, true)
        val root = CaptionOverlay::class.java.getDeclaredField("root").apply { isAccessible = true }.get(overlay) as View
        save(root, "overlay-controls.png", backdrop = true)
        overlay.hide()
    }

    @Test
    fun settingsScreen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val scroll = activity.findViewById<ScrollView>(R.id.root)
        val content = scroll.getChildAt(0)
        content.setBackgroundColor(Color.WHITE)
        save(content, "settings.png", backdrop = false)
    }

    private fun save(view: View, name: String, backdrop: Boolean) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val margin = if (backdrop) (24 * density).toInt() else 0
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight + 2 * margin, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (backdrop) {
            // Stand-in for a video frame: dark and light areas, to judge readability on both.
            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(),
                    intArrayOf(Color.rgb(20, 30, 40), Color.rgb(120, 140, 160), Color.rgb(235, 225, 200)),
                    null, Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
        }
        canvas.translate(0f, margin.toFloat())
        view.draw(canvas)
        FileOutputStream(File(outDir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private object NoActions : CaptionOverlay.Actions {
        override fun onClose() = Unit
        override fun onVoiceToggle() = Unit
        override fun onPauseToggle() = Unit
        override fun onLockChanged(locked: Boolean) = Unit
        override fun onOpenSettings() = Unit
    }
}
