package au.com.shiftyjelly.pocketcasts.repositories.migration

import au.com.shiftyjelly.pocketcasts.models.type.EpisodePlayingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodcastAddictEpisodeStateTest {
    @Test
    fun playedFlagWinsEvenWithSavedPosition() {
        assertEquals(EpisodePlayingStatus.COMPLETED, PodcastAddictEpisodeState.propose(1, 0))
        assertEquals(EpisodePlayingStatus.COMPLETED, PodcastAddictEpisodeState.propose(1, 12500))
    }

    @Test
    fun unplayedFlagWithPositionMeansInProgress() {
        assertEquals(EpisodePlayingStatus.NOT_PLAYED, PodcastAddictEpisodeState.propose(0, 0))
        assertEquals(EpisodePlayingStatus.IN_PROGRESS, PodcastAddictEpisodeState.propose(0, 12500))
    }

    @Test
    fun unknownFlagsAndInvalidPositionsAreNotGuessed() {
        assertNull(PodcastAddictEpisodeState.propose(null, 0))
        assertNull(PodcastAddictEpisodeState.propose(2, 0))
        assertNull(PodcastAddictEpisodeState.propose(0, -1))
        assertNull(PodcastAddictEpisodeState.propose(1, null))
    }
}
