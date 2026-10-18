package au.com.shiftyjelly.pocketcasts.repositories.playback

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.UserEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.model.AutoPlaySource
import au.com.shiftyjelly.pocketcasts.repositories.file.CloudFilesManager
import au.com.shiftyjelly.pocketcasts.repositories.playlist.PlaylistManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class AutoPlaySelector @Inject constructor(
    private val settings: Settings,
    private val podcastManager: PodcastManager,
    private val episodeManager: EpisodeManager,
    private val playlistManager: PlaylistManager,
    private val cloudFilesManager: CloudFilesManager,
) {
    suspend fun selectNextEpisode(currentEpisodeUuid: String?): Pair<BaseEpisode, AutoPlaySource>? {
        val source = settings.lastAutoPlaySource.value
        var stopAtPodcastEnd = false
        val episodes = when (source) {
            is AutoPlaySource.PodcastOrFilter -> {
                val podcast = podcastManager.findPodcastByUuid(uuid = source.uuid)
                if (podcast != null) {
                    stopAtPodcastEnd = true
                    findPodcastEpisodes(podcast, currentEpisodeUuid)
                } else {
                    findPlaylistEpisodes(source.uuid, currentEpisodeUuid)
                }
            }

            AutoPlaySource.Predefined.Downloads -> findDownloadedEpisodes()

            AutoPlaySource.Predefined.Files -> findUserEpisodes()

            AutoPlaySource.Predefined.Starred -> findStarredEpisodes()

            AutoPlaySource.Predefined.None -> emptyList()
        }
        val episode = if (currentEpisodeUuid == null) {
            episodes.firstOrNull()
        } else {
            val currentEpisodeIndex = episodes.indexOfFirst { it.uuid == currentEpisodeUuid }
            when {
                // A removed/currently unavailable podcast episode is no longer
                // a safe chronological anchor. Never restart the podcast from
                // its oldest unfinished episode in that situation.
                currentEpisodeIndex == -1 && stopAtPodcastEnd -> null
                currentEpisodeIndex == -1 -> episodes.firstOrNull()
                else -> episodes.getOrNull(currentEpisodeIndex + 1)
                    ?: if (stopAtPodcastEnd) null else episodes.firstOrNull()?.takeIf { it.uuid != currentEpisodeUuid }
            }
        }

        if (episode != null) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Auto play selected episode=${episode.uuid} from source=$source")
        }

        return episode?.let { it to source }
    }

    private suspend fun findPodcastEpisodes(
        podcast: Podcast,
        currentEpisodeUuid: String?,
    ): List<PodcastEpisode> {
        // Playback order is chronological regardless of the podcast's visual
        // grouping/sort. Retain the current episode as an anchor even if it has
        // just been marked completed; never replay completed or dismissed items.
        return episodeManager.findEpisodesByPodcastOrdered(podcast)
            .asSequence()
            .filter { episode ->
                (!episode.isArchived && !episode.isFinished) ||
                    episode.uuid == currentEpisodeUuid
            }
            .sortedWith(compareBy<PodcastEpisode> { it.publishedDate }.thenBy { it.uuid })
            .toList()
    }

    private suspend fun findPlaylistEpisodes(
        playlistUuid: String,
        currentEpisodeUuid: String?,
    ): List<PodcastEpisode> {
        return playlistManager
            .getAutoPlayEpisodes(playlistUuid, currentEpisodeUuid)
            .filter { episode ->
                (!episode.isArchived && !episode.isFinished) ||
                    episode.uuid == currentEpisodeUuid
            }
    }

    private suspend fun findDownloadedEpisodes(): List<PodcastEpisode> {
        return episodeManager.findDownloadedEpisodesFlow().first()
    }

    private suspend fun findUserEpisodes(): List<UserEpisode> {
        return cloudFilesManager.sortedCloudFiles.first()
    }

    private suspend fun findStarredEpisodes(): List<PodcastEpisode> {
        return episodeManager.findStarredEpisodes()
    }
}
