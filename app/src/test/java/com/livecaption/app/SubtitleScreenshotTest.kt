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
            view.setCaption(SentenceSnapshot.of(emptyList(), khmer, khmer.lastIndexOf(' ') + 1), SentenceSnapshot.of(emptyList(), english))
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
    fun sentenceModes() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val khmerSentences = listOf("សូមស្វាគមន៍មកកាន់កម្មវិធីវិញ។", "ថ្ងៃនេះ យើងនឹងធ្វើបាយឆា។")
        val khmerNow = "ដំបូង ដាក់ខ្ទះឲ្យក្តៅ"
        val main = SentenceSnapshot.of(khmerSentences, khmerNow, khmerNow.indexOf(' ') + 1)
        val original = SentenceSnapshot.of(listOf("Welcome back to the show.", "Today we are cooking fried rice."), "First, heat the pan")
        val shown = mutableListOf<Pair<SubtitleView, SubtitleAppearance>>()
        for ((label, sentences) in listOf("Sentence by sentence (3 lines)" to true, "Continuous (3 lines)" to false)) {
            column.addView(
                TextView(context).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 13f
                },
            )
            val appearance = SubtitleAppearance(lines = 3, sentences = sentences)
            val view = SubtitleView(context).apply {
                apply(appearance)
                setCaption(main, original)
            }
            shown += view to appearance
            column.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = (20 * density).toInt() },
            )
        }
        // Which sentences fit is decided once the width is known.
        column.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        column.layout(0, 0, column.measuredWidth, column.measuredHeight)
        shown.forEach { (view, appearance) -> view.apply(appearance) }
        save(column, "sentence-modes.png", backdrop = true)
    }

    @Test
    fun speakers() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val woman = SpeakerInfo(1, VoiceType.WOMAN)
        val man = SpeakerInfo(2, VoiceType.MAN)
        val child = SpeakerInfo(3, VoiceType.CHILD)
        val main = SentenceSnapshot(
            listOf(Sentence("តើកូនចង់ញ៉ាំអ្វី?", woman), Sentence("ប៉ាធ្វើបាយឆាឲ្យ។", man)),
            "ខ្ញុំចង់ញ៉ាំបាយឆា!", currentSpeaker = child,
        )
        val original = SentenceSnapshot(
            listOf(Sentence("What do you want to eat?", woman), Sentence("Dad will make fried rice.", man)),
            "I want fried rice!", currentSpeaker = child,
        )
        val shown = mutableListOf<Pair<SubtitleView, SubtitleAppearance>>()
        for (style in listOf(SubtitleStyle.BOX, SubtitleStyle.OUTLINE)) {
            column.addView(
                TextView(context).apply {
                    text = style.name.lowercase().replaceFirstChar { it.uppercase() } + " · 3 lines"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                },
            )
            val appearance = SubtitleAppearance(style = style, lines = 3)
            val view = SubtitleView(context).apply {
                apply(appearance)
                setCaption(main, original)
            }
            shown += view to appearance
            column.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = (20 * density).toInt() },
            )
        }
        column.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        column.layout(0, 0, column.measuredWidth, column.measuredHeight)
        shown.forEach { (view, appearance) -> view.apply(appearance) }
        save(column, "speakers.png", backdrop = true)
    }

    @Test
    fun overlayWithControls() {
        val prefs = Prefs(context)
        val overlay = CaptionOverlay(context, prefs, NoActions)
        overlay.show()
        overlay.setVoiceButton(VoiceButton.ON)
        overlay.setIndicator(CaptionIndicator.SPEAKING)
        overlay.setCaption(
            SentenceSnapshot.of(listOf("សួស្តីអ្នកទាំងអស់គ្នា។"), "តើថ្ងៃនេះអ្នកសុខសប្បាយជាទេ?"),
            SentenceSnapshot.of(listOf("Hello everyone."), "How are you today?"),
        )
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

    @Test
    fun transcriptScreens() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val store = TranscriptStore(TranscriptActivity.storeDir(app))
        store.deleteAll()
        val day = java.util.Calendar.getInstance().apply { set(2026, 10, 20, 14, 3, 0) }.timeInMillis

        val cooking = TranscriptInfo("20261120-140300", day, TranslationEngine.GEMINI, "en", "km", "a cooking show")
        store.create(cooking).apply {
            append(TranscriptLine(2_000, 6_000, "Today we're making fried rice at home.", "ថ្ងៃនេះ យើងនឹងធ្វើបាយឆានៅផ្ទះ។", 1, VoiceType.WOMAN))
            append(TranscriptLine(9_000, 13_000, "The secret is to use rice from yesterday.", "អាថ៌កំបាំងគឺប្រើបាយពីម្សិលមិញ។", 1, VoiceType.WOMAN))
            append(TranscriptLine(17_000, 21_000, "First, fry two eggs in a hot pan.", "ដំបូង ឆាពងមាន់ពីរគ្រាប់ក្នុងខ្ទះក្តៅ។", 2, VoiceType.MAN))
            append(TranscriptLine(26_000, 29_000, "Then add the rice and the vegetables.", "បន្ទាប់មក ដាក់បាយ និងបន្លែ។", 2, VoiceType.MAN))
            append(TranscriptLine(34_000, 38_000, "Can I taste it?", "ខ្ញុំអាចភ្លក់បានទេ?", 3, VoiceType.CHILD))
            finish(day + 7 * 60_000)
        }
        store.saveSummary(
            cooking.id,
            TranscriptSummary(
                day + 8 * 60_000, "Khmer",
                "របៀបធ្វើបាយឆានៅផ្ទះ\n\n" +
                    "អ្នកធ្វើម្ហូបបង្ហាញពីរបៀបធ្វើបាយឆាងាយៗ ដោយប្រើបាយចាស់ ពងមាន់ និងបន្លែ។\n\n" +
                    "ចំណុចសំខាន់ៗ\n• ប្រើបាយដែលនៅសល់ពីម្សិលមិញ\n• ឆាពងមាន់មុន រួចដាក់បាយ\n• បន្ថែមទឹកស៊ីអ៊ីវ និងខ្ទឹមបារាំង",
            ),
        )
        val news = TranscriptInfo("20261120-190500", day + 5 * 3_600_000, TranslationEngine.LIVE, TranscriptInfo.AUTO, "km")
        store.create(news).apply {
            append(TranscriptLine(1_000, 4_000, "Good evening, here is the news.", "សាយ័ណ្ហសួស្តី នេះជាព័ត៌មាន។"))
        }.close()
        val lecture = TranscriptInfo("20261119-090000", day - 29 * 3_600_000, TranslationEngine.OFFLINE, "en", "")
        store.create(lecture).apply {
            append(TranscriptLine(0, 5_000, "Welcome to today's lecture on physics.", null))
            finish(lecture.startedAt + 52 * 60_000)
        }
        CaptionState.currentTranscript.value = news.id // shows the "Recording" badge

        val list = Robolectric.buildActivity(TranscriptsActivity::class.java).setup().get()
        settle()
        saveWindow(list.window.decorView, "transcripts-list.png")

        CaptionState.currentTranscript.value = null
        val detail = Robolectric.buildActivity(TranscriptActivity::class.java, TranscriptActivity.intent(app, cooking.id)).setup().get()
        settle()
        saveWindow(detail.window.decorView, "transcript-detail.png")
    }

    /** Lets coroutines that load files on background threads finish and update the screen. */
    private fun settle() {
        repeat(30) {
            Thread.sleep(40)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    private fun saveWindow(view: View, name: String) {
        val heightPx = (852 * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        settle()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(File(outDir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun save(view: View, name: String, backdrop: Boolean) {
        repeat(2) {
            // Twice: views that fit text to their width redraw after the first layout.
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            settle()
        }
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
        override fun onOpenTranscript() = Unit
    }
}
