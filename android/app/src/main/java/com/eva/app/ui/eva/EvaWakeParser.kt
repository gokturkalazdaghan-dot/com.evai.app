// android/app/src/main/java/com/eva/app/ui/eva/EvaWakeParser.kt
package com.eva.app.ui.eva

/**
 * "hey Eva" ve yakin duyuluslari.
 *
 * SpeechRecognizer Turkce/Ingilizce karisik duyar. Tek basina "eva"
 * yalnizca cumle basindaysa uyanistir.
 */
object EvaWakeParser {

    private val wakeAtStart = Regex(
        """^\s*(?:hey|hei|hi|hello|merhaba|selam|alo)?\s*(?:eva(?:i|n)?|evva|ewa)\b[\s,!.?]*""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(raw: String): WakeUtterance? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val match = wakeAtStart.find(text) ?: return null
        val rest = text.substring(match.range.last + 1).trim()
        return WakeUtterance(rest)
    }
}

data class WakeUtterance(
    val afterWake: String,
)
