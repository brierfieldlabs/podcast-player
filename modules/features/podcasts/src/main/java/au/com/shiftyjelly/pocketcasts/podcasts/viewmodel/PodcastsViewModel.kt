package au.com.shiftyjelly.pocketcasts.podcasts.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.models.entity.Folder
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.FolderItem
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeDownloadStatus
import au.com.shiftyjelly.pocketcasts.models.type.PodcastsSortType
import au.com.shiftyjelly.pocketcasts.models.type.SignInState
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.model.AutoPlaySource
import au.com.shiftyjelly.pocketcasts.preferences.model.BadgeType
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadQueue
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadType
import au.com.shiftyjelly.pocketcasts.repositories.download.eligibleForOfflineDownload
import au.com.shiftyjelly.pocketcasts.repositories.notification.NotificationHelper
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FolderManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.user.UserManager
import com.automattic.eventhorizon.EpisodeRecentlyPlayedSortOptionTooltipDismissedEvent
import com.automattic.eventhorizon.EpisodeRecentlyPlayedSortOptionTooltipShownEvent
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.FolderShownEvent
import com.automattic.eventhorizon.PodcastUnsubscribedEvent
import com.automattic.eventhorizon.PodcastsListReorderedEvent
import com.automattic.eventhorizon.PodcastsListShownEvent
import com.automattic.eventhorizon.PullToRefreshSourceType
import com.automattic.eventhorizon.PulledToRefreshEvent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = PodcastsViewModel.Factory::class)
class PodcastsViewModel @AssistedInject constructor(
    private val podcastManager: PodcastManager,
    private val episodeManager: EpisodeManager,
    private val playbackManager: PlaybackManager,
    private val downloadQueue: DownloadQueue,
    private val folderManager: FolderManager,
    private val settings: Settings,
    private val eventHorizon: EventHorizon,
    private val userManager: UserManager,
    private val notificationHelper: NotificationHelper,
    @Assisted private val folderUuid: String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(UiState(isLoadingItems = true))
    val uiState = _uiState.asStateFlow()

    private val notificationsPermissionState = MutableStateFlow(notificationHelper.hasNotificationsPermission())
    val notificationPromptState = combine(
        notificationsPermissionState,
        settings.notificationsPromptAcknowledged.flow,
    ) { hasPermission, hasShownPermissionBefore ->
        NotificationsPermissionState(
            hasPermission = hasPermission,
            hasShownPromptBefore = hasShownPermissionBefore,
        )
    }

    init {
        viewModelScope.launch {
            observeUiState().collect { _uiState.value = it }
        }
    }

    private fun observeUiState(): Flow<UiState> {
        val podcastsFlow = settings.podcastsSortType.flow
            .flatMapLatest { sortType ->
                when (sortType) {
                    PodcastsSortType.RECENTLY_PLAYED -> podcastManager.observePodcastsBySortedRecentlyPlayed()
                    else -> podcastManager.observePodcastsSortedByLatestEpisode()
                }
            }

        val foldersFlow: Flow<List<FolderItem.Folder>> = folderManager.observeFolders()
            .flatMapLatest { folders ->
                val filteredFolders = if (folderUuid != null) {
                    folders.filter { it.uuid == folderUuid }
                } else {
                    folders
                }
                if (filteredFolders.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val folderPodcasts = filteredFolders.map { folder ->
                        podcastManager
                            .observePodcastsSortedByUserChoice(folder)
                            .map { podcasts -> FolderItem.Folder(folder, podcasts) }
                    }
                    combine(folderPodcasts) { array -> array.toList() }
                }
            }

        val libraryState = combine(
            podcastsFlow,
            foldersFlow,
            settings.podcastsSortType.flow,
            userManager.getSignInState().asFlow(),
        ) { podcasts, folders, sortType, signInState ->
            withContext(Dispatchers.Default) {
                buildUiState(podcasts, folders, sortType, signInState)
            }
        }

        val unfinishedEpisodeCounts: Flow<Map<String, Int>> = combine(
            settings.hidePodcastsWithNoUnfinishedEpisodes.flow,
            settings.sortPodcastsByUnfinishedCount.flow,
        ) { hide, sortByCount -> hide || sortByCount }
            .flatMapLatest { required ->
                if (required) episodeManager.observePodcastUuidToBadgeUnfinished().map { it.orEmpty() } else flowOf(emptyMap())
            }

        return combine(
            libraryState,
            unfinishedEpisodeCounts,
            settings.hidePodcastsWithNoUnfinishedEpisodes.flow,
            settings.sortPodcastsByUnfinishedCount.flow,
            settings.reversePodcastsSort.flow,
        ) { state, counts, hide, sortByCount, reverse ->
            val visibleItems = if (hide) filterPodcastsWithoutUnfinishedEpisodes(state.items, counts) else state.items
            val sortedItems = if (sortByCount) {
                sortLibraryItemsByUnfinishedCount(visibleItems, counts, reverse)
            } else if (reverse) {
                visibleItems.reversed()
            } else {
                visibleItems
            }
            state.copy(
                items = sortedItems,
                hasHiddenPodcasts = hide && visibleItems != state.items,
            )
        }
    }

    private fun buildUiState(
        podcasts: List<Podcast>,
        folders: List<FolderItem.Folder>,
        sortType: PodcastsSortType,
        signInState: SignInState,
    ) = UiState(
        items = when {
            signInState.isNoAccountOrFree -> buildPodcastItems(podcasts, sortType)

            folderUuid == null -> buildHomeFolderItems(podcasts, folders, sortType)

            else -> folders.find { it.uuid == folderUuid }
                ?.podcasts
                ?.map(FolderItem::Podcast)
                .orEmpty()
        },
        folder = folders.find { it.uuid == folderUuid }?.folder,
        isSignedInAsPlusOrPatron = signInState.isSignedInAsPlusOrPatron,
    )

    private fun buildHomeFolderItems(podcasts: List<Podcast>, folders: List<FolderItem>, podcastSortType: PodcastsSortType) = when (podcastSortType) {
        PodcastsSortType.EPISODE_DATE_NEWEST_TO_OLDEST,
        PodcastsSortType.RECENTLY_PLAYED,
        -> {
            val folderUuids = folders.mapTo(mutableSetOf()) { it.uuid }
            val items = mutableListOf<FolderItem>()
            val uuidToFolder = folders.associateByTo(mutableMapOf(), FolderItem::uuid)
            for (podcast in podcasts) {
                if (podcast.folderUuid == null || !folderUuids.contains(podcast.folderUuid)) {
                    items.add(FolderItem.Podcast(podcast))
                } else {
                    // add the folder in the position of the default sorted podcasts
                    val folder = uuidToFolder.remove(podcast.folderUuid)
                    if (folder != null) {
                        items.add(folder)
                    }
                }
            }
            if (uuidToFolder.isNotEmpty()) {
                items.addAll(uuidToFolder.values)
            }
            items
        }

        else -> {
            val folderUuids = folders.map { it.uuid }.toHashSet()
            val items = podcasts
                // add the podcasts not in a folder or if the folder doesn't exist
                .filter { podcast -> podcast.folderUuid == null || !folderUuids.contains(podcast.folderUuid) }
                .map { FolderItem.Podcast(it) }
                .toMutableList<FolderItem>()
                // add the folders
                .apply { addAll(folders) }

            items.sortedWith(podcastSortType.folderComparator)
        }
    }

    private fun buildPodcastItems(podcasts: List<Podcast>, podcastSortType: PodcastsSortType): List<FolderItem> {
        val items = podcasts.map { podcast -> FolderItem.Podcast(podcast) }
        return when (podcastSortType) {
            PodcastsSortType.EPISODE_DATE_NEWEST_TO_OLDEST,
            PodcastsSortType.RECENTLY_PLAYED,
            -> items

            else -> items.sortedWith(podcastSortType.folderComparator)
        }
    }

    val podcastUuidToBadge = settings.podcastBadgeType.flow
        .flatMapLatest { badgeType ->
            when (badgeType) {
                BadgeType.ALL_UNFINISHED -> episodeManager.observePodcastUuidToBadgeUnfinished()
                BadgeType.LATEST_EPISODE -> episodeManager.observePodcastUuidToBadgeLatest()
                else -> flowOf(emptyMap())
            }
        }

    // We only want the current badge type when loading for this flow or else it will rebind the adapter every time the badge changes. We use take(1) for this.
    val layoutChangedFlow = settings.podcastGridLayout.flow
        .combine(settings.podcastBadgeType.flow.take(1), ::Pair)

    val refreshStateFlow = settings.refreshStateFlow

    suspend fun reorderItems(items: List<FolderItem>) {
        if (settings.hidePodcastsWithNoUnfinishedEpisodes.value || settings.sortPodcastsByUnfinishedCount.value || settings.reversePodcastsSort.value) return

        eventHorizon.track(PodcastsListReorderedEvent)
        viewModelScope.launch {
            if (folderUuid == null) {
                settings.podcastsSortType.set(PodcastsSortType.DRAG_DROP, updateModifiedAt = true)
            } else {
                folderManager.updateSortType(folderUuid, PodcastsSortType.DRAG_DROP)
            }
            folderManager.updateSortPosition(items)
        }

        // Wait until the UI state is synchronized with the ordered items.
        // This ensures smoother transitions — items won’t jump around
        // if an intermediate state is emitted during ordering.
        return withContext(Dispatchers.Default) {
            val sortedUuids = items.map(FolderItem::uuid)
            uiState
                .map { state -> state.items.map(FolderItem::uuid) }
                .takeWhile { stateUuids -> stateUuids.size == sortedUuids.size && stateUuids != sortedUuids }
                .collect()
        }
    }

    suspend fun unfinishedEpisodes(podcast: Podcast): List<PodcastEpisode> =
        episodeManager.findEpisodesByPodcastOrderedByPublishDate(podcast)
            .filter { !it.isFinished && !it.isArchived }
            .sortedWith(compareBy<PodcastEpisode> { it.publishedDate }.thenBy { it.uuid })

    suspend fun episodesNotPlayed(podcast: Podcast): List<PodcastEpisode> =
        episodeManager.findEpisodesByPodcastOrdered(podcast)
            .filter { !it.isFinished && !it.isArchived }

    fun addUnfinishedToUpNext(episodes: List<PodcastEpisode>, playNext: Boolean) {
        val limit = settings.getMaxUpNextEpisodes()
        val count = if (playNext) 1 else limit
        val newEpisodes = episodes.filterNot { playbackManager.upNextQueue.contains(it.uuid) }.take(count)
        if (playNext) {
            playbackManager.playEpisodesNext(newEpisodes, SourceView.PODCAST_LIST)
        } else {
            playbackManager.playEpisodesLast(newEpisodes, SourceView.PODCAST_LIST)
        }
    }

    // Queue across podcasts from fewest unfinished first, with each podcast's
    // episodes playing oldest to newest. Preserve the existing listening queue.
    suspend fun queueVisiblePodcastsByUnfinishedCount(): Int {
        val podcasts = uiState.value.items
            .filterIsInstance<FolderItem.Podcast>()
            .map { it.podcast }
        val room = (settings.getMaxUpNextEpisodes() - playbackManager.upNextQueue.queueEpisodes.size)
            .coerceAtLeast(0)
        if (podcasts.isEmpty() || room == 0) return 0
        val byCount = withContext(Dispatchers.IO) {
            podcasts.map { podcast -> podcast to unfinishedEpisodes(podcast) }
                .filter { (_, episodes) -> episodes.isNotEmpty() }
                .sortedWith(
                    compareBy<Pair<Podcast, List<PodcastEpisode>>> { it.second.size }
                        .thenBy { it.first.title },
                )
        }
        val alreadyQueued = playbackManager.upNextQueue.queueEpisodes.mapTo(mutableSetOf()) { it.uuid }
        playbackManager.upNextQueue.currentEpisode?.uuid?.let(alreadyQueued::add)
        val toQueue = buildList {
            for ((_, episodes) in byCount) {
                for (episode in episodes) {
                    if (size >= room) break
                    if (alreadyQueued.add(episode.uuid)) add(episode)
                }
                if (size >= room) break
            }
        }
        if (toQueue.isNotEmpty()) {
            playbackManager.playEpisodesLast(toQueue, SourceView.PODCAST_LIST)
        }
        return toQueue.size
    }

    private fun selectPodcastForAutoPlay(podcastUuid: String) {
        val source = AutoPlaySource.fromId(podcastUuid)
        settings.trackingAutoPlaySource.set(source, updateModifiedAt = false)
        settings.lastAutoPlaySource.set(source, updateModifiedAt = false)
    }

    fun playOldestUnfinished(episodes: List<PodcastEpisode>) {
        val oldest = episodes.firstOrNull() ?: return
        selectPodcastForAutoPlay(oldest.podcastUuid)
        // Start listening now. With continuous playback enabled, the next
        // unfinished episode will follow from this podcast automatically.
        playbackManager.playEpisodes(listOf(oldest), SourceView.PODCAST_LIST, replaceQueue = true)
    }

    fun playUnfinishedOldestFirst(episodes: List<PodcastEpisode>) {
        if (episodes.isEmpty()) return
        selectPodcastForAutoPlay(episodes.first().podcastUuid)
        playbackManager.playEpisodes(episodes.take(settings.getMaxUpNextEpisodes()), SourceView.PODCAST_LIST, replaceQueue = true)
    }

    /**
     * All unfinished, non-dismissed and downloadable podcast episodes.
     * Downloaded, in-flight, failed or HLS-only episodes are not queued again.
     */
    suspend fun episodesForOffline(podcast: Podcast): List<PodcastEpisode> = withContext(Dispatchers.IO) {
        episodeManager.findEpisodesByPodcastOrderedByPublishDate(podcast)
            .eligibleForOfflineDownload()
    }

    fun downloadAllForOffline(episodes: List<PodcastEpisode>) {
        if (episodes.isEmpty()) return
        // Unlike upstream's quick bulk action, this must not silently stop at
        // 100. Reuse WorkManager's Wi-Fi, power and storage safeguards.
        downloadQueue.enqueueAll(
            episodes.map(PodcastEpisode::uuid),
            DownloadType.Automatic(bypassAutoDownloadStatus = true),
            SourceView.PODCAST_LIST,
        )
    }

    fun markEpisodesPlayed(episodes: List<PodcastEpisode>) {
        viewModelScope.launch {
            episodeManager.markAllAsPlayed(episodes, playbackManager, podcastManager)
        }
    }

    suspend fun downloadedEpisodeCount(podcast: Podcast): Int = withContext(Dispatchers.IO) {
        podcastManager.countEpisodesInPodcastWithStatusBlocking(podcast.uuid, EpisodeDownloadStatus.Downloaded)
    }

    fun unsubscribe(podcast: Podcast) {
        podcastManager.unsubscribeAsync(podcast.uuid, SourceView.PODCAST_LIST)
        eventHorizon.track(PodcastUnsubscribedEvent(uuid = podcast.uuid, source = SourceView.PODCAST_LIST.analyticsValue))
    }

    fun refreshPodcasts() {
        eventHorizon.track(
            PulledToRefreshEvent(
                source = PullToRefreshSourceType.PodcastsList,
            ),
        )
        podcastManager.refreshPodcasts("Pull down")
    }

    fun updateNotificationsPermissionState() {
        notificationsPermissionState.value = notificationHelper.hasNotificationsPermission()
    }

    fun updateFolderSort(uuid: String, podcastsSortType: PodcastsSortType) {
        viewModelScope.launch {
            folderManager.updateSortType(folderUuid = uuid, podcastsSortType = podcastsSortType)
        }
    }

    fun trackScreenShown() {
        if (folderUuid != null) {
            trackFolderShown(folderUuid)
        } else {
            trackPodcastsListShown()
        }
    }

    private fun trackPodcastsListShown() {
        viewModelScope.launch {
            eventHorizon.track(
                PodcastsListShownEvent(
                    numberOfFolders = folderManager.countFolders().toLong(),
                    numberOfPodcasts = podcastManager.countSubscribed().toLong(),
                    badgeType = settings.podcastBadgeType.value.analyticsValue,
                    layout = settings.podcastGridLayout.value.analyticsValue,
                    sortOrder = settings.podcastsSortType.value.analyticsValue,
                ),
            )
        }
    }

    private fun trackFolderShown(folderUuid: String) {
        viewModelScope.launch {
            eventHorizon.track(
                FolderShownEvent(
                    sortOrder = (folderManager.findByUuid(folderUuid)?.podcastsSortType ?: PodcastsSortType.DATE_ADDED_NEWEST_TO_OLDEST).analyticsValue,
                    numberOfPodcasts = folderManager.findFolderPodcastsSorted(folderUuid).size.toLong(),
                ),
            )
        }
    }

    fun shouldShowTooltip() = settings.showPodcastsRecentlyPlayedSortOrderTooltip.value

    fun onTooltipShown() {
        eventHorizon.track(EpisodeRecentlyPlayedSortOptionTooltipShownEvent)
    }

    fun onTooltipClosed() {
        settings.showPodcastsRecentlyPlayedSortOrderTooltip.set(false, updateModifiedAt = false)
        eventHorizon.track(EpisodeRecentlyPlayedSortOptionTooltipDismissedEvent)
    }

    data class UiState(
        val isLoadingItems: Boolean = false,
        val items: List<FolderItem> = emptyList(),
        val folder: Folder? = null,
        val isSignedInAsPlusOrPatron: Boolean = false,
        val hasHiddenPodcasts: Boolean = false,
    )

    @AssistedFactory
    interface Factory {
        fun create(folderUuid: String?): PodcastsViewModel
    }

    data class NotificationsPermissionState(
        val hasPermission: Boolean,
        val hasShownPromptBefore: Boolean,
    )
}
