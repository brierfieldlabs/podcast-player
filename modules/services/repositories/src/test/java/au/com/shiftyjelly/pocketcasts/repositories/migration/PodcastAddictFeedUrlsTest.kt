package au.com.shiftyjelly.pocketcasts.repositories.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodcastAddictFeedUrlsTest {
    @Test
    fun usesExportedRssAddressesKeyedByPodcastUuid() {
        val exported = mapOf("p1" to " https://example.org/feed.xml ", "p2" to "https://example.org/other.xml")
        assertEquals(mapOf("p1" to "https://example.org/feed.xml"), PodcastAddictFeedUrls.verified(listOf("p1"), exported))
    }

    @Test
    fun incompleteOrInvalidExportDoesNotProduceMissingSubscriptionCandidates() {
        assertNull(PodcastAddictFeedUrls.verified(listOf("p1", "p2"), mapOf("p1" to "https://example.org/feed.xml")))
        assertNull(PodcastAddictFeedUrls.verified(listOf("p1"), mapOf("p1" to "")))
    }
}
