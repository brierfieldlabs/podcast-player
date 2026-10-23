package au.com.shiftyjelly.pocketcasts.models.entity

import au.com.shiftyjelly.pocketcasts.models.type.EpisodesSortType
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastEpisodeSortDefaultTest {
    @Test
    fun `new podcasts display oldest first`() {
        val podcast = Podcast(uuid = "new")

        assertEquals(EpisodesSortType.EPISODES_SORT_BY_DATE_ASC, podcast.effectiveEpisodesSortType)
    }

    @Test
    fun `existing podcasts without manual sort display oldest first`() {
        val podcast = Podcast(uuid = "existing", episodesSortType = EpisodesSortType.EPISODES_SORT_BY_DATE_DESC)

        assertEquals(EpisodesSortType.EPISODES_SORT_BY_DATE_ASC, podcast.effectiveEpisodesSortType)
    }

    @Test
    fun `explicit newest first choice stays newest first`() {
        val podcast = Podcast(
            uuid = "manual",
            episodesSortType = EpisodesSortType.EPISODES_SORT_BY_DATE_DESC,
            episodesSortTypeModified = Date(1_000L),
        )

        assertEquals(EpisodesSortType.EPISODES_SORT_BY_DATE_DESC, podcast.effectiveEpisodesSortType)
    }
}
