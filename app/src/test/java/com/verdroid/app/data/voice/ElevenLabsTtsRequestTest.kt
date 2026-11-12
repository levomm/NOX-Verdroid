package com.verdroid.app.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ElevenLabsTtsRequestTest {
    @Test fun usesOfficialV4TurboDialogueWebSocketAndEstonianPcm24k() {
        val req = ElevenLabsTtsRequest.make(ElevenLabsTtsPolicy.DEFAULT_VOICE_ID, "dummy-test-key")
        assertEquals("https", req.url.scheme) // OkHttp upgrades HTTPS to WSS
        assertEquals("api.elevenlabs.io", req.url.host)
        assertEquals("/v1/text-to-dialogue/stream-input", req.url.encodedPath)
        assertEquals("eleven_v4_turbo", req.url.queryParameter("model_id"))
        assertEquals("pcm_24000", req.url.queryParameter("output_format"))
        assertEquals("et", req.url.queryParameter("language_code"))
        assertEquals("dummy-test-key", req.header("xi-api-key"))
        assertNull(req.body)
    }
    @Test fun registersVoiceThenInputAndFlushesAtEnd() {
        val voice = ElevenLabsTtsPolicy.DEFAULT_VOICE_ID
        val registration = Json.parseToJsonElement(ElevenLabsTtsRequest.register(voice)).jsonObject
        assertEquals(voice, registration["voices"]!!.jsonArray[0].jsonPrimitive.content)
        val payload = Json.parseToJsonElement(ElevenLabsTtsRequest.input("Tere maailm", voice)).jsonObject
        val input = payload["inputs"]!!.jsonArray[0].jsonObject
        assertEquals("Tere maailm", input["text"]!!.jsonPrimitive.content)
        assertEquals(voice, input["voice_id"]!!.jsonPrimitive.content)
        assertTrue(ElevenLabsTtsRequest.end().contains("close_socket"))
    }
    @Test fun parsesPcmAudioAndFinalMessages() {
        val packet = ElevenLabsTtsRequest.parse("""{"audio":"AAECAw=="}""")
        assertArrayEquals(byteArrayOf(0,1,2,3), packet.audio)
        assertFalse(packet.isFinal)
        assertTrue(ElevenLabsTtsRequest.parse("""{"is_final":true}""").isFinal)
        assertTrue(ElevenLabsTtsRequest.parse("""{"error":"unauthorized"}""").isError)
    }
    @Test(expected = IllegalArgumentException::class)
    fun unsafeVoiceIdCannotInjectUrlSegments() {
        ElevenLabsTtsRequest.make("../secrets", "dummy-test-key")
    }
}
