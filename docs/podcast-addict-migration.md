# Podcast Addict migration

24 November 2026: owner accepted Podcast Player v1.2.4 on the phone and approved importer development. Podcast Addict is authoritative for confidently matched subscriptions and episode data, even when the destination is newer. The existing separate live-import approval remains in force. No automatic unsubscribe of destination-only feeds, destructive archive reset, or alteration of unmatched records is implied.

## Preview implementation

Reuse Settings > Import & export > Podcast Addict backup. Match active subscriptions by exact RSS URL; within a unique shared feed, compare unique exact enclosure URLs and publication timestamps within 24 hours. Pocket Casts stores its own episode UUID, not the original RSS GUID. Duplicate source or destination enclosure URLs and date mismatches are ambiguous. Report target-side matched, ambiguous and unmatched episode counts, resume and favourite differences, and historical completion evidence. This is a candidate identity comparison, not a state-writing plan; source-only episodes and feeds still need a separate acquisition/matching step. No titles or personal records leave the device.

Source `seen_status` and completion history must be interpreted together before mapping current played/unplayed state. A historic completion can precede a reset; do not silently reclassify ambiguous states. Source resume values are milliseconds, Pocket Casts positions are seconds. Verify units and state semantics with representative on-device cases before applying.

## Statistics

Podcast Addict has day/hour/feed aggregates and separate listening history. Pocket Casts StatsManager stores lifetime listening and saved-time totals, not those detailed aggregates. Its server merge adds local and server totals; directly setting totals risks duplicate counting on both devices. The preview reports availability of source day statistics only. Determine the supported server replacement or local-only preservation approach, including actual wall listening time versus content duration, before importing any stats. Keep unmatched detail in the original backup, not a new service.

## Next actions

1. Compile and verify the extended read-only preview on the designated CI runner and phone, using the fresh private backup only on the phone. Do not upload it to CI.
2. Resolve source state semantics, source-only feed acquisition, missing episodes, archive and history handling; calculate a full proposed overwrite including resets to unplayed.
3. Establish safe target snapshot and idempotent account-sync application, review statistics limitations, then obtain separate explicit apply approval. Do not merge or release the draft PR without its separate release approval.
