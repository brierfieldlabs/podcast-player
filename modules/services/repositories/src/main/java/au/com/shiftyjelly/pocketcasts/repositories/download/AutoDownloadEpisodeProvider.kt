package au.com.shiftyjelly.pocketcasts.repositories.download

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.PlaylistEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.playlist.PlaylistManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutoDownloadEpisodeProvider @Inject constructor(
    private val podcastManager: PodcastManager,
    private val episodeManager: EpisodeManager,
    private val playlistManager: PlaylistManager,
    private val upNextQueue: UpNextQueue,
    private val settings: Settings,
    private val podcastPlayerPreferences: PodcastPlayerPodcastPreferences,
) {
    // Set dispatcher to default because some users follow thousands of podcasts
    suspend fun getAll(newPodcastEpisodeUuids: Collection<String>) = withContext(Dispatchers.Default) {
        buildSet {
            addAll(getPodcastEpisodes(newPodcastEpisodeUuids))
            addAll(getPlaylistEpisodes())
            addAll(getUpNextEpisodes())
        }
    }

    /**
     * Reuse the synchronised dismiss state to exclude matching newly arrived
     * episodes. The local rule does not change their played status.
     */
    suspend fun dismissExcludedNewEpisodes(
        newEpisodeUuids: Collection<String>,
        playbackManager: PlaybackManager,
    ): Set<String> = withContext(Dispatchers.IO) {
        buildSet {
            for (episode in episodeManager.findByUuids(newEpisodeUuids)) {
                if (!episode.isArchived &&
                    !episode.isFinished &&
                    podcastPlayerPreferences.matchesExcludedTitle(episode.podcastUuid, episode.title)
                ) {
                    episodeManager.archiveBlocking(episode, playbackManager)
                    add(episode.uuid)
                }
            }
        }
    }

    /**
     * Fill available offline slots using oldest unfinished episodes, without
     * touching any files already downloaded. Disabled by default per podcast.
     */
    suspend fun getRollingEpisodes(podcast: Podcast): Set<String> {
        val limit = podcastPlayerPreferences.rollingLimit(podcast.uuid)
        if (limit == 0 || !podcast.isSubscribed) return emptySet()

        val episodes = episodeManager.findEpisodesByPodcastOrdered(podcast)
            .asSequence()
            .filter { !it.isFinished && !it.isArchived }
            .sortedWith(compareBy<PodcastEpisode> { it.publishedDate }.thenBy { it.uuid })
            .toList()
        // Fill the oldest unfinished slots first. Already downloaded newer
        // episodes remain available, but cannot displace missing older ones.
        return episodes.asSequence()
            // Non-downloadable media and failed items must not take one of the
            // N offline slots. A failed item can be retried manually.
            .filterNot { it.isHlsOnly || it.isAutoDownloadDisabled || it.isDownloadFailure }
            .take(limit)
            .filter { it.canQueueForAutoDownload }
            .filterNot { it.isDownloaded || it.isDownloading || it.isDownloadPending }
            .map(PodcastEpisode::uuid)
            .toSet()
    }

    suspend fun getAllRollingEpisodes(): Set<String> = withContext(Dispatchers.Default) {
        buildSet {
            for (podcast in podcastManager.findSubscribedNoOrder()) {
                if (podcastPlayerPreferences.rollingLimit(podcast.uuid) > 0) {
                    addAll(getRollingEpisodes(podcast))
                }
            }
        }
    }

    private suspend fun getPodcastEpisodes(newEpisodeUuids: Collection<String>): Set<String> {
        val globalEnabled = settings.autoDownloadNewEpisodes.value == Podcast.AUTO_DOWNLOAD_NEW_EPISODES
        return if (globalEnabled && newEpisodeUuids.isNotEmpty()) {
            val perPodcastLimit = settings.autoDownloadLimit.value.episodeCount
            val newEpisodeUuidSet = newEpisodeUuids.toSet()
            podcastManager
                .findSubscribedNoOrder()
                .asSequence()
                .filter(Podcast::isAutoDownloadNewEpisodes)
                .flatMapTo(mutableSetOf()) { podcast ->
                    episodeManager
                        .findEpisodesByPodcastOrdered(podcast)
                        .asSequence()
                        .filter { episode -> episode.uuid in newEpisodeUuidSet }
                        .filter { episode ->
                            val canQueue = episode.canQueueForAutoDownload
                            if (!canQueue) {
                                LogBuffer.i(LogBuffer.TAG_DOWNLOAD, "Can't auto download new episode '${episode.uuid}'. Archived: ${episode.isArchived}, Finished: ${episode.isFinished}, Disabled: ${episode.isAutoDownloadDisabled}")
                            }
                            canQueue
                        }
                        .map(BaseEpisode::uuid)
                        .take(perPodcastLimit)
                }
        } else {
            emptySet()
        }
    }

    private suspend fun getPlaylistEpisodes(): Set<String> {
        return playlistManager
            .getAutoDownloadPlaylists()
            .flatMapTo(mutableSetOf()) { playlist ->
                playlist.episodes
                    .asSequence()
                    .mapNotNull(PlaylistEpisode::toPodcastEpisode)
                    .filter(BaseEpisode::canQueueForAutoDownload)
                    .map(BaseEpisode::uuid)
                    .take(playlist.settings.autoDownloadLimit)
            }
    }

    private fun getUpNextEpisodes(): Set<String> {
        return if (settings.autoDownloadUpNext.value) {
            upNextQueue.allEpisodes
                .asSequence()
                .filter(BaseEpisode::canQueueForAutoDownload)
                .map(BaseEpisode::uuid)
                .toSet()
        } else {
            emptySet()
        }
    }
}
