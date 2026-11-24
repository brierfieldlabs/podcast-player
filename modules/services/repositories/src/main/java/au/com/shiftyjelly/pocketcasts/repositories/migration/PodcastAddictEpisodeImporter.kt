package au.com.shiftyjelly.pocketcasts.repositories.migration

import android.content.Context
import androidx.room.withTransaction
import au.com.shiftyjelly.pocketcasts.models.db.AppDatabase
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Staged importer. Not connected to a UI or scheduled job yet.
 * Caller must obtain approval of the preview and coordinate playback and sync.
 */
internal class PodcastAddictEpisodeImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val playbackManager: PlaybackManager,
) {
    data class Applied(val changed: Int, val snapshot: File?)

    suspend fun applyReviewedChanges(changes: List<PodcastAddictEpisodeChange>): Applied = withContext(Dispatchers.IO) {
        require(changes.map { it.uuid }.distinct().size == changes.size)
        require(changes.all { it.after.positionSeconds.isFinite() && it.after.positionSeconds >= 0 })
        val now = System.currentTimeMillis()
        require(
            changes.all {
                it.after.lastPlaybackDate == it.before.lastPlaybackDate ||
                    (it.after.lastPlaybackDate ?: 0L) in 1..now
            },
        )
        check(!playbackManager.isPlaying()) { "Pause playback and review again" }
        database.withTransaction {
            val dao = database.episodeDao()
            val episodes = dao.findByUuids(changes.map { it.uuid }).associateBy { it.uuid }
            val subscribedFeeds = database.podcastDao().findSubscribedNoOrder()
            val feeds = subscribedFeeds.associateBy { it.uuid }
            val feedOccurrences = subscribedFeeds.groupingBy { it.podcastUrl?.trim() }.eachCount()
            val pending = changes.mapNotNull { change ->
                val episode = checkNotNull(episodes[change.uuid]) { "Episode no longer available" }
                val feed = feeds[episode.podcastUuid]
                check(feed?.podcastUrl?.trim() == change.feedUrl && feedOccurrences[change.feedUrl] == 1) {
                    "Feed changed or became ambiguous; preview again"
                }
                check(
                    episode.downloadUrl?.trim() == change.enclosure &&
                        episode.publishedDate.time == change.publicationDate,
                ) { "Episode identity changed; preview again" }
                val current = episode.migrationValues()
                if (current == change.after) return@mapNotNull null
                check(current == change.before) { "Episode state changed; preview again" }
                check(playbackManager.getCurrentEpisode()?.uuid != episode.uuid) { "Unload current episode before import" }
                check(episode.duration <= 0 || change.after.positionSeconds <= episode.duration) {
                    "Resume position exceeds destination duration; review again"
                }
                episode to change.after
            }
            if (pending.isEmpty()) return@withTransaction Applied(0, null)

            // Write the previous values before committing any database changes.
            // This is a local recovery record, not a guaranteed server rollback.
            val snapshot = saveSnapshot(pending.map { it.first })
            val modifiedAt = System.currentTimeMillis()
            val updated = pending.map { (episode, after) ->
                episode.copy(
                    playingStatus = after.playingStatus,
                    playingStatusModified = if (after.playingStatus != episode.playingStatus) {
                        modifiedAt
                    } else {
                        episode.playingStatusModified
                    },
                    playedUpTo = after.positionSeconds,
                    playedUpToModified = if (after.positionSeconds != episode.playedUpTo) {
                        modifiedAt
                    } else {
                        episode.playedUpToModified
                    },
                    isStarred = after.favourite,
                    starredModified = if (after.favourite != episode.isStarred) modifiedAt else episode.starredModified,
                    lastStarredDate = if (after.favourite != episode.isStarred) modifiedAt else episode.lastStarredDate,
                )
            }
            dao.updateAllSyncFields(updated)
            for ((episode, after) in pending) {
                val historyDate = after.lastPlaybackDate
                if (historyDate != null && historyDate > 0 && historyDate != episode.lastPlaybackInteraction) {
                    dao.updatePlaybackInteraction(
                        episode.uuid,
                        historyDate,
                        PodcastEpisode.LAST_PLAYBACK_INTERACTION_NOT_SYNCED,
                    )
                }
            }
            Applied(updated.size, snapshot)
        }
    }

    private fun PodcastEpisode.migrationValues() = PodcastAddictEpisodeValues(
        playingStatus, playedUpTo, isStarred, lastPlaybackInteraction,
    )

    private fun saveSnapshot(episodes: List<PodcastEpisode>): File {
        val file = File.createTempFile("podcast-addict-before-", ".jsonl", context.noBackupFilesDir)
        try {
            FileOutputStream(file).use { output ->
                val writer = output.bufferedWriter()
                for (episode in episodes) {
                    writer.write(JSONObject().apply {
                        put("uuid", episode.uuid)
                        put("podcastUuid", episode.podcastUuid)
                        put("playingStatus", episode.playingStatus.toInt())
                        put("positionSeconds", episode.playedUpTo)
                        put("favourite", episode.isStarred)
                        put("playingStatusModified", episode.playingStatusModified ?: JSONObject.NULL)
                        put("playedUpToModified", episode.playedUpToModified ?: JSONObject.NULL)
                        put("starredModified", episode.starredModified ?: JSONObject.NULL)
                        put("lastStarredDate", episode.lastStarredDate ?: JSONObject.NULL)
                        put("lastPlaybackInteraction", episode.lastPlaybackInteraction ?: JSONObject.NULL)
                        put("lastPlaybackInteractionSyncStatus", episode.lastPlaybackInteractionSyncStatus)
                    }.toString())
                    writer.newLine()
                }
                writer.flush()
                output.fd.sync()
            }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
        return file
    }
}
