package com.verdroid.app.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import java.util.Base64

/** Eleven v4 Turbo supports TTD WebSocket, NOT /text-to-speech/{voice}/stream. */
data class ElevenLabsAudioFrame(
    val audio: ByteArray? = null,
    val isFinal: Boolean = false,
    val isError: Boolean = false,
)

object ElevenLabsTtsRequest {
    private val json = Json { ignoreUnknownKeys = true }

    fun make(voiceId: String, apiKey: String): Request {
        require(ElevenLabsTtsPolicy.validVoiceId(voiceId)) { "Invalid ElevenLabs voice ID" }
        require(apiKey.isNotBlank()) { "ElevenLabs API key required" }
        // OkHttp newWebSocket performs an RFC 6455 upgrade for an HTTPS Request.
        return Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-dialogue/stream-input" +
                "?model_id=${ElevenLabsTtsPolicy.DEFAULT_MODEL}&output_format=pcm_24000&language_code=et")
            .header("xi-api-key", apiKey)
            .build()
    }

    /** First message of each connection registers exactly one voice for v4 turbo. */
    fun register(voiceId: String): String {
        require(ElevenLabsTtsPolicy.validVoiceId(voiceId))
        return buildJsonObject {
            put("voices", buildJsonArray { add(JsonPrimitive(voiceId)) })
        }.toString()
    }

    fun input(text: String, voiceId: String): String {
        require(ElevenLabsTtsPolicy.validVoiceId(voiceId))
        require(text.isNotBlank())
        return buildJsonObject {
            put("inputs", buildJsonArray {
                add(buildJsonObject {
                    put("text", text)
                    put("voice_id", voiceId)
                    put("new_turn", false)
                })
            })
        }.toString()
    }

    /** Flush short inputs, receive all audio and an is_final frame, then close. */
    fun end(): String = """{"close_socket":true}"""

    fun parse(raw: String): ElevenLabsAudioFrame {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return ElevenLabsAudioFrame(isError = true)
        val audio = obj["audio"]?.jsonPrimitive?.contentOrNull?.let { data ->
            runCatching { Base64.getDecoder().decode(data) }.getOrNull()
                ?: return ElevenLabsAudioFrame(isError = true)
        }
        return ElevenLabsAudioFrame(
            audio = audio,
            isFinal = obj["is_final"]?.jsonPrimitive?.booleanOrNull == true,
            isError = obj.containsKey("error"),
        )
    }
}
