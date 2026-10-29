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
            ByteBuffer.allocateDirect(Short.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { putShort(1000); flip() }
        )
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)

        assertEquals((1000 * 10.0.pow(0.3)).toInt().toShort(), output.getShort())
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
