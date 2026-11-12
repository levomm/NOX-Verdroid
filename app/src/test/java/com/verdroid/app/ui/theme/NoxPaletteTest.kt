package com.verdroid.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoxPaletteTest {
    @Test fun terminalPaletteUsesGreenBackgroundAndForeground() {
        val s = Palettes.scheme(Palette.NOX, true)
        assertEquals(Color(0xFF04180A), s.background)
        assertEquals(Color(0xFFACFFC2), s.onBackground)
        assertEquals(Color(0xFF65F58F), s.primary)
        assertEquals("NOX Terminal", Palette.NOX.label)
        assertTrue(s.surface != s.onSurface)
    }
}
