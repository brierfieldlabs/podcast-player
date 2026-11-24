package au.com.shiftyjelly.pocketcasts.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.to.RefreshState
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.migration.PodcastAddictBackupInspector
import au.com.shiftyjelly.pocketcasts.repositories.migration.PodcastAddictEpisodeImporter
import au.com.shiftyjelly.pocketcasts.repositories.migration.PodcastAddictFeedUrls
import au.com.shiftyjelly.pocketcasts.repositories.migration.PodcastAddictStatisticsStore
import au.com.shiftyjelly.pocketcasts.repositories.opml.OpmlImportTask
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncManager
import au.com.shiftyjelly.pocketcasts.servers.ServiceManager
import au.com.shiftyjelly.pocketcasts.settings.stats.summary
import au.com.shiftyjelly.pocketcasts.settings.viewmodel.ExportSettingsViewModel
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.utils.Network
import au.com.shiftyjelly.pocketcasts.views.extensions.findToolbar
import au.com.shiftyjelly.pocketcasts.views.extensions.setup
import au.com.shiftyjelly.pocketcasts.views.helper.NavigationIcon.BackArrow
import au.com.shiftyjelly.pocketcasts.views.helper.OpmlExporter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@AndroidEntryPoint
class ExportSettingsFragment : PreferenceFragmentCompat() {

    @Inject lateinit var serviceManager: ServiceManager

    @Inject lateinit var settings: Settings

    @Inject lateinit var podcastManager: PodcastManager

    @Inject lateinit var episodeManager: EpisodeManager

    @Inject lateinit var podcastAddictInspector: PodcastAddictBackupInspector

    @Inject lateinit var podcastAddictImporter: PodcastAddictEpisodeImporter

    @Inject lateinit var podcastAddictStatisticsStore: PodcastAddictStatisticsStore

    @Inject lateinit var playbackManager: PlaybackManager

    @Inject lateinit var theme: Theme

    @Inject lateinit var syncManager: SyncManager

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private val viewModel by viewModels<ExportSettingsViewModel>()
    private var exporter: OpmlExporter? = null

    private val importOpmlFilePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val activity = activity ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data?.data ?: return@registerForActivityResult
            OpmlImportTask.run(data, activity)
        }
    }

    private val podcastAddictBackupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && view != null) showPodcastAddictPreview(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.onCreate()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findToolbar().setup(title = getString(LR.string.settings_title_import_export), navigationIcon = BackArrow, activity = activity, theme = theme)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.bottomInset.collect {
                    view.updatePadding(bottom = it)
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_export, rootKey)

        val activity = activity ?: return

        findPreference<Preference>("importPodcasts")?.setOnPreferenceClickListener {
            showOpmlFilePicker()
            viewModel.onImportSelectFile()
            true
        }

        findPreference<Preference>("previewPodcastAddictBackup")?.setOnPreferenceClickListener {
            podcastAddictBackupPicker.launch(arrayOf("*/*"))
            true
        }

        findPreference<EditTextPreference>("importPodcastsByUrl")?.apply {
            setOnPreferenceClickListener {
                viewModel.onImportByUrlClicked()
                false
            }
            setOnPreferenceChangeListener { _, newValue ->
                val url = newValue.toString().toHttpUrlOrNull()
                if (url != null) {
                    OpmlImportTask.run(url, activity)
                }
                false
            }
        }

        findPreference<Preference>("exportSendEmail")?.setOnPreferenceClickListener {
            viewModel.onExportByEmail()
            exporter = OpmlExporter(
                fragment = this@ExportSettingsFragment,
                serviceManager = serviceManager,
                podcastManager = podcastManager,
                syncManager = syncManager,
                context = activity,
                eventHorizon = viewModel.eventHorizon,
                applicationScope = applicationScope,
            ).apply {
                sendEmail()
            }
            true
        }

        findPreference<Preference>("exportSaveFile")?.setOnPreferenceClickListener {
            viewModel.onExportFile()
            exporter = OpmlExporter(
                fragment = this@ExportSettingsFragment,
                serviceManager = serviceManager,
                podcastManager = podcastManager,
                syncManager = syncManager,
                context = activity,
                eventHorizon = viewModel.eventHorizon,
                applicationScope = applicationScope,
            ).apply {
                saveFile()
            }
            true
        }
    }

    private fun showPodcastAddictPreview(uri: Uri, afterSubscriptions: Boolean = false) {
        val progress = AlertDialog.Builder(requireContext())
            .setTitle(LR.string.podcast_player_backup_preview_title)
            .setMessage(LR.string.podcast_player_backup_preview_busy)
            .setCancelable(false)
            .create()
        progress.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                val subscribedFeeds = withContext(Dispatchers.IO) {
                    val queued = playbackManager.upNextQueue.allEpisodes.map { it.uuid }.toMutableSet()
                    playbackManager.getCurrentEpisode()?.uuid?.let { queued.add(it) }
                    val podcasts = podcastManager.findSubscribedNoOrder()
                    val urls = PodcastAddictFeedUrls.resolve(serviceManager, podcasts.map { it.uuid })
                        ?: return@withContext null
                    podcasts.map { podcast ->
                        PodcastAddictBackupInspector.LocalFeed(
                            uuid = podcast.uuid,
                            url = urls.getValue(podcast.uuid),
                            episodes = episodeManager.findEpisodesByPodcastOrderedByPublishDate(podcast).map { episode ->
                                PodcastAddictBackupInspector.LocalEpisode(
                                    uuid = episode.uuid,
                                    enclosure = episode.downloadUrl,
                                    publicationDate = episode.publishedDate.time,
                                    positionSeconds = episode.playedUpTo,
                                    favourite = episode.isStarred,
                                    playingStatus = episode.playingStatus,
                                    lastPlaybackDate = episode.lastPlaybackInteraction,
                                    durationSeconds = episode.duration,
                                    queued = episode.uuid in queued,
                                )
                            },
                        )
                    }
                }
                if (subscribedFeeds == null) {
                    PodcastAddictBackupInspector.Result.Rejected(PodcastAddictBackupInspector.Reason.FeedLookupUnavailable)
                } else {
                    podcastAddictInspector.inspect(uri, subscribedFeeds)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Invalid provider data or unavailable local subscriptions must
                // never crash Settings or start a fallback import.
                PodcastAddictBackupInspector.Result.Rejected(
                    PodcastAddictBackupInspector.Reason.CannotOpenBackup,
                )
            } finally {
                progress.dismiss()
            }
            if (!isAdded || view == null) return@launch
            val message = when (result) {
                is PodcastAddictBackupInspector.Result.Readable -> {
                    getString(
                        LR.string.podcast_player_backup_preview_results,
                        result.podcastCount,
                        result.subscribedFeedCount,
                        result.episodeCount,
                        result.listeningHistoryCount,
                        result.episodesWithResumePosition,
                        result.localSubscribedFeedCount,
                        result.exactMatchedLocalFeeds,
                        result.ambiguousFeedMatches,
                        result.missingSubscriptions,
                        result.destinationOnlySubscriptions,
                    ) + "\n\n" + getString(
                        LR.string.podcast_player_backup_preview_episodes,
                        result.episodes.exact,
                        result.episodes.ambiguous,
                        result.episodes.unmatched,
                        result.episodes.resumeDifferences,
                        result.episodes.favouriteDifferences,
                        result.episodes.completionEvidence,
                        result.episodes.markPlayed,
                        result.episodes.markUnplayed,
                        result.episodes.markInProgress,
                        result.episodes.unresolvedState,
                        result.episodes.sourceEpisodesWithoutExactMatch,
                        result.episodes.changes.size,
                        result.episodes.historyDifferences,
                        result.episodes.queuedDifferences,
                    ) + "\n" + (
                        result.statistics?.summary(resources)
                            ?: getString(LR.string.podcast_player_backup_preview_stats_unavailable)
                        ) + "\n\n" + getString(LR.string.podcast_player_backup_preview_disclaimer)
                }

                is PodcastAddictBackupInspector.Result.Rejected -> when (result.reason) {
                    PodcastAddictBackupInspector.Reason.MissingFeedUrls ->
                        getString(LR.string.podcast_player_backup_preview_no_feeds)

                    PodcastAddictBackupInspector.Reason.FeedLookupUnavailable ->
                        getString(LR.string.podcast_player_backup_feed_lookup_failed)

                    PodcastAddictBackupInspector.Reason.BackupTooLarge ->
                        getString(LR.string.podcast_player_backup_preview_too_large)

                    else -> getString(LR.string.podcast_player_backup_preview_rejected)
                }
            }
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(LR.string.podcast_player_backup_preview_title)
                .setMessage(
                    if (afterSubscriptions && result is PodcastAddictBackupInspector.Result.Readable) {
                        getString(LR.string.podcast_player_backup_after_feeds, result.missingSubscriptions) + "\n\n" + message
                    } else if (afterSubscriptions) {
                        getString(LR.string.podcast_player_backup_import_feeds_partial) + "\n\n" + message
                    } else {
                        message
                    },
                )
                .setNegativeButton(LR.string.cancel, null)
            if (result is PodcastAddictBackupInspector.Result.Readable) {
                val addFeeds = !afterSubscriptions && result.missingFeedUrls.isNotEmpty()
                if (addFeeds || result.episodes.changes.isNotEmpty() || result.statistics != null) {
                    dialog.setPositiveButton(LR.string.podcast_player_backup_import_all) { _, _ ->
                        importPodcastAddictBackup(uri, result, addFeeds)
                    }
                }
            }
            dialog.show()
        }
    }

    private fun importPodcastAddictBackup(
        uri: Uri,
        result: PodcastAddictBackupInspector.Result.Readable,
        addFeeds: Boolean,
    ) {
        val context = requireContext().applicationContext
        if (addFeeds && !Network.isConnected(context)) {
            showPodcastAddictMessage(getString(LR.string.podcast_player_backup_online_required))
            return
        }
        val progress = AlertDialog.Builder(requireContext())
            .setTitle(LR.string.podcast_player_backup_import_all)
            .setMessage(LR.string.podcast_player_backup_import_starting)
            .setCancelable(false)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (OpmlImportTask.workInfos(context).first().any { !it.state.isFinished }) {
                    progress.dismiss()
                    showPodcastAddictMessage(getString(LR.string.podcast_player_backup_import_busy))
                    return@launch
                }
                if (addFeeds) {
                    progress.setMessage(getString(LR.string.podcast_player_backup_import_feeds_busy))
                    val succeeded = OpmlImportTask.runPodcastAddictMissingFeeds(result.missingFeedUrls, context)
                    progress.dismiss()
                    if (succeeded) {
                        // Newly acquired episodes were not part of the first preview.
                        // Review the updated matches before any episode overwrite.
                        if (isAdded && view != null) showPodcastAddictPreview(uri, afterSubscriptions = true)
                    } else {
                        showPodcastAddictMessage(getString(LR.string.podcast_player_backup_import_feeds_partial))
                    }
                    return@launch
                }

                var updated = 0
                if (result.episodes.changes.isNotEmpty()) {
                    progress.setMessage(getString(LR.string.podcast_player_backup_wait_offline))
                    val ready = withTimeoutOrNull(120_000L) {
                        while (
                            Network.isConnected(context) ||
                            playbackManager.isPlaying() ||
                            settings.getRefreshState() == RefreshState.Refreshing
                        ) {
                            delay(500)
                        }
                        true
                    } ?: false
                    if (!ready) {
                        progress.dismiss()
                        showPodcastAddictMessage(getString(LR.string.podcast_player_backup_offline_timeout))
                        return@launch
                    }
                    progress.setMessage(getString(LR.string.podcast_player_backup_apply_busy))
                    updated = podcastAddictImporter.applyReviewedChanges(
                        result.episodes.changes,
                        result.reviewedFeedUrls,
                    ).changed
                }

                val statistics = result.statistics
                val statisticsStatus = if (statistics != null) {
                    try {
                        podcastAddictStatisticsStore.save(statistics)
                        LR.string.podcast_player_backup_stats_saved
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        LR.string.podcast_player_backup_stats_save_failed
                    }
                } else {
                    LR.string.podcast_player_backup_preview_stats_unavailable
                }
                val message = getString(
                    LR.string.podcast_player_backup_import_finished,
                    updated,
                    result.missingSubscriptions,
                    result.episodes.queuedDifferences,
                ) + "\n\n" + getString(statisticsStatus)
                progress.dismiss()
                showPodcastAddictMessage(message)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val failure = if (addFeeds) {
                    LR.string.podcast_player_backup_import_feeds_partial
                } else {
                    LR.string.podcast_player_backup_apply_failed
                }
                progress.dismiss()
                if (isAdded && view != null) showPodcastAddictMessage(getString(failure))
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun showPodcastAddictMessage(message: String) {
        if (!isAdded || view == null) return
        AlertDialog.Builder(requireContext())
            .setTitle(LR.string.podcast_player_backup_preview_title)
            .setMessage(message)
            .setPositiveButton(LR.string.done, null)
            .show()
    }

    private fun showOpmlFilePicker() {
        val intent = Intent().apply {
            type = "*/*"
            action = Intent.ACTION_GET_CONTENT
        }
        importOpmlFilePickerLauncher.launch(Intent.createChooser(intent, getString(LR.string.settings_import_choose_file)))
    }

    override fun onPause() {
        super.onPause()
        viewModel.onFragmentPause(activity?.isChangingConfigurations)
    }
}
