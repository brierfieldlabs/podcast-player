package au.com.shiftyjelly.pocketcasts.repositories.migration

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * A device-local source summary. Never written into Pocket Casts' live counters
 * or sent to its account/analytics endpoints. Re-import replaces the summary.
 */
@Singleton
class PodcastAddictStatisticsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "podcast-addict-statistics.json"))

    suspend fun save(statistics: PodcastAddictBackupInspector.SourceStatistics) = withContext(Dispatchers.IO) {
        require(statistics.days >= 0)
        require(
            listOf(
                statistics.wallListeningMs,
                statistics.contentListeningMs,
                statistics.speedSavedMs,
                statistics.silenceSavedMs,
                statistics.episodeDayCount,
                statistics.introSavedMs,
                statistics.outroSavedMs,
                statistics.forwardSavedMs,
                statistics.adsSavedMs,
                statistics.mutedChapterSavedMs,
                statistics.liveStreamMs,
            ).all { it >= 0 },
        )
        val data = JSONObject().apply {
            put("days", statistics.days)
            put("wallListeningMs", statistics.wallListeningMs)
            put("contentListeningMs", statistics.contentListeningMs)
            put("speedSavedMs", statistics.speedSavedMs)
            put("silenceSavedMs", statistics.silenceSavedMs)
            put("episodeDayCount", statistics.episodeDayCount)
            put("introSavedMs", statistics.introSavedMs)
            put("outroSavedMs", statistics.outroSavedMs)
            put("forwardSavedMs", statistics.forwardSavedMs)
            put("adsSavedMs", statistics.adsSavedMs)
            put("mutedChapterSavedMs", statistics.mutedChapterSavedMs)
            put("liveStreamMs", statistics.liveStreamMs)
        }.toString().toByteArray(Charsets.UTF_8)
        synchronized(this@PodcastAddictStatisticsStore) {
            val output = file.startWrite()
            try {
                output.write(data)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        }
    }

    suspend fun load(): PodcastAddictBackupInspector.SourceStatistics? = withContext(Dispatchers.IO) {
        synchronized(this@PodcastAddictStatisticsStore) {
            try {
                val data = JSONObject(file.readFully().toString(Charsets.UTF_8))
                PodcastAddictBackupInspector.SourceStatistics(
                    days = data.getInt("days"),
                    wallListeningMs = data.getLong("wallListeningMs"),
                    contentListeningMs = data.getLong("contentListeningMs"),
                    speedSavedMs = data.getLong("speedSavedMs"),
                    silenceSavedMs = data.getLong("silenceSavedMs"),
                    episodeDayCount = data.getLong("episodeDayCount"),
                    introSavedMs = data.getLong("introSavedMs"),
                    outroSavedMs = data.getLong("outroSavedMs"),
                    forwardSavedMs = data.getLong("forwardSavedMs"),
                    adsSavedMs = data.getLong("adsSavedMs"),
                    mutedChapterSavedMs = data.getLong("mutedChapterSavedMs"),
                    liveStreamMs = data.getLong("liveStreamMs"),
                )
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }
    }
}
