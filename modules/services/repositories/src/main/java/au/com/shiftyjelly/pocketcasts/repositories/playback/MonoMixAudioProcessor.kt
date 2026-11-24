package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stereo-to-dual-mono mixing of 16-bit or floating-point PCM. Preserves the
 * channel count, avoiding changes to routing or stream format mid-playback.
 * Multichannel surround and unsupported encodings pass through unchanged.
 */
@OptIn(UnstableApi::class)
class MonoMixAudioProcessor(private val enabled: () -> Boolean) : BaseAudioProcessor() {
    private var isFloatPcm = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        isFloatPcm = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || isFloatPcm
        return if (supported && inputAudioFormat.channelCount == 2) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (!enabled()) {
            output.put(inputBuffer)
        } else {
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            output.order(ByteOrder.LITTLE_ENDIAN)
            val bytesPerFrame = if (isFloatPcm) 8 else 4
            while (inputBuffer.remaining() >= bytesPerFrame) {
                if (isFloatPcm) {
                    val left = inputBuffer.getFloat()
                    val right = inputBuffer.getFloat()
                    val mixed = (left + right) / 2f
                    output.putFloat(mixed)
                    output.putFloat(mixed)
                } else {
                    val left = inputBuffer.getShort().toInt()
                    val right = inputBuffer.getShort().toInt()
                    val mixed = ((left + right) / 2).toShort()
                    output.putShort(mixed)
                    output.putShort(mixed)
                }
            }
            // Audio buffers should contain complete stereo frames; don't
            // discard any trailing bytes if a producer violates alignment.
            while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        }
        output.flip()
    }
}
