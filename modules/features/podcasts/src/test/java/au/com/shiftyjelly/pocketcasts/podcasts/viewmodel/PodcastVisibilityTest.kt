package au.com.shiftyjelly.pocketcasts.podcasts.viewmodel

import au.com.shiftyjelly.pocketcasts.models.entity.Folder
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.to.FolderItem
import au.com.shiftyjelly.pocketcasts.models.type.PodcastsSortType
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastVisibilityTest {
    private val unfinished = Podcast(uuid = "unfinished", isSubscribed = true)
    private val inProgress = Podcast(uuid = "in-progress", isSubscribed = true)
    private val caughtUp = Podcast(uuid = "caught-up", isSubscribed = true)

    private val folder = Folder(
        uuid = "folder",
        name = "Folder",
        color = 0,
        addedDate = Date(0),
        sortPosition = 0,
        podcastsSortType = PodcastsSortType.DRAG_DROP,
        deleted = false,
        syncModified = 0L,
    )

    @Test
    fun sortsByUnfinishedCountIncludingFolderTotals() {
        val items = listOf(
            FolderItem.Podcast(unfinished),
            FolderItem.Folder(folder, listOf(caughtUp, inProgress)),
            FolderItem.Podcast(inProgress),
        )
        val sorted = sortLibraryItemsByUnfinishedCount(
            items,
            mapOf("unfinished" to 3, "caught-up" to 4, "in-progress" to 1),
            reverse = false,
        )
        assertEquals(listOf("folder", "unfinished", "in-progress"), sorted.map { it.uuid })
    }

    @Test
    fun reverseUnfinishedSortShowsSmallestCountsFirst() {
        val items = listOf(FolderItem.Podcast(unfinished), FolderItem.Podcast(caughtUp))
        val sorted = sortLibraryItemsByUnfinishedCount(
            items,
            mapOf("unfinished" to 12, "caught-up" to 2),
            reverse = true,
        )
        assertEquals(listOf("caught-up", "unfinished"), sorted.map { it.uuid })
    }
}
