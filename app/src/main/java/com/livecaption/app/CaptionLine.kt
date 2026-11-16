package com.livecaption.app

import org.json.JSONObject

/**
 * One row of rolling subtitle text: the last finished sentence plus the
 * sentence currently being spoken, trimmed from the front to stay short.
 */
class CaptionLine(private val maxChars: Int = 160) {
    private var committed = ""
    private var live = ""

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
        val tail = full.substring(full.length - maxChars)
        // Cut at a word boundary when there is one, so we never show half a word.
        val space = tail.indexOf(' ')
        return "…" + if (space in 0 until tail.length - 1) tail.substring(space + 1) else tail
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
