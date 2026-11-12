package com.verdroid.app.data.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ElevenLabsVoicePrefsTest {
    @Test fun providerDefaultsToElevenLabsButDoesNotEnableSpeak() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = VoicePrefs(context)
        assertEquals("elevenlabs", prefs.ttsProvider.value)
        assertFalse(prefs.speakReplies.value)
        assertEquals(ElevenLabsTtsPolicy.DEFAULT_VOICE_ID, prefs.elevenLabsVoiceId.value)
    }

    @Test fun prepaidCharacterReservationEnforcesDailyCap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = VoicePrefs(context)
        prefs.setDailyCharCap(1000)
        assertTrue(prefs.reserveElevenLabsChars(1000))
        assertFalse(prefs.reserveElevenLabsChars(1))
        assertEquals(1000, prefs.usedElevenLabsCharsToday())
    }

    @Test fun invalidVoiceIdDoesNotPersist() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = VoicePrefs(context)
        prefs.setElevenLabsVoiceId("../evil")
        assertEquals(ElevenLabsTtsPolicy.DEFAULT_VOICE_ID, prefs.elevenLabsVoiceId.value)
    }
}
