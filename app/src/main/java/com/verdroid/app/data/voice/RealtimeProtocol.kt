package com.verdroid.app.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/** OpenAI Realtime API GA events; pure logic so wire changes can be regression-tested. */
sealed interface RealtimeEvent {
    data object SessionCreated : RealtimeEvent
    data object SessionReady : RealtimeEvent
    data object SpeechStarted : RealtimeEvent
    data object ResponseStarted : RealtimeEvent
    data object ResponseDone : RealtimeEvent
    data class Audio(val pcm16: ByteArray) : RealtimeEvent
    data class Transcript(val delta: String) : RealtimeEvent
    data class Error(val message: String) : RealtimeEvent
    data object Other : RealtimeEvent
}

object RealtimeProtocol {
    const val SAMPLE_RATE = 24_000
    const val DEFAULT_MODEL = "gpt-realtime-2.1-mini"
    val MODELS = listOf(DEFAULT_MODEL, "gpt-realtime-2.1")
    private val json = Json { ignoreUnknownKeys = true }

    fun sessionUpdate(model: String, voice: String = "marin"): String {
        require(model in MODELS) { "Unsupported Realtime model" }
        require(voice in setOf("marin", "cedar")) { "Unsupported voice" }
        return buildJsonObject {
            put("type", "session.update")
            put("session", buildJsonObject {
                put("type", "realtime")
                put("model", model)
                put("output_modalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("audio")) })
                put("audio", buildJsonObject {
                    put("input", buildJsonObject {
                        put("format", buildJsonObject {
                            put("type", "audio/pcm"); put("rate", SAMPLE_RATE)
                        })
                        put("turn_detection", buildJsonObject {
                            put("type", "semantic_vad")
                        })
                    })
                    put("output", buildJsonObject {
                        put("format", buildJsonObject {
                            put("type", "audio/pcm"); put("rate", SAMPLE_RATE)
                        })
                        put("voice", voice)
                    })
                })
                put("instructions", "Speak primarily Estonian. Be concise, technically precise, with dry sarcasm when appropriate. You are NOX, an AI voice assistant. This voice session has no device, shell or browser tools; never claim to have performed external actions.")
            })
        }.toString()
    }

    fun appendAudio(pcm16: ByteArray): String = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", Base64.getEncoder().encodeToString(pcm16))
    }.toString()

    fun cancelResponse(): String = """{"type":"response.cancel"}"""

    fun parse(raw: String): RealtimeEvent {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return RealtimeEvent.Error("Malformed Realtime API event")
        return when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "session.created" -> RealtimeEvent.SessionCreated
            "session.updated" -> RealtimeEvent.SessionReady
            "input_audio_buffer.speech_started" -> RealtimeEvent.SpeechStarted
            "response.created" -> RealtimeEvent.ResponseStarted
            "response.done" -> RealtimeEvent.ResponseDone
            "response.output_audio_transcript.delta" ->
                RealtimeEvent.Transcript(root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "response.output_audio.delta" -> {
                val bytes = runCatching {
                    Base64.getDecoder().decode(root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }.getOrElse { return RealtimeEvent.Error("Malformed audio stream") }
                RealtimeEvent.Audio(bytes)
            }
            "error" -> RealtimeEvent.Error(
                (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "Unknown Realtime API error"
            )
            else -> RealtimeEvent.Other
        }
    }
}
