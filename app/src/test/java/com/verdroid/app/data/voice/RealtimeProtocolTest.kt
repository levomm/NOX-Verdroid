package com.verdroid.app.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RealtimeProtocolTest {
    @Test fun sessionUpdateUsesGaAudioShape() {
        val root = Json.parseToJsonElement(RealtimeProtocol.sessionUpdate("gpt-realtime-2.1-mini", "marin")).jsonObject
        assertEquals("session.update", root["type"]?.jsonPrimitive?.content)
        val session = root["session"]!!.jsonObject
        assertEquals("realtime", session["type"]!!.jsonPrimitive.content)
        assertEquals(null, session["model"])
        val audio = session["audio"]!!.jsonObject
        assertEquals("audio/pcm", audio["input"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("24000", audio["input"]!!.jsonObject["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content)
        assertEquals("semantic_vad", audio["input"]!!.jsonObject["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("marin", audio["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertEquals("audio", session["output_modalities"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test fun audioAppendUsesPcmBase64() {
        val bytes = byteArrayOf(0, 1, -1, 127)
        val root = Json.parseToJsonElement(RealtimeProtocol.appendAudio(bytes)).jsonObject
        assertEquals("input_audio_buffer.append", root["type"]?.jsonPrimitive?.content)
        assertArrayEquals(bytes, Base64.getDecoder().decode(root["audio"]!!.jsonPrimitive.content))
    }

    @Test fun decodeAudioDeltaAndError() {
        val delta = RealtimeProtocol.parse("""{"type":"response.output_audio.delta","delta":"AAE="}""")
        assertTrue(delta is RealtimeEvent.Audio)
        assertArrayEquals(byteArrayOf(0,1), (delta as RealtimeEvent.Audio).pcm16)
        val error = RealtimeProtocol.parse("""{"type":"error","error":{"message":"not available"}}""")
        assertEquals("not available", (error as RealtimeEvent.Error).message)
    }

    @Test fun parseSpeechInterruption() {
        assertEquals(RealtimeEvent.SpeechStarted, RealtimeProtocol.parse("""{"type":"input_audio_buffer.speech_started"}"""))
        assertEquals(RealtimeEvent.SessionReady, RealtimeProtocol.parse("""{"type":"session.updated"}"""))
    }

    @Test fun rejectUnsupportedModel() {
        try {
            RealtimeProtocol.sessionUpdate("gpt-6.1-sol", "marin")
            fail("unsupported voice model accepted")
        } catch (expected: IllegalArgumentException) {}
    }
}
