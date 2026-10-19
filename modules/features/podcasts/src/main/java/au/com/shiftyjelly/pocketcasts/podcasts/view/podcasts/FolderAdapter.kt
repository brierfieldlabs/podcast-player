package au.com.shiftyjelly.pocketcasts.podcasts.view.podcasts

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.compose.extensions.setContentWithViewCompositionStrategy
import au.com.shiftyjelly.pocketcasts.compose.images.CountBadge
import au.com.shiftyjelly.pocketcasts.compose.images.CountBadgeStyle
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.to.FolderItem
import au.com.shiftyjelly.pocketcasts.podcasts.R
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.model.BadgeType
import au.com.shiftyjelly.pocketcasts.preferences.model.PodcastGridLayoutType
import au.com.shiftyjelly.pocketcasts.repositories.images.PocketCastsImageRequestFactory
import au.com.shiftyjelly.pocketcasts.repositories.images.PocketCastsImageRequestFactory.PlaceholderType
import au.com.shiftyjelly.pocketcasts.ui.extensions.getThemeColor
import au.com.shiftyjelly.pocketcasts.ui.extensions.themed
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.utils.extensions.dpToPx
import au.com.shiftyjelly.pocketcasts.views.adapter.FolderItemDiffCallback
import au.com.shiftyjelly.pocketcasts.views.adapter.LockableListAdapter
import au.com.shiftyjelly.pocketcasts.views.extensions.hide
import au.com.shiftyjelly.pocketcasts.views.extensions.inflate
import au.com.shiftyjelly.pocketcasts.views.extensions.show
import au.com.shiftyjelly.pocketcasts.views.extensions.showIf
import coil3.imageLoader
import coil3.request.target
import kotlin.math.abs
import kotlin.math.min

class FolderAdapter(
    val clickListener: ClickListener,
    val settings: Settings,
    val context: Context,
    val theme: Theme,
) : LockableListAdapter<FolderItem, RecyclerView.ViewHolder>(FolderItemDiffCallback()) {

    var badgeType = BadgeType.OFF
        private set

    fun setBadgeType(value: BadgeType) {
        if (badgeType == value) return
        badgeType = value
        // Changing the badge mode needs only a badge rebind. Recreating all
        // artwork requests and RecyclerView holders causes visible stutter.
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, badgePayload)
    }

    private companion object {
        val badgePayload = Any()
    }

    private val imageRequestFactory = PocketCastsImageRequestFactory(context, placeholderType = PlaceholderType.None).themed()
    private var podcastUuidToBadge: Map<String, Int> = emptyMap()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).adapterId
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is FolderItem.Podcast -> AdapterViewTypeIds.PODCAST_ID
            is FolderItem.Folder -> AdapterViewTypeIds.FOLDER_ID
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            AdapterViewTypeIds.PODCAST_ID -> {
                val isLayoutListView = settings.podcastGridLayout.value == PodcastGridLayoutType.LIST_VIEW
                val layoutId = if (isLayoutListView) R.layout.adapter_podcast_list else R.layout.adapter_podcast_grid
                val view = parent.inflate(layoutId, attachToThis = false)
                val podcastGridLayout = settings.podcastGridLayout.value
                PodcastViewHolder(
                    view,
                    imageRequestFactory.copy(cornerRadius = 4, showErrorPlaceholder = isLayoutListView),
                    podcastGridLayout,
                    theme,
                )
            }

            AdapterViewTypeIds.FOLDER_ID -> {
                val podcastsLayout = settings.podcastGridLayout.value
                FolderViewHolder(
                    composeView = ComposeView(parent.context),
                    theme = theme,
                    podcastsLayout = podcastsLayout,
                    onFolderClick = { clickListener.onFolderClick(it.uuid, isUserInitiated = true) },
                    podcastGridLayout = podcastsLayout,
                )
            }

            else -> throw Exception("Unknown view type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is FolderItem.Podcast -> (holder as PodcastViewHolder).bind(item.podcast, badgeType, podcastUuidToBadge, clickListener)
            is FolderItem.Folder -> (holder as FolderViewHolder).bind(item.folder, item.podcasts, badgeType, podcastUuidToBadge)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(badgePayload)) {
            when (val item = getItem(position)) {
                is FolderItem.Podcast -> (holder as PodcastViewHolder).bindBadge(
                    item.podcast,
                    badgeType,
                    podcastUuidToBadge,
                )

                is FolderItem.Folder -> (holder as FolderViewHolder).bind(
                    item.folder,
                    item.podcasts,
                    badgeType,
                    podcastUuidToBadge,
                )
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is PodcastViewHolder -> {
                holder.clearBoundPodcast()
                if (context is FragmentActivity && !context.isDestroyed) {
                    holder.podcastThumbnail.setImageDrawable(null)
                }
            }
        }
    }

    fun setBadges(podcastUuidToBadge: Map<String, Int>) {
        val previousCounts = this.podcastUuidToBadge
        if (previousCounts == podcastUuidToBadge) return

        this.podcastUuidToBadge = podcastUuidToBadge
        applyBadgesToExistingPodcasts()
        for (position in 0 until itemCount) {
            val changed = when (val item = getItem(position)) {
                is FolderItem.Podcast -> previousCounts[item.uuid] != podcastUuidToBadge[item.uuid]

                is FolderItem.Folder -> item.podcasts.any { podcast ->
                    previousCounts[podcast.uuid] != podcastUuidToBadge[podcast.uuid]
                }
            }
            if (changed) {
                notifyItemChanged(position, badgePayload)
            }
        }
    }

    fun setFolderItems(items: List<FolderItem>) {
        applyBadgesToNewPodcasts(items.mapNotNull { (it as? FolderItem.Podcast)?.podcast })
        submitList(items)
    }

    private fun applyBadgesToExistingPodcasts() {
        for (index in 0 until itemCount) {
            val folderItem = getItem(index) as? FolderItem.Podcast ?: continue
            val podcast = folderItem.podcast
            val badge = podcastUuidToBadge[podcast.uuid]
            if (badge != null && badge != podcast.unplayedEpisodeCount) {
                podcast.unplayedEpisodeCount = badge
            }
        }
    }

    private fun applyBadgesToNewPodcasts(podcasts: List<Podcast>) {
        podcasts.forEach { podcast ->
            podcastUuidToBadge[podcast.uuid]?.let { podcast.unplayedEpisodeCount = it }
        }
    }

    class PodcastViewHolder(
        val view: View,
        private val imageRequestFactory: PocketCastsImageRequestFactory,
        podcastGridLayout: PodcastGridLayoutType,
        val theme: Theme,
    ) : RecyclerView.ViewHolder(view),
        DraggableHolder {

        val button: View? = view.findViewById(R.id.button)
        val podcastThumbnail: ImageView = view.findViewById(R.id.podcast_artwork)
        val podcastCardView = view.findViewById<CardView>(R.id.podcast_card_view)
        val podcastTitle: TextView = view.findViewById<TextView>(R.id.library_podcast_title).apply {
            textSize = if (podcastGridLayout == PodcastGridLayoutType.SMALL_ARTWORK) 10f else 14f
        }
        val podcastOverlay: View? = view.findViewById(R.id.podcast_artwork_overlay)
        val author: TextView? = view.findViewById(R.id.podcast_author)
        val cardElevation: Float = 1.dpToPx(view.resources.displayMetrics).toFloat()
        val cardCornerRadius: Float = 4.dpToPx(view.resources.displayMetrics).toFloat()
        val isListLayout: Boolean = podcastGridLayout == PodcastGridLayoutType.LIST_VIEW
        val unplayedCountBadgeView = view.findViewById<ComposeView>(R.id.badge_view)
        private val displayedBadgeCount = mutableIntStateOf(0)
        private val displayedBadgeType = mutableStateOf(BadgeType.OFF)
        private var boundPodcastUuid: String? = null

        fun clearBoundPodcast() {
            boundPodcastUuid = null
        }

        init {
            unplayedCountBadgeView.setContentWithViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool) {
                AppTheme(theme.activeTheme) {
                    CountBadge(
                        count = displayedBadgeCount.intValue,
                        style = when {
                            displayedBadgeType.value == BadgeType.LATEST_EPISODE -> CountBadgeStyle.Small
                            isListLayout -> CountBadgeStyle.Big
                            else -> CountBadgeStyle.Medium
                        },
                        modifier = if (isListLayout) {
                            Modifier
                        } else {
                            Modifier.wrapContentSize(align = Alignment.TopEnd)
                        },
                    )
                }
            }
        }

        fun bind(podcast: Podcast, badgeType: BadgeType, podcastUuidToBadge: Map<String, Int>, clickListener: ClickListener) {
            boundPodcastUuid = podcast.uuid
            button?.setOnClickListener { clickListener.onPodcastClick(podcast, itemView) }
            button?.setOnLongClickListener {
                clickListener.onPodcastLongClick(podcast, itemView)
                true
            }
            podcastCardView?.setOnClickListener { clickListener.onPodcastClick(podcast, itemView) }
            podcastCardView?.setOnLongClickListener {
                clickListener.onPodcastLongClick(podcast, itemView)
                true
            }
            podcastTitle.text = podcast.title
            podcastTitle.showIf(isListLayout)
            author?.text = podcast.author

            bindBadge(podcast, badgeType, podcastUuidToBadge)
            podcastCardView?.cardElevation = if (isListLayout) cardElevation else 0f
            podcastCardView?.radius = if (isListLayout) cardCornerRadius else 0f
            podcastCardView?.setCardBackgroundColor(Color.TRANSPARENT)
            podcastOverlay?.hide()

            // Load image with custom listener to handle error state
            val imageRequest = imageRequestFactory
                .create(podcast)
                .newBuilder()
                .listener(
                    onSuccess = { _, _ ->
                        if (boundPodcastUuid == podcast.uuid && !isListLayout) {
                            podcastTitle.hide()
                            podcastOverlay?.hide()
                        }
                    },
                    onError = { _, _ ->
                        if (boundPodcastUuid == podcast.uuid && !isListLayout) {
                            // Show the fallback podcast title and artwork color
                            podcastTitle.show()
                            podcastOverlay?.show()
                            // Set color background using UUID hash so the same podcast gets the same color each time
                            val colorIndex = abs(podcast.uuid.hashCode()) % Theme.folderColors.size
                            val folderColorAttr = Theme.folderColors.getOrElse(colorIndex) { Theme.folderColors.first() }
                            val folderColor = view.context.getThemeColor(folderColorAttr)
                            podcastCardView?.setCardBackgroundColor(folderColor)
                        }
                    },
                )
                .target(podcastThumbnail)
                .build()

            view.context.imageLoader.enqueue(imageRequest)
        }

        fun bindBadge(podcast: Podcast, badgeType: BadgeType, podcastUuidToBadge: Map<String, Int>) {
            val unplayedEpisodeCount = podcastUuidToBadge[podcast.uuid] ?: 0
            val count = when (badgeType) {
                BadgeType.OFF -> 0
                BadgeType.ALL_UNFINISHED -> unplayedEpisodeCount
                BadgeType.LATEST_EPISODE -> min(1, unplayedEpisodeCount)
            }
            displayedBadgeCount.intValue = count
            displayedBadgeType.value = badgeType
            unplayedCountBadgeView.showIf(count > 0)

            val badgeCountMessage = if (badgeType == BadgeType.OFF) "" else "$unplayedEpisodeCount new episodes. "
            val contentDescription = "${podcast.title}. $badgeCountMessage Open podcast."
            button?.contentDescription = contentDescription
            podcastCardView?.contentDescription = contentDescription
        }

        override fun onStartDragging() = Unit

        override fun onFinishDragging() = Unit
    }

    interface ClickListener {
        fun onPodcastClick(podcast: Podcast, view: View)
        fun onPodcastLongClick(podcast: Podcast, view: View)
        fun onFolderClick(folderUuid: String, isUserInitiated: Boolean)
    }
}
