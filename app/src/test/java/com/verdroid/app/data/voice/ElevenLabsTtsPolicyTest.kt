package com.verdroid.app.data.voice

import org.junit.Assert.*
import org.junit.Test

class ElevenLabsTtsPolicyTest {
    @Test fun shortReplyStaysUntouched() {
        assertEquals("Tere maailm", ElevenLabsTtsPolicy.clipped("  Tere maailm  ", 1200))
    }
    @Test fun clippingPrefersWordBoundary() {
        assertEquals("Tere", ElevenLabsTtsPolicy.clipped("Tere maailm", 9))
    }
    @Test fun veryLongWordStillHonoursCap() {
        assertEquals("supercal", ElevenLabsTtsPolicy.clipped("supercalifragilistic", 8))
    }
    @Test fun emptyTextIsRejected() {
        assertEquals("", ElevenLabsTtsPolicy.clipped("  ", 30))
    }
    @Test fun voiceIdRejectsUrlAndWhitespaceInjection() {
        assertTrue(ElevenLabsTtsPolicy.validVoiceId(ElevenLabsTtsPolicy.DEFAULT_VOICE_ID))
        assertFalse(ElevenLabsTtsPolicy.validVoiceId("../voices"))
        assertFalse(ElevenLabsTtsPolicy.validVoiceId("evil?text=secret"))
        assertFalse(ElevenLabsTtsPolicy.validVoiceId(""))
    }
    @Test fun exactDailyBudgetAllowedButOneOverBlocked() {
        assertTrue(ElevenLabsTtsPolicy.allowed(900, 100, 1000))
        assertFalse(ElevenLabsTtsPolicy.allowed(901, 100, 1000))
        assertFalse(ElevenLabsTtsPolicy.allowed(0, -1, 1000))
        assertFalse(ElevenLabsTtsPolicy.allowed(Int.MAX_VALUE, 100, Int.MAX_VALUE))
    }
    @Test fun defaultsAreConservativeAndUseEstonianCapableModel() {
        assertEquals("eleven_v4_turbo", ElevenLabsTtsPolicy.DEFAULT_MODEL)
        assertEquals(1200, ElevenLabsTtsPolicy.REPLY_CHAR_CAP)
        assertEquals(8000, ElevenLabsTtsPolicy.DAILY_CHAR_CAP)
    }
}