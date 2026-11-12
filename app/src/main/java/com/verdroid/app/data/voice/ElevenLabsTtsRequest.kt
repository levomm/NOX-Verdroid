package com.verdroid.app.data.voice

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Strict ElevenLabs HTTP streaming request. No prompt, key or audio is ever logged. */
object ElevenLabsTtsRequest {
    fun make(text: String, voiceId: String, apiKey: String): Request {
        require(ElevenLabsTtsPolicy.validVoiceId(voiceId)) { "Invalid ElevenLabs voice ID" }
        require(apiKey.isNotBlank()) { "ElevenLabs API key required" }
        require(text.isNotBlank()) { "Nothing to speak" }
        val payload = buildJsonObject {
            put("text", text)
            put("model_id", ElevenLabsTtsPolicy.DEFAULT_MODEL)
            put("language_code", "et")
        }.toString()
        return Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?output_format=pcm_24000")
            .header("xi-api-key", apiKey)
            .header("Accept", "application/octet-stream")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
    }
}
