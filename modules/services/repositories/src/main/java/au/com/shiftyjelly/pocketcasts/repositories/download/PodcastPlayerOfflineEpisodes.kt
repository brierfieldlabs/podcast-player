package au.com.shiftyjelly.pocketcasts.repositories.download

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode

/**
 * All unfinished episodes eligible for an explicit bulk offline download.
 *
 * This deliberately does NOT apply the automatic-download opt-out setting:
 * the user has explicitly requested these episodes. WorkManager still applies
 * the configured Wi-Fi, charging and storage constraints when they are queued.
 */
fun List<PodcastEpisode>.eligibleForOfflineDownload(): List<PodcastEpisode> =
    asSequence()
        .filterNot {
            it.isFinished || it.isArchived || it.isDownloaded || it.isDownloading ||
                it.isDownloadPending || it.isHlsOnly || it.isDownloadFailure
        }
        .sortedWith(compareBy<PodcastEpisode> { it.publishedDate }.thenBy { it.uuid })
        .toList()
