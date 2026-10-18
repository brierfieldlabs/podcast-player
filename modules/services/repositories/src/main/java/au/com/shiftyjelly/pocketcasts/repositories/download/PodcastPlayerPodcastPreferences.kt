package au.com.shiftyjelly.pocketcasts.repositories.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in device-local podcast listening rules.
 *
 * Keep these separate from Pocket Casts' server-synchronised podcast schema,
 * so the fork never sends unknown settings to the existing web service.
 */
@Singleton
class PodcastPlayerPodcastPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("podcast_player_downloads", Context.MODE_PRIVATE)

    fun rollingLimit(podcastUuid: String): Int =
        preferences.getInt("rolling_$podcastUuid", 0).coerceIn(0, MAX_ROLLING_DOWNLOADS)

    fun setRollingLimit(podcastUuid: String, limit: Int) {
        require(limit in 0..MAX_ROLLING_DOWNLOADS)
        preferences.edit().putInt("rolling_$podcastUuid", limit).apply()
    }

    fun excludedTitles(podcastUuid: String): String =
        preferences.getString("excluded_$podcastUuid", "").orEmpty()

    fun setExcludedTitles(podcastUuid: String, value: String): String =
        saveTerms("excluded_$podcastUuid", value)

    fun matchesExcludedTitle(podcastUuid: String, episodeTitle: String): Boolean =
        matchesTerms(excludedTitles(podcastUuid), episodeTitle)

    fun skippedChapterTitles(podcastUuid: String): String =
        preferences.getString("skip_chapters_$podcastUuid", "").orEmpty()

    fun setSkippedChapterTitles(podcastUuid: String, value: String): String =
        saveTerms("skip_chapters_$podcastUuid", value)

    fun matchesSkippedChapterTitle(podcastUuid: String, chapterTitle: String): Boolean =
        matchesTerms(skippedChapterTitles(podcastUuid), chapterTitle)

    private fun saveTerms(key: String, value: String): String {
        val cleaned = value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .joinToString(", ")
        preferences.edit().putString(key, cleaned).apply()
        return cleaned
    }

    private fun matchesTerms(terms: String, title: String): Boolean =
        terms.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { phrase -> title.contains(phrase, ignoreCase = true) }

    companion object {
        const val MAX_ROLLING_DOWNLOADS = 10
    }
}
