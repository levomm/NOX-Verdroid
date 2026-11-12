package com.verdroid.app.data.voice

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ElevenLabsTtsRequestTest {
    @Test fun requestUsesOfficialStreamingEndpointAndEstonianTurboModel() {
        val req = ElevenLabsTtsRequest.make("Tere maailm", ElevenLabsTtsPolicy.DEFAULT_VOICE_ID, "dummy-test-key")
        assertEquals("https", req.url.scheme)
        assertEquals("api.elevenlabs.io", req.url.host)
        assertEquals("pcm_24000", req.url.queryParameter("output_format"))
        assertEquals("dummy-test-key", req.header("xi-api-key"))
        val bytes = Buffer()
        req.body!!.writeTo(bytes)
        val data = bytes.readUtf8()
        assertTrue(data.contains("\"model_id\":\"eleven_v4_turbo\""))
        assertTrue(data.contains("\"language_code\":\"et\""))
        assertTrue(data.contains("\"Tere maailm\""))
    }
    @Test(expected = IllegalArgumentException::class)
    fun unsafeVoiceIdCannotInjectUrlSegments() {
        ElevenLabsTtsRequest.make("x", "../secrets", "dummy-test-key")
    }
}
