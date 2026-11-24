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
    data class LocalFeed(
        val url: String,
        val episodes: List<LocalEpisode>,
    )

    data class LocalEpisode(
        val enclosure: String?,
        val publicationDate: Long,
        val positionSeconds: Double,
        val favourite: Boolean,
    )

    data class EpisodeMatches(
        val exact: Int,
        val ambiguous: Int,
        val unmatched: Int,
        val resumeDifferences: Int,
        val favouriteDifferences: Int,
        val completionEvidence: Int,
    )

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
            val episodes: EpisodeMatches,
            val statisticsDays: Int?,
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
        localFeeds: List<LocalFeed> = emptyList(),
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
                val subscribedFeeds = localFeeds.map { it.url.trim() }.filter { it.isNotEmpty() }.toSet()
                val podcasts = scanFeedCounts(database, subscribedFeeds)
                val episodeMatches = scanEpisodeMatches(database, localFeeds)
                val statisticsDays = database.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='playback_stats_day'",
                    null,
                ).use { it.moveToFirst() }.let { exists ->
                    if (exists) {
                        database.rawQuery("SELECT COUNT(*) FROM playback_stats_day NOT INDEXED", null)
                            .use {
                                it.moveToFirst()
                                it.getInt(0)
                            }
                    } else {
                        null
                    }
                }
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
                    episodes = episodeMatches,
                    statisticsDays = statisticsDays,
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

    private data class SourceEpisode(
        val id: Long,
        val publicationDate: Long,
        val positionMs: Long,
        val favourite: Boolean,
    )

    /**
     * Pocket Casts UUIDs are not RSS GUIDs. Only count one-to-one exact enclosure
     * matches within an exact feed, with publication date corroboration.
     * Completion evidence is historical, not a proposed current played status.
     */
    private fun scanEpisodeMatches(database: SQLiteDatabase, localFeeds: List<LocalFeed>): EpisodeMatches {
        val sourceFeeds = mutableMapOf<String, MutableList<Long>>()
        database.rawQuery("SELECT _id, feed_url FROM podcasts NOT INDEXED WHERE subscribed_status=1", null)
            .use { rows ->
                while (rows.moveToNext()) {
                    val url = rows.getString(1)?.trim().orEmpty()
                    if (url.isNotEmpty()) sourceFeeds.getOrPut(url) { mutableListOf() }.add(rows.getLong(0))
                }
            }
        val targetFeeds = localFeeds.groupBy { it.url.trim() }
        val targetEnclosures = mutableMapOf<Long, Set<String>>()
        for ((url, ids) in sourceFeeds) {
            val targets = targetFeeds[url] ?: continue
            if (ids.size == 1 && targets.size == 1) {
                targetEnclosures[ids.single()] = targets.single().episodes
                    .mapNotNull { it.enclosure?.trim()?.takeIf(String::isNotEmpty) }
                    .toSet()
            }
        }
        val sourceEpisodes = mutableMapOf<Pair<Long, String>, MutableList<SourceEpisode>>()
        database.rawQuery(
            "SELECT podcast_id, download_url, _id, publication_date, position_to_resume, " +
                "favorite FROM episodes NOT INDEXED",
            null,
        ).use { rows ->
            while (rows.moveToNext()) {
                val feedId = rows.getLong(0)
                val enclosures = targetEnclosures[feedId] ?: continue
                val enclosure = rows.getString(1)?.trim().orEmpty()
                if (enclosure.isEmpty() || enclosure !in enclosures) continue
                val source = SourceEpisode(rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getInt(5) == 1)
                sourceEpisodes.getOrPut(feedId to enclosure) { mutableListOf() }.add(source)
            }
        }
        val completedIds = mutableSetOf<Long>()
        database.rawQuery(
            "SELECT episode_id FROM playback_listening_history NOT INDEXED WHERE completed=1",
            null,
        ).use { rows ->
            while (rows.moveToNext()) completedIds.add(rows.getLong(0))
        }
        var exact = 0
        var ambiguous = 0
        var unmatched = 0
        var resumeDifferences = 0
        var favouriteDifferences = 0
        var completionEvidence = 0
        for ((url, ids) in sourceFeeds) {
            val targets = targetFeeds[url] ?: continue
            if (ids.size != 1 || targets.size != 1) {
                ambiguous += targets.sumOf { it.episodes.size }
                continue
            }
            val targetEpisodes = targets.single().episodes.groupBy { it.enclosure?.trim().orEmpty() }
            for ((enclosure, episodes) in targetEpisodes) {
                val sources = sourceEpisodes[ids.single() to enclosure]
                if (enclosure.isEmpty() || sources.isNullOrEmpty()) {
                    unmatched += episodes.size
                    continue
                }
                if (sources.size != 1 || episodes.size != 1) {
                    ambiguous += episodes.size
                    continue
                }
                val source = sources.single()
                val target = episodes.single()
                val targetDate = target.publicationDate
                if (
                    source.publicationDate == 0L || targetDate == 0L ||
                    kotlin.math.abs(source.publicationDate - targetDate) > DATE_TOLERANCE_MS
                ) {
                    ambiguous++
                    continue
                }
                exact++
                if (kotlin.math.abs(source.positionMs / 1000.0 - target.positionSeconds) >= 1.0) resumeDifferences++
                if (source.favourite != target.favourite) favouriteDifferences++
                if (source.id in completedIds) completionEvidence++
            }
        }
        return EpisodeMatches(exact, ambiguous, unmatched, resumeDifferences, favouriteDifferences, completionEvidence)
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
        const val DATE_TOLERANCE_MS = 24L * 60 * 60 * 1000
        const val MAX_DATABASE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
