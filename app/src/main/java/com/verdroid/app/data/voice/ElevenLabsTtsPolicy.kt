package com.verdroid.app.data.voice

/** Pure, testable guardrails for the paid ElevenLabs speech endpoint. */
object ElevenLabsTtsPolicy {
    const val DEFAULT_MODEL = "eleven_v4_turbo"
    const val DEFAULT_VOICE_ID = "pzXYLespwi48YnRBYxIW" // Bri: calm, clear female voice; Estonian accent unverified.
    const val REPLY_CHAR_CAP = 1200
    const val DAILY_CHAR_CAP = 8000

    fun validVoiceId(value: String): Boolean =
        value.length in 16..64 && value.all { it.isLetterOrDigit() && it.code < 128 }

    /** Avoid cutting a word in half unless the first word itself exceeds cap. */
    fun clipped(text: String, cap: Int): String {
        if (cap <= 0) return ""
        val normalized = text.trim()
        if (normalized.length <= cap) return normalized
        val slice = normalized.take(cap)
        val lastSpace = slice.indexOfLast { it.isWhitespace() }
        return if (lastSpace > 0) slice.substring(0, lastSpace).trimEnd() else slice
    }

    /** Use subtraction rather than addition to avoid Int overflow. */
    fun allowed(used: Int, requested: Int, dailyCap: Int): Boolean =
        used >= 0 && requested > 0 && dailyCap > 0 && requested <= dailyCap && used <= dailyCap - requested
}