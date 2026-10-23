package au.com.shiftyjelly.pocketcasts.repositories.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only compatibility check for a user-selected Podcast Addict full backup.
 *
 * This does NOT import or synchronise data. Table scans intentionally avoid
 * SQLite indexes because third-party backups may contain damaged indexes.
 * Never treat these counts as a reliable played/unplayed mapping.
 */
class PodcastAddictBackupInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface Result {
        data class Readable(
            val podcastCount: Int,
            val feedCount: Int,
            val subscribedFeedCount: Int,
            val episodeCount: Int,
            val episodesWithResumePosition: Int,
            val episodesWithPlaybackDate: Int,
            val listeningHistoryCount: Int,
            val completedHistoryCount: Int,
            val localSubscribedFeedCount: Int,
            val exactMatchedLocalFeeds: Int,
            val ambiguousFeedMatches: Int,
        ) : Result

        data class Rejected(val reason: Reason) : Result
    }

    enum class Reason {
        CannotOpenBackup,
        MissingDatabase,
        BackupTooLarge,
        UnreadableDatabase,
        MissingFeedUrls,
    }

    suspend fun inspect(
        backupUri: Uri,
        localSubscribedFeedUrls: Set<String> = emptySet(),
    ): Result = withContext(Dispatchers.IO) {
        val temp = try {
            File.createTempFile("podcast-addict-preview-", ".db", context.cacheDir)
        } catch (_: IOException) {
            return@withContext Result.Rejected(Reason.CannotOpenBackup)
        }
        try {
            val copyResult = copyDatabaseEntry(backupUri, temp)
            if (copyResult != null) return@withContext Result.Rejected(copyResult)

            SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                val subscribedFeeds = localSubscribedFeedUrls
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                val podcasts = scanFeedCounts(database, subscribedFeeds)
                if (podcasts.feedCount == 0) return@withContext Result.Rejected(Reason.MissingFeedUrls)

                var episodeCount = 0
                var resumeCount = 0
                var playbackDateCount = 0
                database.rawQuery(
                    "SELECT guid, playing_status, favorite, position_to_resume, playbackDate FROM episodes NOT INDEXED",
                    null,
                ).use { rows ->
                    while (rows.moveToNext()) {
                        episodeCount++
                        if (!rows.isNull(3) && rows.getLong(3) > 0L) resumeCount++
                        if (!rows.isNull(4) && rows.getLong(4) > 0L) playbackDateCount++
                    }
                }

                var historyCount = 0
                var completedCount = 0
                database.rawQuery(
                    "SELECT completed FROM playback_listening_history NOT INDEXED",
                    null,
                ).use { rows ->
                    while (rows.moveToNext()) {
                        historyCount++
                        if (!rows.isNull(0) && rows.getInt(0) == 1) completedCount++
                    }
                }

                Result.Readable(
                    podcastCount = podcasts.podcastCount,
                    feedCount = podcasts.feedCount,
                    subscribedFeedCount = podcasts.subscribedFeedCount,
                    episodeCount = episodeCount,
                    episodesWithResumePosition = resumeCount,
                    episodesWithPlaybackDate = playbackDateCount,
                    listeningHistoryCount = historyCount,
                    completedHistoryCount = completedCount,
                    localSubscribedFeedCount = subscribedFeeds.size,
                    exactMatchedLocalFeeds = podcasts.exactMatches,
                    ambiguousFeedMatches = podcasts.ambiguousMatches,
                )
            }
        } catch (_: SQLiteException) {
            Result.Rejected(Reason.UnreadableDatabase)
        } catch (_: IOException) {
            Result.Rejected(Reason.CannotOpenBackup)
        } catch (_: SecurityException) {
            Result.Rejected(Reason.CannotOpenBackup)
        } finally {
            temp.delete()
        }
    }

    private data class FeedCounts(
        val podcastCount: Int,
        val feedCount: Int,
        val subscribedFeedCount: Int,
        val exactMatches: Int,
        val ambiguousMatches: Int,
    )

    /** Exact RSS URL comparisons only. Never infer matches from podcast titles. */
    private fun scanFeedCounts(
        database: SQLiteDatabase,
        localFeeds: Set<String>,
    ): FeedCounts {
        var podcasts = 0
        var feeds = 0
        var subscribedFeeds = 0
        val matchOccurrences = mutableMapOf<String, Int>()
        database.rawQuery("SELECT feed_url, subscribed_status FROM podcasts NOT INDEXED", null).use { rows ->
            while (rows.moveToNext()) {
                podcasts++
                if (!rows.isNull(0)) {
                    val url = rows.getString(0).trim()
                    if (url.isNotEmpty()) {
                        feeds++
                        if (!rows.isNull(1) && rows.getInt(1) == 1) {
                            subscribedFeeds++
                            if (url in localFeeds) matchOccurrences[url] = (matchOccurrences[url] ?: 0) + 1
                        }
                    }
                }
            }
        }
        return FeedCounts(
            podcastCount = podcasts,
            feedCount = feeds,
            subscribedFeedCount = subscribedFeeds,
            exactMatches = matchOccurrences.count { (_, occurrences) -> occurrences == 1 },
            ambiguousMatches = matchOccurrences.count { (_, occurrences) -> occurrences > 1 },
        )
    }

    /** Extract only the expected SQLite entry, never ZIP-supplied paths. */
    private fun copyDatabaseEntry(uri: Uri, destination: File): Reason? {
        val source = context.contentResolver.openInputStream(uri) ?: return Reason.CannotOpenBackup
        source.use { input ->
            ZipInputStream(input.buffered()).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (entry.name == "podcastAddict.db" && !entry.isDirectory) {
                        var copied = 0L
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val length = archive.read(buffer)
                                if (length < 0) break
                                copied += length
                                if (copied > MAX_DATABASE_BYTES) return Reason.BackupTooLarge
                                output.write(buffer, 0, length)
                            }
                        }
                        return null
                    }
                    archive.closeEntry()
                }
            }
        }
        return Reason.MissingDatabase
    }

    private companion object {
        const val MAX_DATABASE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
