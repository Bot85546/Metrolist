package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackLoudnessCacheTest {
    @Test
    fun `records metadata before a song becomes active`() {
        val cache = TrackLoudnessCache()
        cache.record("next-song", loudnessDb = -1.5, perceptualLoudnessDb = -8.5)

        assertEquals(TrackLoudness(-1.5, -8.5), cache.get("next-song"))
    }

    @Test
    fun `missing metadata does not erase an earlier measurement`() {
        val cache = TrackLoudnessCache()
        cache.record("song", loudnessDb = -3.0, perceptualLoudnessDb = -10.0)
        cache.record("song", loudnessDb = null, perceptualLoudnessDb = null)

        assertEquals(TrackLoudness(-3.0, -10.0), cache.get("song"))
    }

    @Test
    fun `fresh loudness metadata replaces an older measurement`() {
        val cache = TrackLoudnessCache()
        cache.record("song", loudnessDb = 2.0, perceptualLoudnessDb = null)
        cache.record("song", loudnessDb = 1.0, perceptualLoudnessDb = -13.0)

        assertEquals(TrackLoudness(1.0, -13.0), cache.get("song"))
    }

    @Test
    fun `evicts the least recently used song`() {
        val cache = TrackLoudnessCache(capacity = 2)
        cache.record("A", -1.0, -13.0)
        cache.record("B", -2.0, -12.0)
        cache.get("A")
        cache.record("C", -3.0, -11.0)

        assertEquals(TrackLoudness(-1.0, -13.0), cache.get("A"))
        assertNull(cache.get("B"))
        assertEquals(TrackLoudness(-3.0, -11.0), cache.get("C"))
    }

    @Test
    fun `learns both seven and fourteen LUFS reference scales per extraction client`() {
        val cache = TrackLoudnessCache()
        cache.record("music-anchor", -5.44, -12.44, clientName = "MUSIC")
        cache.record("web-anchor", 1.56, -12.44, clientName = "WEB")
        cache.record("music-relative", -5.44, null, clientName = "MUSIC")
        cache.record("web-relative", 1.56, null, clientName = "WEB")

        assertEquals(-7.0, cache.referenceFor("music-relative")!!, 0.0001)
        assertEquals(-14.0, cache.referenceFor("web-relative")!!, 0.0001)
        assertNull(cache.referenceFor("music-anchor"))
        assertNull(cache.referenceFor("web-anchor"))
    }

    @Test
    fun `refuses uncalibrated and conflicting client reference scales`() {
        val cache = TrackLoudnessCache()
        cache.record("unknown", 0.5, null, clientName = "UNKNOWN")
        assertNull(cache.referenceFor("unknown"))

        cache.record("anchor1", 0.5, -13.5, clientName = "UNSTABLE")
        cache.record("relative", 0.5, null, clientName = "UNSTABLE")
        assertEquals(-14.0, cache.referenceFor("relative")!!, 0.0001)
        cache.record("anchor2", -6.5, -13.5, clientName = "UNSTABLE")
        assertNull(cache.referenceFor("relative"))
    }

    @Test
    fun `partial updates preserve the extraction client and calibrated reference`() {
        val cache = TrackLoudnessCache()
        cache.record("anchor", -6.0, -13.0, clientName = "MUSIC")
        cache.record("partial", -6.0, null, clientName = "MUSIC")
        cache.record("partial", -5.0, null)

        assertEquals(TrackLoudness(-5.0, null, "MUSIC"), cache.get("partial"))
        assertEquals(-7.0, cache.referenceFor("partial")!!, 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects zero capacity`() {
        TrackLoudnessCache(capacity = 0)
    }
    @Test
    fun `partial or invalid responses preserve absolute measurements`() {
        val cache = TrackLoudnessCache()
        cache.record("song", 1.0, -13.0)
        cache.record("song", 2.0, null)
        assertEquals(TrackLoudness(2.0, -13.0), cache.get("song"))
        cache.record("song", Double.NaN, Double.POSITIVE_INFINITY)
        assertEquals(TrackLoudness(2.0, -13.0), cache.get("song"))
        cache.record("bad", Double.NaN, null)
        assertNull(cache.get("bad"))
    }

}
