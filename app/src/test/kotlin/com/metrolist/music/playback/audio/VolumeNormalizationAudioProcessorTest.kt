package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeNormalizationAudioProcessorTest {
    @Test
    fun `disabled normalization passes PCM through bit for bit`() {
        val processor = configuredProcessor()
        processor.setTargetGain(600)
        processor.enabled = false

        val original = sampleBuffer()
        val bytes = original.duplicate().let { copy ->
            ByteArray(copy.remaining()).also(copy::get)
        }
        processor.queueInput(original)
        val output = processor.output

        assertEquals(ByteOrder.LITTLE_ENDIAN, output.order())
        assertArrayEquals(bytes, ByteArray(output.remaining()).also(output::get))
    }

    @Test
    fun `zero gain passes PCM through bit for bit while enabled`() {
        val processor = configuredProcessor()
        processor.setTargetGain(0)
        processor.enabled = true

        val original = sampleBuffer()
        val bytes = original.duplicate().let { copy ->
            ByteArray(copy.remaining()).also(copy::get)
        }
        processor.queueInput(original)

        val output = processor.output
        assertArrayEquals(bytes, ByteArray(output.remaining()).also(output::get))
    }

    @Test
    fun `positive gain still uses the existing PCM multiplication`() {
        val processor = configuredProcessor()
        processor.setTargetGain(600)
        processor.enabled = true

        processor.queueInput(
            ByteBuffer.allocateDirect(2 * Short.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { putShort(1000); putShort(1000); flip() }
        )
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)

        assertEquals((1000 * 10.0.pow(0.3)).toInt().toShort(), output.getShort())
    }


    @Test
    fun `a mid stream gain reduction starts smoothly and completes in 60 milliseconds`() {
        val processor = configuredProcessor()
        processor.enabled = true
        processor.setTargetGain(0)
        processor.queueInput(pcm16Buffer(frames = 4))
        processor.output // Consume the initial, unmodified PCM buffer.

        processor.setTargetGain(-600)
        processor.queueInput(pcm16Buffer(frames = 1))
        val firstFrame = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4, firstFrame.remaining())
        val firstLeft = firstFrame.getShort().toInt()
        val firstRight = firstFrame.getShort().toInt()
        assertEquals(firstLeft, firstRight) // Both channels receive the same gain.
        org.junit.Assert.assertTrue(firstLeft in 9_900..10_000)

        // Two successive 30 ms buffers complete the same 60 ms ramp.
        processor.queueInput(pcm16Buffer(frames = 1_440))
        val halfway = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val nearHalfway = halfway.getShort(halfway.limit() - 4).toInt()
        org.junit.Assert.assertTrue(nearHalfway in 7_400..7_600)

        processor.queueInput(pcm16Buffer(frames = 1_440))
        val finished = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val nearTarget = finished.getShort(finished.limit() - 4).toInt()
        org.junit.Assert.assertTrue(nearTarget in 5_000..5_030)

        processor.queueInput(pcm16Buffer(frames = 1))
        assertEquals(nearTarget, processor.output.order(ByteOrder.LITTLE_ENDIAN).getShort().toInt())
    }

    @Test
    fun `first audio buffer always starts at the prepared target without fade in`() {
        val processor = configuredProcessor()
        processor.setTargetGain(-600)
        processor.enabled = true

        processor.queueInput(pcm16Buffer(frames = 1))
        val first = processor.output.order(ByteOrder.LITTLE_ENDIAN).getShort().toInt()
        org.junit.Assert.assertTrue(first in 5_000..5_030)

        // A seek/flush starts with the requested gain immediately as well.
        processor.setTargetGain(600)
        processor.flush()
        processor.queueInput(pcm16Buffer(frames = 1))
        val afterFlush = processor.output.order(ByteOrder.LITTLE_ENDIAN).getShort().toInt()
        org.junit.Assert.assertTrue(afterFlush in 19_900..20_000)
    }

    @Test
    fun `disabling normalization ramps back to unity and then bypasses unchanged bytes`() {
        val processor = configuredProcessor()
        processor.setTargetGain(-600)
        processor.enabled = true
        processor.queueInput(pcm16Buffer(frames = 1))
        processor.output

        processor.enabled = false
        processor.queueInput(pcm16Buffer(frames = 1))
        val firstFrame = processor.output.order(ByteOrder.LITTLE_ENDIAN).getShort().toInt()
        org.junit.Assert.assertTrue(firstFrame in 5_000..5_100)

        processor.queueInput(pcm16Buffer(frames = 2_880))
        processor.output
        val original = pcm16Buffer(frames = 10)
        val expected = ByteArray(original.remaining()).also(original.duplicate()::get)
        processor.queueInput(original)
        val output = processor.output
        assertArrayEquals(expected, ByteArray(output.remaining()).also(output::get))
    }

    @Test
    fun `a new gain request during a ramp begins at the current gain`() {
        val processor = configuredProcessor()
        processor.enabled = true
        processor.setTargetGain(0)
        processor.queueInput(pcm16Buffer(frames = 1))
        processor.output

        processor.setTargetGain(-600)
        processor.queueInput(pcm16Buffer(frames = 1_440))
        val halfway = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val previous = halfway.getShort(halfway.limit() - 4).toInt()

        processor.setTargetGain(0)
        processor.queueInput(pcm16Buffer(frames = 1))
        val next = processor.output.order(ByteOrder.LITTLE_ENDIAN).getShort().toInt()
        org.junit.Assert.assertTrue(kotlin.math.abs(next - previous) <= 2)
    }

    @Test
    fun `gapless track transition snaps immediately even when prior gain was ramping`() {
        val processor = configuredProcessor()
        processor.enabled = true
        processor.setTargetGain(0)
        processor.queueInput(pcm16Buffer(frames = 1))
        processor.output

        // Simulate an ordinary correction still fading when the user skips.
        processor.setTargetGain(-600)
        processor.queueInput(pcm16Buffer(frames = 500))
        processor.output

        // A selected track must use its prepared gain from its first frame.
        processor.setTargetGain(600, immediate = true)
        // Simulate a redundant play/resume callback: it must not cancel the
        // pending immediate gain before the audio thread sees the new track.
        processor.setTargetGain(600)
        processor.queueInput(pcm16Buffer(frames = 1))
        val nextTrack = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val left = nextTrack.getShort().toInt()
        val right = nextTrack.getShort().toInt()
        assertEquals(left, right)
        org.junit.Assert.assertTrue(left in 19_900..20_000)
    }

    @Test
    fun `a user volume setting change still ramps while playback continues`() {
        val processor = configuredProcessor()
        processor.enabled = true
        processor.setTargetGain(-600, immediate = true)
        processor.queueInput(pcm16Buffer(frames = 1))
        processor.output

        // Deliberately omit immediate=true: this is not a track transition.
        processor.setTargetGain(0)
        processor.queueInput(pcm16Buffer(frames = 1))
        val first = processor.output.order(ByteOrder.LITTLE_ENDIAN).getShort().toInt()
        org.junit.Assert.assertTrue(first in 5_000..5_100)

        processor.queueInput(pcm16Buffer(frames = 2_880))
        val finished = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val last = finished.getShort(finished.limit() - 4).toInt()
        assertEquals(10_000, last)
    }

    @Test
    fun `float PCM remains frame aligned and introduces no buffering during a ramp`() {
        val processor = VolumeNormalizationAudioProcessor().apply {
            configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
            flush()
            enabled = true
            setTargetGain(0)
        }

        fun stereoFrames(count: Int) =
            ByteBuffer.allocateDirect(count * 2 * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    repeat(count * 2) { putFloat(0.5f) }
                    flip()
                }

        processor.queueInput(stereoFrames(1))
        val original = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8, original.remaining())
        assertEquals(0.5f, original.getFloat(), 0.00001f)
        assertEquals(0.5f, original.getFloat(), 0.00001f)

        processor.setTargetGain(-600)
        processor.queueInput(stereoFrames(2_880))
        val ramped = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2_880 * 2 * Float.SIZE_BYTES, ramped.remaining())
        val lastLeft = ramped.getFloat(ramped.limit() - 8)
        val lastRight = ramped.getFloat(ramped.limit() - 4)
        assertEquals(lastLeft, lastRight, 0.00001f)
        assertEquals((0.5 * 10.0.pow(-0.3)).toFloat(), lastLeft, 0.00001f)
    }

    @Test
    fun `boosted peaks are limited before output and both channels keep their ratio`() {
        val processor = configuredProcessor().apply {
            enabled = true
            setTargetGain(1200)
        }
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(30_000.toShort())
            putShort(15_000.toShort())
            flip()
        }
        processor.queueInput(input)
        assertEquals(0, input.remaining())
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4, output.remaining()) // No held frames or lookahead delay.
        val left = output.getShort().toInt()
        val right = output.getShort().toInt()
        org.junit.Assert.assertTrue(left in 29_190..29_205)
        org.junit.Assert.assertTrue(kotlin.math.abs(left - 2 * right) <= 1)
    }

    @Test
    fun `peak limiting releases across buffers and resets on a new track`() {
        val processor = configuredProcessor().apply {
            enabled = true
            setTargetGain(1200)
        }
        processor.queueInput(pcm16Buffer(1))
        processor.output
        val quiet = pcm16Buffer(4_800).apply {
            clear()
            repeat(9_600) { putShort(1000) }
            flip()
        }
        processor.queueInput(quiet)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val first = output.getShort().toInt()
        val last = output.getShort(output.limit() - 4).toInt()
        org.junit.Assert.assertTrue(first < last)
        assertEquals((1000 * 10.0.pow(0.6)).toInt(), last)
        processor.setTargetGain(-600, immediate = true)
        processor.queueInput(pcm16Buffer(1))
        org.junit.Assert.assertTrue(processor.output.order(ByteOrder.LITTLE_ENDIAN).getShort().toInt() in 5_000..5_030)
    }

    @Test
    fun `peak protection covers every supported PCM encoding and polarity`() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            val size = if (encoding == C.ENCODING_PCM_16BIT) 2 else if (encoding == C.ENCODING_PCM_24BIT) 3 else 4
            val processor = VolumeNormalizationAudioProcessor().apply {
                configure(AudioProcessor.AudioFormat(48_000, 2, encoding))
                flush()
                enabled = true
                setTargetGain(1200)
            }
            val input = ByteBuffer.allocateDirect(2 * size).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (sign in listOf(1, -1)) {
                    when (encoding) {
                        C.ENCODING_PCM_16BIT -> putShort((sign * 32767).toShort())
                        C.ENCODING_PCM_24BIT -> {
                            val sample = sign * 8388607
                            put(sample.toByte()); put((sample shr 8).toByte()); put((sample shr 16).toByte())
                        }
                        C.ENCODING_PCM_32BIT -> putInt(sign * Int.MAX_VALUE)
                        C.ENCODING_PCM_FLOAT -> putFloat(sign.toFloat())
                    }
                }
                flip()
            }
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2 * size, output.remaining())
            repeat(2) {
                val sample = when (encoding) {
                    C.ENCODING_PCM_16BIT -> output.getShort() / 32768.0
                    C.ENCODING_PCM_24BIT -> {
                        val value = (output.get().toInt() and 255) or ((output.get().toInt() and 255) shl 8) or (output.get().toInt() shl 16)
                        value / 8388608.0
                    }
                    C.ENCODING_PCM_32BIT -> output.getInt() / 2147483648.0
                    else -> output.getFloat().toDouble()
                }
                org.junit.Assert.assertTrue(kotlin.math.abs(sample) <= 0.891251)
                org.junit.Assert.assertTrue(kotlin.math.abs(sample) >= 0.8911)
            }
        }
    }

    @Test
    fun `a new track with the same target does not inherit peak reduction`() {
        val processor = configuredProcessor().apply { enabled = true; setTargetGain(1200, immediate = true) }
        processor.queueInput(pcm16Buffer(1))
        processor.output
        processor.setTargetGain(1200, immediate = true)
        processor.setTargetGain(1200) // Redundant callback must preserve the reset.
        processor.queueInput(ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1000); putShort(1000); flip()
        })
        assertEquals(3981, processor.output.order(ByteOrder.LITTLE_ENDIAN).getShort().toInt())
    }

    @Test
    fun `sustained processing reuses output storage and stays frame aligned`() {
        for (gain in listOf(-700, 1200)) {
            val processor = configuredProcessor().apply { enabled = true; setTargetGain(gain) }
            val input = pcm16Buffer(1_024)
            processor.queueInput(input)
            val storage = processor.output
            val started = System.nanoTime()
            repeat(4_000) {
                input.rewind()
                processor.queueInput(input)
                val output = processor.output
                org.junit.Assert.assertSame(storage, output)
                assertEquals(4_096, output.remaining())
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            println("Normalization benchmark: gain=$gain, audioMs=85333, elapsedMs=$elapsedMs")
        }
    }

    private fun pcm16Buffer(frames: Int): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 2 * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                repeat(frames * 2) { putShort(10_000.toShort()) }
                flip()
            }

    private fun configuredProcessor() = VolumeNormalizationAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        flush()
    }

    private fun sampleBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                shortArrayOf(0, 1, -1, 10_000, -10_000, Short.MIN_VALUE)
                    .forEach { putShort(it) }
                flip()
            }
}
