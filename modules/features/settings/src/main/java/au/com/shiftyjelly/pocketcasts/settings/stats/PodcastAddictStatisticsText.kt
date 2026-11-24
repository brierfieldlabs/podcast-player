package au.com.shiftyjelly.pocketcasts.settings.stats

import android.content.res.Resources
import au.com.shiftyjelly.pocketcasts.repositories.migration.PodcastAddictBackupInspector
import au.com.shiftyjelly.pocketcasts.localization.R as LR

internal fun PodcastAddictBackupInspector.SourceStatistics.summary(resources: Resources): String =
    resources.getString(
        LR.string.podcast_player_backup_preview_stats_totals,
        days,
        wallListeningMs / 60000,
        contentListeningMs / 60000,
        speedSavedMs / 60000,
        silenceSavedMs / 60000,
    ) + "\n" + resources.getString(
        LR.string.podcast_player_backup_stats_additional,
        episodeDayCount,
        introSavedMs / 60000,
        outroSavedMs / 60000,
        forwardSavedMs / 60000,
        adsSavedMs / 60000,
        mutedChapterSavedMs / 60000,
        liveStreamMs / 60000,
    )
