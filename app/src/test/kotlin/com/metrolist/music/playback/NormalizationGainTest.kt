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
    @Test
    fun `normalization always disables offload so PCM gain is applied`() {
        assertEquals(false, shouldEnableAudioOffload(
            offloadPreferred = true,
            crossfadeEnabled = false,
            normalizationEnabled = true,
        ))
        assertEquals(false, shouldEnableAudioOffload(
            offloadPreferred = true,
            crossfadeEnabled = true,
            normalizationEnabled = true,
        ))
    }

    @Test
    fun `offload remains available when neither normalization nor crossfade is active`() {
        assertEquals(true, shouldEnableAudioOffload(
            offloadPreferred = true,
            crossfadeEnabled = false,
            normalizationEnabled = false,
        ))
        assertEquals(false, shouldEnableAudioOffload(
            offloadPreferred = false,
            crossfadeEnabled = false,
            normalizationEnabled = false,
        ))
        assertEquals(false, shouldEnableAudioOffload(
            offloadPreferred = true,
            crossfadeEnabled = true,
            normalizationEnabled = false,
        ))
    }

    @Test
    fun `both YouTube loudness fields give the same gain for equivalent measurements`() {
        // YouTube's loudnessDb and perceptualLoudnessDb are on different
        // reference scales; the legacy field must not shift songs by 7 dB.
        val measured = -12.44
        val relative = -5.44
        assertEquals(
            normalizationGainMb(null, measured, -14f),
            normalizationGainMb(relative, null, -14f),
        )
    }

    @Test
    fun `balanced loudness highlights the cap on quiet recordings`() {
        assertEquals(-500, normalizationGainMb(null, -9.0, -14f))
        assertEquals(600, normalizationGainMb(null, -24.0, -14f))
    }

}
