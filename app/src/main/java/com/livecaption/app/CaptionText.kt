package com.livecaption.app

import org.json.JSONObject
import java.text.BreakIterator

/**
 * Caption text to show. Everything from [pendingStart] on is still being spoken (it may
 * change), and is drawn dimmer.
 */
data class CaptionText(val text: String, val pendingStart: Int = text.length) {
    val isEmpty: Boolean get() = text.isEmpty()

    companion object {
        val EMPTY = CaptionText("")
    }
}

/** The last [maxChars] of [full], cut at a word (or character-cluster) boundary, with "…" if cut. */
fun displayTail(full: String, maxChars: Int): String {
    if (full.length <= maxChars) return full
    val cut = full.length - maxChars
    // Cut at a nearby word boundary when there is one, so we never show half a word.
    val space = full.indexOfAny(charArrayOf(' ', '​'), cut)
    if (space in cut until cut + MAX_WORD_SEARCH) return "…" + full.substring(space + 1)
    // Scripts written without spaces (Khmer, Chinese, …): never split a character cluster.
    val clusters = BreakIterator.getCharacterInstance().apply { setText(full) }
    val start = if (clusters.isBoundary(cut)) cut else clusters.following(cut)
    return "…" + full.substring(start)
}

private const val MAX_WORD_SEARCH = 24

/** Enough for several lines of small text; the view shows the newest lines. */
const val DEFAULT_MAX_CHARS = 280

/** Pulls [key] ("text" or "partial") out of a Vosk JSON result. */
fun parseVoskResult(json: String, key: String): String =
    try {
        JSONObject(json).optString(key).trim()
    } catch (e: Exception) {
        ""
    }

fun String.capitalizeFirst(): String = replaceFirstChar { it.titlecase() }
