package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizationGainTest {
    @Test
    fun `perceptual loudness takes priority`() {
        assertEquals(-506, normalizationGainMb(-20.0, -8.94, -14f))
    }

    @Test
    fun `legacy loudness uses YouTube reference level`() {
        assertEquals(-506, normalizationGainMb(-1.94, null, -14f))
    }

    @Test
    fun `gain is clamped to processor limits`() {
        assertEquals(600, normalizationGainMb(-30.0, null, -14f))
        assertEquals(-1500, normalizationGainMb(null, 10.0, -14f))
    }

    @Test
    fun `quiet songs can gain more than three decibels`() {
        assertEquals(400, normalizationGainMb(null, -18.0, -14f))
        assertEquals(600, normalizationGainMb(null, -20.0, -14f))
    }

    @Test
    fun `missing loudness disables normalization`() {
        assertNull(normalizationGainMb(null, null, -14f))
    }
}
