package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stereo-to-dual-mono mixing of 16-bit PCM. Preserves the channel count, which
 * avoids changing audio routing or renegotiating a stream mid-playback.
 * Other formats and multichannel surround audio pass through unchanged.
 */
@OptIn(UnstableApi::class)
class MonoMixAudioProcessor(private val enabled: () -> Boolean) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
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
            while (inputBuffer.remaining() >= 4) {
                val left = inputBuffer.getShort().toInt()
                val right = inputBuffer.getShort().toInt()
                val mixed = ((left + right) / 2).toShort()
                output.putShort(mixed)
                output.putShort(mixed)
            }
            // PCM buffers are frame-aligned. Be defensive about an incomplete
            // trailing frame rather than leaving an unconsumed input buffer.
            while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        }
        output.flip()
    }
}
