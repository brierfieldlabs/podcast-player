# Podcast Player v1.2.0 phone acceptance

**Status:** Awaiting user testing. This is a trial APK, not approval to release publicly,
merge unverified changes, or migrate Podcast Addict history.

The existing **Podcast Addict** installation remains the daily player. Do not
uninstall or change it. Do not import any backups or connect its history to
Podcast Player during this test.

## Before installing

- Confirm the APK says **Podcast Player v1.2.0** and is provided by the
  Brierfield Labs GitHub build of the approved development commit.
- Confirm Android offers an **Update** for the existing Podcast Player/PodcastGPT
  installation, not a replacement that requires uninstalling or clearing data.
  If it demands an uninstall, **stop** and report it. Existing local player data
  must be retained.
- Keep normal phone storage and network access available for the initial trial.
  Do not assume downloads have completed merely because they are queued.

## Acceptance checklist

- [ ] Opens without a lengthy white screen; scrolls the dense podcast grid
      smoothly. Fast scrolling shows the correct artwork and unplayed badges.
- [ ] Shows **Playlist** consistently instead of Up Next, while existing
      Pocket Casts account/web sync and queue ordering still work.
- [ ] **Play oldest unfinished** starts immediately, resumes a partially played
      episode, then proceeds through newer unfinished episodes, skipping
      completed or dismissed ones. Existing queue takes precedence where expected.
- [ ] **Add unfinished to Playlist** appends without clearing the current
      queue, adding duplicates, or exceeding its capacity. Library-wide
      fewest-unplayed-first ordering works.
- [ ] Long-press an episode and **Dismiss** it; its unplayed count drops
      without changing played status or playback position. Restore through
      **Show Dismissed**.
- [ ] Long-press a podcast tile and **Download all**: confirmation counts
      only unfinished/partly played episodes, excluding dismissed/played,
      already downloaded/queued/failed or stream-only episodes. New episodes
      arriving after confirmation are not added silently.
- [ ] Podcast-screen **Download all** behaves the same way. Check pending
      downloads obey Wi-Fi, charging and storage preferences.
- [ ] Per-podcast rolling download limit (0 to 10, disabled by default)
      fills the oldest unfinished slots, refills after completion/dismissal,
      and never deletes existing audio just to reduce the limit.
- [ ] New episode title exclusions dismiss only matching new episodes;
      already saved episodes are not changed.
- [ ] Recurring chapter-title skipping works locally without unexpected
      repeated seeks. Manual chapter selections and Chromecast remain intact.
- [ ] Bass, Midrange and Treble EQ work where Android supports them; flat/off
      leaves sound unchanged. Mono toggles correctly with wired and Bluetooth
      output, without interfering with playback volume boost.
- [ ] Verify per-podcast settings, notifications, downloads, podcast artwork,
      playback background/lock-screen controls, and Pocket Casts sync after
      restarting the app.

## Stop and report

If the APK cannot update in place, shows a wrong package/version, loses
subscriptions or playback positions, unexpectedly downloads played episodes,
or behaves badly with background audio, **do not clear app data**. Record
the symptom and the episode/podcast action that produced it.

**Approval gate:** Only after these phone checks are accepted can the user
approve merging/releasing the player. Podcast Addict migration remains
planning-only, requires a fresh backup, and needs **separate approval**.
