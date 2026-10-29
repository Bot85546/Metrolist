package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var sampleRate = 0
    private var channelCount = 0

    @Volatile
    var enabled = false
        set(value) {
            if (field != value) {
                field = value
                Timber.tag(TAG).d("Normalization processor enabled: $value")
            }
        }

    private data class GainState(
        val targetGainMb: Int,
        val linearGain: Double,
        val smooth: Boolean,
    )

    // Control-thread writes are read once per PCM buffer. The ramp state is
    // exclusively owned by the audio thread: no locks or timers on that path.
    @Volatile
    private var currentGain = GainState(0, 1.0, false)
    private var appliedGainState: GainState? = null
    private var appliedLinearGain = 1.0
    private var rampRemainingFrames = 0
    private var rampStepPerFrame = 0.0
    private var hasProcessedAudio = false

    /**
     * A prepared track must use its correct gain from the first sample
     * (smooth=false). Only corrections made to audible playback request a
     * short fade (smooth=true). No audio is buffered or delayed.
     */
    @Synchronized
    fun setTargetGain(gainMb: Int, smooth: Boolean = false) {
        val previous = currentGain
        if (previous.targetGainMb != gainMb || previous.smooth != smooth) {
            val linearGain = 10.0.pow(gainMb / 2000.0)
            currentGain = GainState(gainMb, linearGain, smooth)
            Timber.tag(TAG).d("Target gain set to $gainMb mB (Linear multiplier: $linearGain, smooth=$smooth)")
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        bytesPerSample =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }

        Timber.tag(TAG).d(
            "Configured: sampleRate=$sampleRate, channels=$channelCount, encoding=$encoding",
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val requested = currentGain
        if (!enabled) {
            // Preserve the bit-exact pass-through path when disabled.
            appliedGainState = null
            appliedLinearGain = 1.0
            rampRemainingFrames = 0
            hasProcessedAudio = true
            outputUnmodified(inputBuffer)
            return
        }

        if (requested !== appliedGainState) {
            if (requested.smooth &&
                hasProcessedAudio &&
                sampleRate > 0 &&
                channelCount > 0 &&
                abs(requested.linearGain - appliedLinearGain) > 1e-9
            ) {
                rampRemainingFrames = (sampleRate * RAMP_DURATION_MS / 1000).coerceAtLeast(1)
                rampStepPerFrame = (requested.linearGain - appliedLinearGain) / rampRemainingFrames
            } else {
                // Track changes and the first audio buffer never fade in from
                // the previous song's gain.
                appliedLinearGain = requested.linearGain
                rampRemainingFrames = 0
            }
            appliedGainState = requested
        }
        hasProcessedAudio = true

        if (rampRemainingFrames == 0 && requested.targetGainMb == 0) {
            outputUnmodified(inputBuffer)
            return
        }

        val sampleCount = inputBuffer.remaining() / bytesPerSample
        val output = replaceOutputBuffer(sampleCount * bytesPerSample)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        // The ordinary case uses one constant gain for the whole buffer.
        // Only the first 50 ms after a late correction takes the per-frame
        // path. All channels in one audio frame use the same multiplier.
        val rampFrames = if (channelCount > 0) min(rampRemainingFrames, sampleCount / channelCount) else 0
        repeat(rampFrames) {
            appliedLinearGain += rampStepPerFrame
            rampRemainingFrames--
            if (rampRemainingFrames == 0) {
                appliedLinearGain = requested.linearGain
            }
            writeProcessedSamples(inputBuffer, output, channelCount, appliedLinearGain)
        }
        writeProcessedSamples(inputBuffer, output, sampleCount - rampFrames * channelCount, appliedLinearGain)
        output.flip()
    }

    private fun outputUnmodified(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        output.put(inputBuffer)
        output.flip()
    }

    private fun writeProcessedSamples(
        inputBuffer: ByteBuffer,
        output: ByteBuffer,
        sampleCount: Int,
        linearGain: Double,
    ) {
        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                repeat(sampleCount) {
                    val sample = inputBuffer.getShort()
                    output.putShort(
                        (sample * linearGain)
                            .coerceIn(-32768.0, 32767.0)
                            .toInt()
                            .toShort(),
                    )
                }

            C.ENCODING_PCM_24BIT ->
                repeat(sampleCount) {
                    val b0 = inputBuffer.get().toInt() and 0xFF
                    val b1 = inputBuffer.get().toInt() and 0xFF
                    val b2 = inputBuffer.get().toInt()
                    val sample = (b2 shl 16) or (b1 shl 8) or b0
                    val processed =
                        (sample * linearGain)
                            .coerceIn(-8388608.0, 8388607.0)
                            .toInt()
                    output.put((processed and 0xFF).toByte())
                    output.put(((processed shr 8) and 0xFF).toByte())
                    output.put(((processed shr 16) and 0xFF).toByte())
                }

            C.ENCODING_PCM_32BIT ->
                repeat(sampleCount) {
                    val sample = inputBuffer.getInt()
                    output.putInt(
                        (sample * linearGain)
                            .coerceIn(-2147483648.0, 2147483647.0)
                            .toLong()
                            .toInt(),
                    )
                }

            C.ENCODING_PCM_FLOAT -> {
                val floatGain = linearGain.toFloat()
                repeat(sampleCount) {
                    val sample = inputBuffer.getFloat()
                    output.putFloat((sample * floatGain).coerceIn(-1.0f, 1.0f))
                }
            }
        }
    }

    override fun onFlush() {
        // Seek/reconfiguration/new track: never continue the prior ramp.
        appliedLinearGain = if (enabled) currentGain.linearGain else 1.0
        appliedGainState = if (enabled) currentGain else null
        rampRemainingFrames = 0
        rampStepPerFrame = 0.0
        hasProcessedAudio = false
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        sampleRate = 0
        channelCount = 0
        appliedGainState = null
        appliedLinearGain = 1.0
        rampRemainingFrames = 0
        rampStepPerFrame = 0.0
        hasProcessedAudio = false
    }

    private companion object {
        const val TAG = "VolumeNormalizationProcessor"
        const val RAMP_DURATION_MS = 50
    }
}
