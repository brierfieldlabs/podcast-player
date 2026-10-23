package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local settings. Do not extend Pocket Casts' server-side playback schema. */
@Singleton
class PodcastPlayerAudioPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("podcast_player_audio", Context.MODE_PRIVATE)

    // ExoPlayer consults this on its audio thread for each PCM buffer. Cache
    // the flag in memory rather than crossing SharedPreferences on every read.
    @Volatile
    private var monoEnabled = preferences.getBoolean("mono", false)

    var mono: Boolean
        get() = monoEnabled
        set(value) {
            monoEnabled = value
            preferences.edit().putBoolean("mono", value).apply()
        }

    fun gainDb(band: Band): Int = preferences.getInt(band.key, 0).coerceIn(-6, 6)

    fun setGainDb(band: Band, gain: Int) {
        require(gain in -6..6)
        preferences.edit().putInt(band.key, gain).apply()
    }

    fun hasEqualizerAdjustment(): Boolean = Band.entries.any { gainDb(it) != 0 }

    enum class Band(val key: String) {
        Bass("eq_bass"),
        Mid("eq_mid"),
        Treble("eq_treble"),
    }
}
