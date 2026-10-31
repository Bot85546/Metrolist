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
    fun `relative loudness requires a calibrated client reference`() {
        assertNull(normalizationGainMb(-1.94, null, -14f))
        assertEquals(-506, normalizationGainMb(-1.94, null, -14f, -7.0))
    }

    @Test
    fun `different YouTube client references give the same absolute loudness`() {
        val absolute = -12.44
        assertEquals(
            normalizationGainMb(null, absolute, -14f),
            normalizationGainMb(-5.44, null, -14f, -7.0),
        )
        assertEquals(
            normalizationGainMb(null, absolute, -14f),
            normalizationGainMb(1.56, null, -14f, -14.0),
        )
    }

    @Test
    fun `gain is clamped to processor limits`() {
        assertEquals(1200, normalizationGainMb(-30.0, null, -14f, -7.0))
        assertEquals(-2400, normalizationGainMb(null, 10.0, -14f))
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
            normalizationGainMb(relative, null, -14f, -7.0),
        )
    }

    @Test
    fun `balanced loudness highlights the cap on quiet recordings`() {
        assertEquals(-500, normalizationGainMb(null, -9.0, -14f))
        assertEquals(1000, normalizationGainMb(null, -24.0, -14f))
    }

    @Test
    fun `gain conversion rounds floating point metadata consistently`() {
        assertEquals(-156, normalizationGainMb(null, -12.44, -14f))
        assertEquals(-156, normalizationGainMb(-5.4399996, null, -14f, -7.0))
        for (hundredths in -3000..0) {
            val measured = hundredths / 100.0
            assertEquals(
                normalizationGainMb(null, measured, -14f),
                normalizationGainMb(measured + 7.0, null, -14f, -7.0),
            )
        }
    }

    @Test
    fun `invalid metadata falls back without corrupting gain`() {
        assertEquals(-700, normalizationGainMb(0.0, Double.NaN, -14f, -7.0))
        assertNull(normalizationGainMb(Double.POSITIVE_INFINITY, Double.NaN, -14f))
        assertNull(normalizationGainMb(null, -10.0, Float.NaN))
        assertEquals(-3000, normalizationGainMb(null, Double.MAX_VALUE, -14f))
        assertEquals(1200, normalizationGainMb(null, -Double.MAX_VALUE, -14f))
    }

    @Test
    fun `unanchored relative metadata never invents an absolute LUFS measurement`() {
        assertNull(normalizationGainMb(0.0, null, -14f))
        assertNull(normalizationGainMb(10.0, Double.NaN, -14f))
        assertNull(normalizationGainMb(0.0, null, -14f, Double.NaN))
    }

    @Test
    fun `unknown songs use a conservative non boosting fallback at every level`() {
        assertEquals(-700, fallbackNormalizationGainMb(-14f))
        assertEquals(-1200, fallbackNormalizationGainMb(-19f))
        assertEquals(-400, fallbackNormalizationGainMb(-11f))
        assertEquals(0, fallbackNormalizationGainMb(-7f))
        assertEquals(-700, fallbackNormalizationGainMb(Float.NaN))
    }

}
