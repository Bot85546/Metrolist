package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var sampleRate = 0
    private var channelCount = 0

    // This state is owned by the audio-processing thread. Changing the desired
    // gain does not allocate buffers or schedule work on another thread.
    private var hasProcessedAudio = false
    private var lastTargetGainMb = Int.MIN_VALUE
    private var lastSeenGainState: GainState? = null
    private var renderedLinearGain = 1.0
    private var rampTargetLinearGain = 1.0
    private var rampStepPerFrame = 0.0
    private var rampFramesRemaining = 0

    @Volatile
    var enabled = false
        set(value) {
            if (field != value) {
                field = value
                Timber.tag(TAG).d("Normalization processor enabled: $value")
            }
        }

    private data class GainState(val targetGainMb: Int, val linearGain: Double, val immediate: Boolean)

    @Volatile
    private var currentGain = GainState(0, 1.0, false)

    /**
     * immediate=true is for a new track's prepared gain: the first audio
     * frame must use its exact normalization, even in gapless transitions
     * where Media3 doesn't necessarily flush the PCM processor.
     * Ordinary mid-track corrections retain the 60 ms ramp.
     */
    @Synchronized
    fun setTargetGain(gainMb: Int, immediate: Boolean = false) {
        // Keep a pending immediate track-boundary request intact if another
        // callback redundantly reapplies the same gain without that flag.
        // Only a genuinely different gain can replace it with a ramp request.
        if (currentGain.targetGainMb != gainMb || (immediate && !currentGain.immediate)) {
            val linearGain = 10.0.pow(gainMb / 2000.0)
            currentGain = GainState(gainMb, linearGain, immediate)
            Timber.tag(TAG).d("Target gain set to $gainMb mB (Linear multiplier: $linearGain, immediate=$immediate)")
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
            "Configured: sampleRate=${inputAudioFormat.sampleRate}, channels=${inputAudioFormat.channelCount}, encoding=$encoding",
        )
        return inputAudioFormat
    }

    override fun onFlush() {
        // Audio after a flush should start at the selected gain, not ramp up
        // from the previous song's gain. This is also the initial state.
        hasProcessedAudio = false
        lastTargetGainMb = Int.MIN_VALUE
        lastSeenGainState = null
        rampFramesRemaining = 0
        renderedLinearGain = 1.0
        rampTargetLinearGain = 1.0
        rampStepPerFrame = 0.0
    }

    private fun updateRampTarget(targetGainMb: Int, targetLinearGain: Double) {
        if (!hasProcessedAudio) {
            // A new stream's very first sample is already normalized.
            // Never impose a fade-in or an additional startup delay.
            renderedLinearGain = targetLinearGain
            rampTargetLinearGain = targetLinearGain
            rampFramesRemaining = 0
            rampStepPerFrame = 0.0
            lastTargetGainMb = targetGainMb
            return
        }
        if (lastTargetGainMb == targetGainMb) return

        // Only mid-stream gain changes are smoothed. At 48 kHz this is
        // 2,880 frames (60 ms), and no audio is buffered or delayed.
        val frames = (sampleRate.toLong() * RAMP_DURATION_MS / 1_000L).coerceAtLeast(1L).toInt()
        rampTargetLinearGain = targetLinearGain
        rampFramesRemaining = frames
        rampStepPerFrame = (targetLinearGain - renderedLinearGain) / frames
        lastTargetGainMb = targetGainMb
    }

    private fun nextFrameGain(): Double {
        if (rampFramesRemaining > 0) {
            renderedLinearGain += rampStepPerFrame
            rampFramesRemaining--
            if (rampFramesRemaining == 0) {
                // Avoid accumulating rounding error across consecutive ramps.
                renderedLinearGain = rampTargetLinearGain
            }
        }
        return renderedLinearGain
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val gain = currentGain
        if (gain !== lastSeenGainState) {
            if (gain.immediate) {
                // A selected track must never inherit the old song's ramp.
                hasProcessedAudio = false
            }
            lastSeenGainState = gain
        }
        val targetGainMb = if (enabled) gain.targetGainMb else 0
        val targetLinearGain = if (enabled) gain.linearGain else 1.0
        updateRampTarget(targetGainMb, targetLinearGain)

        // AudioProcessor input buffers contain complete interleaved frames.
        val frameBytes = bytesPerSample * channelCount
        val sampleCount = inputBuffer.remaining() / bytesPerSample
        val frameCount = inputBuffer.remaining() / frameBytes
        val ramping = rampFramesRemaining > 0

        if (!ramping && targetGainMb == 0) {
            // Once the ramp is finished, unity gain is a bit-exact PCM copy.
            // No per-sample decoding, multiplications or extra allocations.
            val output = replaceOutputBuffer(inputBuffer.remaining())
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            output.order(ByteOrder.LITTLE_ENDIAN)
            output.put(inputBuffer)
            output.flip()
            hasProcessedAudio = true
            return
        }

        val output = replaceOutputBuffer(sampleCount * bytesPerSample)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        if (!ramping) {
            // The normal playback path retains one multiplier per buffer.
            // The per-frame ramp runs only for ~60 ms following an update.
            val linearGain = targetLinearGain
            when (encoding) {
                C.ENCODING_PCM_16BIT ->
                    repeat(sampleCount) {
                        val sample = inputBuffer.getShort()
                        output.putShort(
                            (sample * linearGain).coerceIn(-32768.0, 32767.0).toInt().toShort(),
                        )
                    }
                C.ENCODING_PCM_24BIT ->
                    repeat(sampleCount) {
                        val b0 = inputBuffer.get().toInt() and 0xFF
                        val b1 = inputBuffer.get().toInt() and 0xFF
                        val b2 = inputBuffer.get().toInt()
                        val sample = (b2 shl 16) or (b1 shl 8) or b0
                        val processed = (sample * linearGain).coerceIn(-8388608.0, 8388607.0).toInt()
                        output.put((processed and 0xFF).toByte())
                        output.put(((processed shr 8) and 0xFF).toByte())
                        output.put(((processed shr 16) and 0xFF).toByte())
                    }
                C.ENCODING_PCM_32BIT ->
                    repeat(sampleCount) {
                        val sample = inputBuffer.getInt()
                        output.putInt(
                            (sample * linearGain).coerceIn(-2147483648.0, 2147483647.0).toLong().toInt(),
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
        } else {
            when (encoding) {
                C.ENCODING_PCM_16BIT ->
                    repeat(frameCount) {
                        val linearGain = nextFrameGain()
                        repeat(channelCount) {
                            val sample = inputBuffer.getShort()
                            output.putShort(
                                (sample * linearGain).coerceIn(-32768.0, 32767.0).toInt().toShort(),
                            )
                        }
                    }
                C.ENCODING_PCM_24BIT ->
                    repeat(frameCount) {
                        val linearGain = nextFrameGain()
                        repeat(channelCount) {
                            val b0 = inputBuffer.get().toInt() and 0xFF
                            val b1 = inputBuffer.get().toInt() and 0xFF
                            val b2 = inputBuffer.get().toInt()
                            val sample = (b2 shl 16) or (b1 shl 8) or b0
                            val processed = (sample * linearGain).coerceIn(-8388608.0, 8388607.0).toInt()
                            output.put((processed and 0xFF).toByte())
                            output.put(((processed shr 8) and 0xFF).toByte())
                            output.put(((processed shr 16) and 0xFF).toByte())
                        }
                    }
                C.ENCODING_PCM_32BIT ->
                    repeat(frameCount) {
                        val linearGain = nextFrameGain()
                        repeat(channelCount) {
                            val sample = inputBuffer.getInt()
                            output.putInt(
                                (sample * linearGain).coerceIn(-2147483648.0, 2147483647.0).toLong().toInt(),
                            )
                        }
                    }
                C.ENCODING_PCM_FLOAT ->
                    repeat(frameCount) {
                        val floatGain = nextFrameGain().toFloat()
                        repeat(channelCount) {
                            val sample = inputBuffer.getFloat()
                            output.putFloat((sample * floatGain).coerceIn(-1.0f, 1.0f))
                        }
                    }
            }
        }

        output.flip()
        hasProcessedAudio = true
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        sampleRate = 0
        channelCount = 0
        hasProcessedAudio = false
        lastTargetGainMb = Int.MIN_VALUE
        lastSeenGainState = null
        rampFramesRemaining = 0
        renderedLinearGain = 1.0
        rampTargetLinearGain = 1.0
        rampStepPerFrame = 0.0
    }

    private companion object {
        const val TAG = "VolumeNormalizationProcessor"
        const val RAMP_DURATION_MS = 60L
    }
}
