package au.com.shiftyjelly.pocketcasts.repositories.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import au.com.shiftyjelly.pocketcasts.models.type.EpisodePlayingStatus
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
        val uuid: String,
        val enclosure: String?,
        val publicationDate: Long,
        val positionSeconds: Double,
        val favourite: Boolean,
        val playingStatus: EpisodePlayingStatus,
        val lastPlaybackDate: Long?,
        val durationSeconds: Double,
    )

    data class EpisodeMatches(
        val exact: Int,
        val ambiguous: Int,
        val unmatched: Int,
        val resumeDifferences: Int,
        val favouriteDifferences: Int,
        val completionEvidence: Int,
        val markPlayed: Int,
        val markUnplayed: Int,
        val markInProgress: Int,
        val unresolvedState: Int,
        val sourceEpisodesWithoutExactMatch: Int,
        val changes: List<PodcastAddictEpisodeChange>,
        val historyDifferences: Int,
    )

    data class SourceStatistics(
        val days: Int,
        val wallListeningMs: Long,
        val contentListeningMs: Long,
        val speedSavedMs: Long,
        val silenceSavedMs: Long,
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
            val missingSubscriptions: Int,
            val missingFeedUrls: List<String>,
            val destinationOnlySubscriptions: Int,
            val episodes: EpisodeMatches,
            val statistics: SourceStatistics?,
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
                val podcasts = scanFeedCounts(database, localFeeds.groupingBy { it.url.trim() }.eachCount())
                val episodeMatches = scanEpisodeMatches(database, localFeeds)
                val statistics = database.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='playback_stats_day'",
                    null,
                ).use { it.moveToFirst() }.let { exists ->
                    if (exists) {
                        database.rawQuery(
                            "SELECT COUNT(*), SUM(wall_played_ms), SUM(content_played_ms), " +
                                "SUM(playback_speed_saved_ms), SUM(skipped_silence_ms) FROM playback_stats_day NOT INDEXED",
                            null,
                        ).use {
                            it.moveToFirst()
                            SourceStatistics(it.getInt(0), it.getLong(1), it.getLong(2), it.getLong(3), it.getLong(4))
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
                    missingSubscriptions = podcasts.missingFeedUrls.size,
                    missingFeedUrls = podcasts.missingFeedUrls,
                    destinationOnlySubscriptions = podcasts.destinationOnlySubscriptions,
                    episodes = episodeMatches,
                    statistics = statistics,
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
        val positionMs: Long?,
        val favourite: Boolean?,
        val seenStatus: Int?,
        val playbackDate: Long?,
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
        var sourceEpisodeCount = 0
        val sourceEpisodes = mutableMapOf<Pair<Long, String>, MutableList<SourceEpisode>>()
        database.rawQuery(
            "SELECT podcast_id, download_url, _id, publication_date, position_to_resume, " +
                "favorite, seen_status, playbackDate FROM episodes NOT INDEXED",
            null,
        ).use { rows ->
            while (rows.moveToNext()) {
                val feedId = rows.getLong(0)
                val enclosures = targetEnclosures[feedId] ?: continue
                sourceEpisodeCount++
                val enclosure = rows.getString(1)?.trim().orEmpty()
                if (enclosure.isEmpty() || enclosure !in enclosures) continue
                val source = SourceEpisode(
                    rows.getLong(2),
                    rows.getLong(3),
                    if (rows.isNull(4)) null else rows.getLong(4),
                    if (rows.isNull(5) || rows.getInt(5) !in 0..1) null else rows.getInt(5) == 1,
                    if (rows.isNull(6)) null else rows.getInt(6),
                    if (rows.isNull(7)) null else rows.getLong(7),
                )
                sourceEpisodes.getOrPut(feedId to enclosure) { mutableListOf() }.add(source)
            }
        }
        val completedIds = mutableSetOf<Long>()
        val lastListened = mutableMapOf<Long, Long>()
        database.rawQuery(
            "SELECT episode_id, completed, last_listened_at FROM playback_listening_history NOT INDEXED",
            null,
        ).use { rows ->
            while (rows.moveToNext()) {
                val episodeId = rows.getLong(0)
                if (rows.getInt(1) == 1) completedIds.add(episodeId)
                val listenedAt = rows.getLong(2)
                if (listenedAt > (lastListened[episodeId] ?: 0L)) lastListened[episodeId] = listenedAt
            }
        }
        var exact = 0
        var ambiguous = 0
        var unmatched = 0
        var resumeDifferences = 0
        var favouriteDifferences = 0
        var completionEvidence = 0
        var markPlayed = 0
        var markUnplayed = 0
        var markInProgress = 0
        var unresolvedState = 0
        var historyDifferences = 0
        val now = System.currentTimeMillis()
        val changes = mutableListOf<PodcastAddictEpisodeChange>()
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
                if (source.id in completedIds) completionEvidence++
                val proposed = PodcastAddictEpisodeState.propose(source.seenStatus, source.positionMs)
                val positionMs = source.positionMs
                val favourite = source.favourite
                if (proposed == null || positionMs == null || favourite == null) {
                    unresolvedState++
                    continue
                }
                val positionSeconds = positionMs / 1000.0
                if (target.durationSeconds > 0 && positionSeconds > target.durationSeconds) {
                    unresolvedState++
                    continue
                }
                val historyDate = maxOf(source.playbackDate ?: 0L, lastListened[source.id] ?: 0L)
                    .takeIf { it in 1..now }
                    ?: target.lastPlaybackDate
                if (positionSeconds != target.positionSeconds) resumeDifferences++
                if (favourite != target.favourite) favouriteDifferences++
                if (historyDate != target.lastPlaybackDate) historyDifferences++
                when (proposed) {
                    target.playingStatus -> Unit
                    EpisodePlayingStatus.COMPLETED -> markPlayed++
                    EpisodePlayingStatus.NOT_PLAYED -> markUnplayed++
                    EpisodePlayingStatus.IN_PROGRESS -> markInProgress++
                }
                val before = PodcastAddictEpisodeValues(
                    target.playingStatus, target.positionSeconds, target.favourite, target.lastPlaybackDate,
                )
                val after = PodcastAddictEpisodeValues(proposed, positionSeconds, favourite, historyDate)
                if (before != after) {
                    changes += PodcastAddictEpisodeChange(
                        uuid = target.uuid,
                        feedUrl = url,
                        enclosure = enclosure,
                        publicationDate = target.publicationDate,
                        before = before,
                        after = after,
                    )
                }
            }
        }
        return EpisodeMatches(
            exact, ambiguous, unmatched, resumeDifferences, favouriteDifferences, completionEvidence,
            markPlayed, markUnplayed, markInProgress, unresolvedState, sourceEpisodeCount - exact,
            changes, historyDifferences,
        )
    }

    private data class FeedCounts(
        val podcastCount: Int,
        val feedCount: Int,
        val subscribedFeedCount: Int,
        val exactMatches: Int,
        val ambiguousMatches: Int,
        val missingFeedUrls: List<String>,
        val destinationOnlySubscriptions: Int,
    )

    /** Exact RSS URL comparisons only. Never infer matches from podcast titles. */
    private fun scanFeedCounts(
        database: SQLiteDatabase,
        localFeeds: Map<String, Int>,
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
                            matchOccurrences[url] = (matchOccurrences[url] ?: 0) + 1
                        }
                    }
                }
            }
        }
        return FeedCounts(
            podcastCount = podcasts,
            feedCount = feeds,
            subscribedFeedCount = subscribedFeeds,
            exactMatches = matchOccurrences.count { (url, occurrences) -> occurrences == 1 && localFeeds[url] == 1 },
            ambiguousMatches = matchOccurrences.count { (url, occurrences) ->
                occurrences > 1 || (localFeeds[url] ?: 0) > 1
            },
            missingFeedUrls = matchOccurrences.filter { (url, occurrences) ->
                occurrences == 1 && url !in localFeeds
            }.keys.sorted(),
            destinationOnlySubscriptions = localFeeds.keys.count { it.isNotEmpty() && it !in matchOccurrences },
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
