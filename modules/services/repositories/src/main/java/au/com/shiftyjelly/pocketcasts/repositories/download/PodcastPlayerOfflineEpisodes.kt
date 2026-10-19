package au.com.shiftyjelly.pocketcasts.repositories.download

import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager

/**
 * All unfinished episodes eligible for an explicit bulk offline download.
 *
 * This deliberately does NOT apply the automatic-download opt-out setting:
 * the user has explicitly requested these episodes. WorkManager still applies
 * the configured Wi-Fi, charging and storage constraints when they are queued.
 */
fun List<PodcastEpisode>.eligibleForOfflineDownload(): List<PodcastEpisode> = asSequence()
    .filterNot {
        it.isFinished || it.isArchived || it.isDownloaded || it.isDownloading ||
            it.isDownloadPending || it.isHlsOnly || it.isDownloadFailure
    }
    .sortedWith(compareBy<PodcastEpisode> { it.publishedDate }.thenBy { it.uuid })
    .toList()

/**
 * Revalidate a confirmed bulk-download selection against fresh episode state.
 * A dialog can stay open while episodes finish, are dismissed or begin
 * downloading. Never enqueue those stale entries, or newly arrived episodes
 * which were not included in the user's confirmation.
 */
suspend fun EpisodeManager.confirmedOfflineDownloadUuids(
    podcast: Podcast,
    confirmedUuids: Set<String>,
): List<String> {
    if (!podcast.isSubscribed || confirmedUuids.isEmpty()) return emptyList()
    return findEpisodesByPodcastOrderedByPublishDate(podcast)
        .eligibleForOfflineDownload()
        .asSequence()
        .map(PodcastEpisode::uuid)
        .filter { it in confirmedUuids }
        .toList()
}
