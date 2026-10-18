# Podcast Player Roadmap

Version in development: **v1.2.0**. User-facing releases use SemVer only, not
`test`, `rc` or an automated APK per commit.

## Accepted decisions

- **Playback A:** Select a podcast or its oldest unfinished episode and continue
  through newer unfinished episodes in chronological order without manual
  playlist construction. The ordinary Play controls also need to support this
  reliably: the new explicit **Play unfinished episodes (oldest first)** action
  is the first implementation step. Continuous playback now defaults to on
  for **new phone preference records**; explicit existing preferences win,
  and queued episodes still take precedence. **Play oldest** on the podcast
  tile now really begins playing the oldest unfinished episode instead of
  just adding it to Up Next; it sets the chosen podcast as the continuing
  autoplay source, while **Add unfinished to Up Next** preserves the queue.
  The explicit **Play unfinished episodes** and **Play from here** controls
  also select the current podcast as the future autoplay source and clear an
  unrelated old Up Next queue, even when the selected podcast has only one
  unfinished episode. The autoplay source is set **after** queue operations
  because Pocket Casts clears remembered sources when inserting episodes.
  The separate **Add unfinished to Up Next** action never
  clears the queue. The podcast autoplay selector now
  follows unfinished episodes **oldest to newest regardless of grid sorting**
  and stops when the podcast has no newer unfinished episodes, rather than
  wrapping back to the first episode. [#9](../../issues/9)
- **Playback B:** Sort podcasts by fewest unfinished episodes; add unfinished
  episodes to a listening queue in ascending count order, and oldest first
  inside each podcast. Preserve existing Up Next. [#10](../../issues/10)
- **Mark all played** excludes dismissed episodes, so dismissing is never
  mistaken for listening or changed by bulk playback-state actions.
- **Dismiss / Dismissed / Restore** are the UI names for upstream
  archive/archive/unarchive. Dismissing doesn't change playback history,
  playback position or played status; removes episodes from active episode
  lists, unfinished counts and queues. Show Dismissed and Restore permit
  recovery. The underlying archived flag, sync protocol and Android package
  identity remain unchanged. Automatic dismissal can trigger existing
  downloaded-audio cleanup; do not present it as equivalent to playing.
  [#20](../../issues/20)
- Podcast-specific download, cleanup and playback overrides. Existing settings
  already include new-episode downloads, speed, volume boost, silence removal,
  introduction/outro skipping and auto-dismissal; review missing overrides in
  [#11](../../issues/11). The per-podcast new-episode download switch now
  reports its own saved state independently of the global preference and explains
  when global automatic downloads are disabled.
- **Rolling oldest-first offline downloads (source implemented, unverified):**
  per-podcast 0-10 unfinished episodes, off by default, refill on completion,
  feed refresh and manual dismissal. Existing downloaded/pending episodes count against the limit;
  extras are never automatically deleted to enforce a smaller limit. Newer
  downloaded episodes cannot displace the oldest unfinished download slots.
  Download failures or non-downloadable episodes do not occupy the rolling
  slots; failures must be retried manually. Uses existing automatic-download
  Wi-Fi, charging and storage preferences. This
  preference is **device-local**, not part of Pocket Casts web sync.
  [#14](../../issues/14)
- **Download all from a podcast tile (source implemented, unverified):**
  long-press a podcast tile, choose **Download all**, review a confirmation
  containing the actual eligible unplayed episode count, and queue all
  unfinished, non-dismissed episodes for offline listening. **Played**
  episodes are excluded, as are previously downloaded, already queued,
  failed and stream-only episodes. Unlike the existing **Download unfinished**
  bulk action, this has no silent 100-episode cutoff. The duplicate capped
  **Download unfinished** option is removed from the tile menu, leaving one
  clear **Download all** action for all unplayed episodes. The podcast screen's
  own Download all option now follows the same selection and confirmation,
  rather than downloading archived/played items and stopping after 100. It uses the native
  automatic-download Wi-Fi, charging and storage constraints; confirm
  downloads have completed before travelling. [#21](../../issues/21)
- **Title exclusions (source implemented, unverified):** a per-podcast list of
  comma-separated words or phrases automatically dismisses matching **new**
  feed episodes before queueing/downloading. Existing episodes aren't bulk
  dismissed when the setting changes. This does not mark episodes played;
  restoring a dismissed episode remains possible. The final automatic download
  queue also explicitly excludes newly dismissed items, even when a playlist
  snapshot is stale. The exclusion rules are device-local; resulting dismissal
  uses existing Pocket Casts episode sync. [#15](../../issues/15)
- **Three-band equaliser and mono (source implemented, unverified):** Android
  Playback Effects now exposes device-local Bass, Midrange and Treble sliders
  (-6 to +6 dB, flat by default) and a Mono audio switch (off by default).
  Reuses the existing Android audio-effects session and Media3 PCM processor
  chain; it does not introduce another player, server or subscription.
  Mono's on/off flag is cached for audio-thread reads. With both EQ and volume
  boost off, Android EQ creation and band reconfiguration are avoided. EQ
  support depends on Android hardware; mono downmix applies to 16-bit stereo
  PCM but leaves other audio formats alone. Casting and the Kitchen Hub web
  player are unchanged. Bluetooth and on-device audio compatibility must be
  checked during acceptance. [#12](../../issues/12)
- **Recurring chapter-title skipping (source implemented, unverified):**
  optional device-local comma-separated chapter title words/phrases per podcast.
  Matching chapter entries are treated as unselected in the existing playback
  chapter-skip mechanism, without introducing another media player or cloud
  service. The underlying episode-specific chapter selection and Pocket Casts
  protocol remain unchanged. The web player and Chromecast do not inherit
  these local chapter-title rules. The chapter observer is cancelled when
  episodes change and checks the active episode again before applying a seek,
  so a late update cannot advance the next episode. Playback seeks happen only
  on entering a skipped chapter, not on every position update. [#13](../../issues/13)
- UK English and Podcast Addict-familiar wording without changing Pocket Casts
  protocol fields or falsely relabelling deletion. [#16](../../issues/16)
- Purple app icon, the name **Podcast Player**, reviewable upstream updates.
  Preserve the current signed Android app identity for in-place updates.
  [#8](../../issues/8)
- **No Podcast Addict history import yet.** Podcast Addict remains the daily,
  authoritative library. The 2026-10-17 .backup in IdeaPad Downloads is a
  privately held read-only format sample, **not** migration authority.
  On migration day use a fresh backup, a preview of safely matched episodes,
  and user confirmation before modifying listening history or syncing to web.
  The sample's SQLite indexes had integrity warnings. [#17](../../issues/17)
- **Kitchen Hub later:** existing Pocket Casts web player first, with
  supported sign-in and sync. No new web app/server without approval.
  [#18](../../issues/18)

## Engineering and release workflow

- Read **AGENTS.md**, README, this ROADMAP, relevant issues and PRs before
  implementation. Choose the smallest maintainable change. Brainstorming alone
  does not authorise code or a production change.
- Keep changes in reviewable PRs, reconcile issues and merge accepted changes
  into `main` rather than proliferating permanent branches.
- Do **not** run repeated full APK builds during feature development.
  The GitHub Actions APK workflow is manually disabled. Use source checks or
  focused compilation when needed, and produce one exact-head signed build
  only once the feature batch is approved for on-device acceptance.
  [#19](../../issues/19)
- Verify the existing Android application ID, certificate and sync still work
  before switching your phone from PodcastGPT v1.1.0.
- Preserve existing Podcast Addict data and the user's Pocket Casts login.

## Development order

1. Source-only core playback actions and dismissal; verify the data rules.
2. Complete per-podcast rolling download, new-title exclusion and tile
   Download all controls; source implementations await compilation and review.
3. Source review, focused checks and on-phone acceptance for recurring
   chapter skips, EQ, mono and the remaining per-podcast override details.
4. Review terminology, icon, performance and remaining requested features.
5. Confirm source validation, review PRs, then approve a single phone APK.
6. Only after the player is accepted: design, preview, and separately approve
   the Podcast Addict full-history import.
