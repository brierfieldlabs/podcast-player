package au.com.shiftyjelly.pocketcasts.repositories.migration

import au.com.shiftyjelly.pocketcasts.models.type.EpisodePlayingStatus

/**
 * Candidate mapping for preview only. The current source flag wins over
 * historical completion records, which may predate a manual reset.
 * A played episode can still retain a non-zero playback position.
 */
internal object PodcastAddictEpisodeState {
    fun propose(seenStatus: Int?, positionMs: Long?): EpisodePlayingStatus? = when {
        positionMs == null || positionMs < 0 -> null
        seenStatus == 1 -> EpisodePlayingStatus.COMPLETED
        seenStatus == 0 && positionMs > 0 -> EpisodePlayingStatus.IN_PROGRESS
        seenStatus == 0 -> EpisodePlayingStatus.NOT_PLAYED
        else -> null
    }
}
