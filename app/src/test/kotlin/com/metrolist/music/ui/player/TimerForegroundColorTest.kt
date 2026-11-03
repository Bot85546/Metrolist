package com.metrolist.music.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerForegroundColorTest {
    @Test
    fun `foreground stays neutral and contrasts with dark glass over different artwork`() {
        val backdrops = listOf(Color.Black, Color.White, Color.Red, Color.Blue, Color(0xFF800080))
        for (backdrop in backdrops) {
            val foreground = timerForegroundColor(backdrop)
            val glass = Color(0xC0202229).compositeOver(backdrop)
            val contrast = (foreground.luminance() + 0.05f) / (glass.luminance() + 0.05f)
            assertTrue("Insufficient contrast for $backdrop: $contrast", contrast >= 4.5f)
            assertTrue(foreground.red == foreground.green && foreground.green == foreground.blue)
        }
        assertTrue(timerForegroundColor(Color.White).luminance() > timerForegroundColor(Color.Black).luminance())
    }
}
