package au.com.shiftyjelly.pocketcasts.repositories.migration

import au.com.shiftyjelly.pocketcasts.models.type.EpisodePlayingStatus

data class PodcastAddictEpisodeValues(
    val playingStatus: EpisodePlayingStatus,
    val positionSeconds: Double,
    val favourite: Boolean,
    val lastPlaybackDate: Long?,
)

/** In-memory preview record, never uploaded or logged. */
data class PodcastAddictEpisodeChange(
    val uuid: String,
    val feedUrl: String,
    val enclosure: String,
    val publicationDate: Long,
    val before: PodcastAddictEpisodeValues,
    val after: PodcastAddictEpisodeValues,
)
