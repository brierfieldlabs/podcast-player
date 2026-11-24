# Podcast Addict migration

24 November 2026: owner accepted Podcast Player v1.2.4 on the phone and approved importer development. Podcast Addict is authoritative for confidently matched subscriptions and episode data, even when the destination is newer. The existing separate live-import approval remains in force. No automatic unsubscribe of destination-only feeds, destructive archive reset, or alteration of unmatched records is implied.

## Preview implementation

Reuse Settings > Import & export > Podcast Addict backup. Match active subscriptions by exact RSS URL; within a unique shared feed, compare unique exact enclosure URLs and publication timestamps within 24 hours. Pocket Casts stores its own episode UUID, not the original RSS GUID. Duplicate source or destination enclosure URLs and date mismatches are ambiguous. Report both source and target unmatched counts in unique shared feeds, missing and destination-only subscriptions, resume and favourite differences, historical completion evidence and provisional state overwrite counts. This is a candidate identity comparison, not a state-writing plan; source-only episodes and feeds still need a separate acquisition/matching step. No titles or personal records leave the device.

Candidate current-state mapping: `seen_status=1` maps to played even with a residual resume position; `seen_status=0` maps to unplayed when position is zero, or in progress when positive. Null, unknown flags and invalid positions remain unresolved. Historical completion is evidence, not an override of the current flag. The [AntennaPod migration reference](https://github.com/AntennaPod/MigrationScripts) and local aggregate source observations support investigating this mapping, but it still needs representative on-device verification. Preserve source resume values separately in milliseconds, converted to Pocket Casts seconds. No third-party migration code was copied.

## Statistics

Podcast Addict has day/hour/feed aggregates and separate listening history. Pocket Casts StatsManager stores lifetime listening and saved-time totals, not those detailed aggregates. Its server merge adds local and server totals; directly setting totals risks duplicate counting on both devices. The preview now reports source days, wall time, content duration and selected saved-time counters separately (minutes). These are not proposed additions to existing account totals. The current backup has a large discrepancy between wall time and content duration, so do not substitute one for the other or assume that old statistics are uniformly measured. Determine the supported server replacement or local-only preservation approach, including actual wall listening time versus content duration, before importing any stats. Keep unmatched detail in the original backup, not a new service.

## Next actions

1. Compile and verify the extended read-only preview on the designated CI runner and phone, using the fresh private backup only on the phone. Do not upload it to CI.
2. Verify provisional state mapping, including marked-played episodes with residual positions and resets to unplayed. Acquire missing subscriptions via the existing OPML server resolution and subscription path, with automatic downloads disabled; only after approval. Re-preview after missing feeds are present. Resolve missing episodes, archive and history handling.
3. Establish safe target snapshot and idempotent account-sync application, review statistics limitations, then obtain separate explicit apply approval. Do not merge or release the draft PR without its separate release approval.

The existing OPML worker already resolves feed URLs in batches and subscribes with `shouldAutoDownload=false`. Reuse this instead of introducing a separate network service. Episode state changes should use the established sync dirty timestamps; history is synced separately, and the current local history view is limited to 1,000 episodes. Do not replace the complete Room database or claim full event-history support.
