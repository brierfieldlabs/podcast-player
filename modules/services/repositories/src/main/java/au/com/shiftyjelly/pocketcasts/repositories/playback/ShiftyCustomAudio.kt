package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import au.com.shiftyjelly.pocketcasts.repositories.user.StatsManager
import timber.log.Timber

class ShiftyCustomAudio(
    private val statsManager: StatsManager,
    private val audioPreferences: PodcastPlayerAudioPreferences,
) {
    private var enhancer: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null
    private var audioSessionId: Int? = null
    private var boostVolume = false
    var playbackSpeed = 0f

    fun setBoostVolume(boostVolume: Boolean) {
        this.boostVolume = boostVolume
        refreshEffects()
    }

    fun setupVolumeBoost(audioSessionId: Int) {
        enhancer?.release()
        equalizer?.release()
        enhancer = null
        equalizer = null
        this.audioSessionId = audioSessionId
        try {
            enhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(LOUDNESS_TARGET_GAIN)
            }
        } catch (e: Exception) {
            Timber.w(e, "Device does not support loudness enhancement")
        }
        refreshEffects()
    }

    /** Apply user EQ changes to the current session without restarting playback. */
    fun refreshEffects() {
        val customEqualizer = audioPreferences.hasEqualizerAdjustment()
        if ((customEqualizer || (boostVolume && enhancer == null)) && equalizer == null) {
            val session = audioSessionId
            if (session != null) {
                try {
                    equalizer = Equalizer(0, session)
                } catch (e: Exception) {
                    Timber.w(e, "Device does not support Android equalizer")
                }
            }
        }

        val hasEqualizer = equalizer != null
        try {
            // Volume boost remains a separate user setting. Where supported,
            // Android can run both effects on the same local audio session.
            enhancer?.enabled = boostVolume
            if (!customEqualizer && (!boostVolume || enhancer != null)) {
                // Flat EQ and no fallback boost need no platform EQ work.
                // Leave an existing effect disabled until it is requested.
                equalizer?.enabled = false
                return
            }
            equalizer?.let { effect ->
                for (index in 0 until effect.numberOfBands.toInt()) {
                    val band = index.toShort()
                    val hz = effect.getCenterFreq(band) / 1000
                    val gainDb = if (customEqualizer) {
                        when {
                            hz < 400 -> audioPreferences.gainDb(PodcastPlayerAudioPreferences.Band.Bass)
                            hz < 3500 -> audioPreferences.gainDb(PodcastPlayerAudioPreferences.Band.Mid)
                            else -> audioPreferences.gainDb(PodcastPlayerAudioPreferences.Band.Treble)
                        }
                    } else {
                        // Preserve the original fixed voice-boost fallback on
                        // devices that do not provide LoudnessEnhancer.
                        when (hz) {
                            in 0..<100 -> -5
                            in 100..<250 -> 0
                            in 250..<1000 -> 10
                            in 1000..<2000 -> 12
                            in 2000..<10000 -> 8
                            else -> 0
                        }
                    }
                    val range = effect.bandLevelRange
                    effect.setBandLevel(band, (gainDb * 100).coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
                effect.enabled = if (customEqualizer) true else boostVolume && enhancer == null
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to apply Android audio effects")
            try {
                equalizer?.enabled = false
            } catch (_: Exception) {
                // The platform may have invalidated the audio session.
            }
        }
        if (customEqualizer && !hasEqualizer) {
            Timber.w("Equalizer requested but unavailable for current audio session")
        }
    }

    fun addSilenceSkippedTime(timeUs: Long) {
        statsManager.addTimeSavedSilenceRemoval(timeUs / 1000)
    }

    fun addVariableSpeedTime(timeUs: Long, speed: Float) {
        statsManager.addTimeSavedVariableSpeed((((timeUs * speed) - timeUs) / 1000).toLong())
    }

    private companion object {
        const val LOUDNESS_TARGET_GAIN = 1000
    }
}
