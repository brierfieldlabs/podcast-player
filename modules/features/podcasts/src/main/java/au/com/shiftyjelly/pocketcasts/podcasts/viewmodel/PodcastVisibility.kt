package au.com.shiftyjelly.pocketcasts.podcasts.viewmodel

import au.com.shiftyjelly.pocketcasts.models.to.FolderItem

internal fun sortLibraryItemsByUnfinishedCount(
    items: List<FolderItem>,
    unfinishedEpisodeCounts: Map<String, Int>,
    reverse: Boolean,
): List<FolderItem> {
    val sorted = items.sortedWith(
        compareByDescending<FolderItem> { item ->
            when (item) {
                is FolderItem.Podcast -> unfinishedEpisodeCounts[item.uuid] ?: 0
                is FolderItem.Folder -> item.podcasts.sumOf { unfinishedEpisodeCounts[it.uuid] ?: 0 }
            }
        }.thenBy { it.title.lowercase() },
    )
    return if (reverse) sorted.reversed() else sorted
}
