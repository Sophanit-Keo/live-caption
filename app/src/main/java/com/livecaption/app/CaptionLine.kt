package com.livecaption.app

import org.json.JSONObject
import java.text.BreakIterator

/**
 * One row of rolling subtitle text: the last finished sentence plus the
 * sentence currently being spoken, trimmed from the front to stay short.
 */
class CaptionLine(private val maxChars: Int = 160) {
    private var committed = ""
    private var live = ""

    private companion object {
        const val MAX_WORD_SEARCH = 24
    }

    /** Update the in-progress sentence. */
    fun setLive(text: String) {
        live = text.trim()
    }

    /** A sentence is complete; it replaces the previous finished one. */
    fun commit(text: String) {
        committed = text.trim()
        live = ""
    }

    fun clear() {
        committed = ""
        live = ""
    }

    fun render(): String {
        val full = listOf(committed, live).filter { it.isNotEmpty() }.joinToString(" ")
        if (full.length <= maxChars) return full
        val cut = full.length - maxChars
        // Cut at a nearby word boundary when there is one, so we never show half a word.
        val space = full.indexOf(' ', cut)
        if (space in cut until cut + MAX_WORD_SEARCH) return "…" + full.substring(space + 1)
        // Scripts written without spaces (Khmer, Chinese, …): never split a character cluster.
        val clusters = BreakIterator.getCharacterInstance().apply { setText(full) }
        val start = if (clusters.isBoundary(cut)) cut else clusters.following(cut)
        return "…" + full.substring(start)
    }
}

/** Pulls [key] ("text" or "partial") out of a Vosk JSON result. */
fun parseVoskResult(json: String, key: String): String =
    try {
        JSONObject(json).optString(key).trim()
    } catch (e: Exception) {
        ""
    }

fun String.capitalizeFirst(): String = replaceFirstChar { it.titlecase() }
