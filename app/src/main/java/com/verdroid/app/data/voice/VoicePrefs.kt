package com.verdroid.app.data.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** NOX voice preferences. Credentials stay in encrypted CustomProviderStore, never here. */
@Singleton
class VoicePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)

    private val _speak = MutableStateFlow(prefs.getBoolean(KEY_SPEAK, false))
    val speakReplies: StateFlow<Boolean> = _speak.asStateFlow()
    fun setSpeakReplies(v: Boolean) { prefs.edit().putBoolean(KEY_SPEAK, v).apply(); _speak.value = v }

    private val _autoSend = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SEND, true))
    val autoSend: StateFlow<Boolean> = _autoSend.asStateFlow()
    fun setAutoSend(v: Boolean) { prefs.edit().putBoolean(KEY_AUTO_SEND, v).apply(); _autoSend.value = v }

    private val _handsFree = MutableStateFlow(prefs.getBoolean(KEY_HANDS_FREE, false))
    val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()
    fun setHandsFree(v: Boolean) { prefs.edit().putBoolean(KEY_HANDS_FREE, v).apply(); _handsFree.value = v }

    private val _micButton = MutableStateFlow(prefs.getBoolean(KEY_MIC, true))
    val micButton: StateFlow<Boolean> = _micButton.asStateFlow()
    fun setMicButton(v: Boolean) { prefs.edit().putBoolean(KEY_MIC, v).apply(); _micButton.value = v }

    /** ElevenLabs is selected for spoken replies when BYOK is configured; Android is automatic fallback. */
    private val _ttsProvider = MutableStateFlow(prefs.getString(KEY_TTS_PROVIDER, "elevenlabs") ?: "elevenlabs")
    val ttsProvider: StateFlow<String> = _ttsProvider.asStateFlow()
    fun setTtsProvider(v: String) {
        if (v !in setOf("android", "elevenlabs")) return
        prefs.edit().putString(KEY_TTS_PROVIDER, v).apply(); _ttsProvider.value = v
    }

    private val _elevenLabsVoiceId = MutableStateFlow(
        (prefs.getString(KEY_ELEVEN_VOICE, null) ?: ElevenLabsTtsPolicy.DEFAULT_VOICE_ID)
            .takeIf(ElevenLabsTtsPolicy::validVoiceId) ?: ElevenLabsTtsPolicy.DEFAULT_VOICE_ID
    )
    val elevenLabsVoiceId: StateFlow<String> = _elevenLabsVoiceId.asStateFlow()
    fun setElevenLabsVoiceId(id: String) {
        if (!ElevenLabsTtsPolicy.validVoiceId(id)) return
        prefs.edit().putString(KEY_ELEVEN_VOICE, id).apply(); _elevenLabsVoiceId.value = id
    }

    private val _replyCharCap = MutableStateFlow(prefs.getInt(KEY_REPLY_CHAR_CAP, ElevenLabsTtsPolicy.REPLY_CHAR_CAP).coerceIn(100, 3000))
    val replyCharCap: StateFlow<Int> = _replyCharCap.asStateFlow()
    fun setReplyCharCap(value: Int) {
        val safe = value.coerceIn(100, 3000)
        prefs.edit().putInt(KEY_REPLY_CHAR_CAP, safe).apply(); _replyCharCap.value = safe
    }

    private val _dailyCharCap = MutableStateFlow(prefs.getInt(KEY_DAILY_CHAR_CAP, ElevenLabsTtsPolicy.DAILY_CHAR_CAP).coerceIn(1000, 50000))
    val dailyCharCap: StateFlow<Int> = _dailyCharCap.asStateFlow()
    fun setDailyCharCap(value: Int) {
        val safe = value.coerceIn(1000, 50000)
        prefs.edit().putInt(KEY_DAILY_CHAR_CAP, safe).apply(); _dailyCharCap.value = safe
    }

    /** Counts reservations before a remote request; failures are conservatively charged locally. */
    @Synchronized
    fun reserveElevenLabsChars(size: Int): Boolean {
        val used = usedElevenLabsCharsToday()
        if (!ElevenLabsTtsPolicy.allowed(used, size, _dailyCharCap.value)) return false
        prefs.edit().putString(KEY_CHAR_DATE, LocalDate.now().toString())
            .putInt(KEY_CHARS_USED, used + size).apply()
        return true
    }

    @Synchronized
    fun usedElevenLabsCharsToday(): Int =
        if (prefs.getString(KEY_CHAR_DATE, "") == LocalDate.now().toString())
            prefs.getInt(KEY_CHARS_USED, 0).coerceAtLeast(0) else 0

    companion object {
        const val KEY_SPEAK = "speak_replies"
        const val KEY_AUTO_SEND = "auto_send"
        const val KEY_HANDS_FREE = "hands_free"
        const val KEY_MIC = "mic_button"
        const val KEY_TTS_PROVIDER = "tts_provider"
        const val KEY_ELEVEN_VOICE = "elevenlabs_voice_id"
        const val KEY_REPLY_CHAR_CAP = "elevenlabs_max_reply_chars"
        const val KEY_DAILY_CHAR_CAP = "elevenlabs_daily_chars"
        const val KEY_CHAR_DATE = "elevenlabs_day"
        const val KEY_CHARS_USED = "elevenlabs_used"
    }
}
