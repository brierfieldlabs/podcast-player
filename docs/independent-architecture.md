# Independent Podcast Player

**Status:** Product direction approved by the owner in the architectural Q&A, 24 November 2026. This is the canonical design checkpoint, not implementation, deployment, migration or release approval. Issue [#26](../../issues/26) coordinates the work. The current Android fork and its existing feature work are the starting point; the owner will use Podcast Addict until the independent system is accepted.

## Purpose and boundaries

One personal podcast library, independent of Pocket Casts, accessible on Android and through a browser. A private server coordinates the catalogue and listening state; Android remains a fully usable offline-first player and can fetch RSS and stream from publishers without the server. No second permanent sync backend, commercial account, subscription tier, advertising, Episode Chat or global podcast index. Audiobookshelf and gPodder-compatible solutions were considered and rejected by the owner because they lack the desired features.

## Accepted decisions (Q&A)

| Question | Accepted decision |
| --- | --- |
| 1 A | Android works independently away from home: direct refresh, streaming, downloads and offline changes; sync when reachable. |
| 2 Tailscale | Private access via existing Tailscale arrangements; no public service. |
| 3 A | Single user, one library, no registration or multi-user management. |
| 4 A | Retain historical episode metadata and history indefinitely, even after RSS removal; play audio when available. |
| 5 B | Opt-in server audio preservation for selected episodes/podcasts with configurable storage limits, alongside local device downloads. No automatic whole-library audio mirroring. |
| 6 B | Shared podcast and playback preferences, sorting, exclusions and chapter rules, with local device-specific storage, download and audio-output overrides. |
| 7 A | Most recent genuine playback or deliberate state action wins, independent of arrival order; respect restarts and mark-unplayed. |
| 8 C | Public directory search, manual RSS URLs and OPML imports. Direct publisher retrieval, not a proprietary directory catalogue. |
| 9 B | Adaptive refresh, manual refresh, per-feed overrides and HTTP caching. |
| 10 A | Unsubscribe without deleting catalogue, settings or history; resubscribe restores them. |
| 11 B | Functional web/Android parity wherever browser capabilities permit; desktop/tablet-appropriate web UI; no browser offline/PWA requirement. |
| 12 A | Complete Podcast Addict migration: subscriptions, historical episodes, played/unplayed, resume, favourites, playlists, history, statistics and compatible settings. Explicitly report omissions. |
| 13 B | Integrated web administration for storage, refresh, failed feeds, imports, backups and health; reuse Proxmox backup/monitoring. |
| 14 revised | Podcast Addict remains daily authority until independent system ready. Stop Pocket Casts-specific migration development. Develop and verify independent app/server, then use fresh backup and separate approval for cutover. No obligation to retain Pocket Casts compatibility in new development; no permanent dual-server support. |

## Minimal technical proposal (not a final stack approval)

- Same Git repository and existing Android Kotlin/Room/Media3 application, signed package identity and feature semantics. Replace provider-specific data boundaries rather than rewrite UI or playback.
- One Debian Proxmox CT, one service, one authoritative SQLite database, integrated browser UI. Proposed lightweight Python backend; choose the exact framework and dependencies only after checking existing conventions and compatibility. No separate database server, queue broker, audio transcoding service or orchestration.
- Tailscale access limited to authorised devices; no public ingress. Check tailnet access policy, browser-origin protections and server identity before deployment. No multi-user account subsystem. Confirm CT location/resources/storage, access configuration and backup/recovery with owner before provisioning.
- A server catalogue and Android Room cache are intentional, not competing authoritative servers. Android can create offline feed/episode records and changes; stable canonical identifiers and reconciliation must work without network access. RSS GUIDs scoped to feeds are useful, not universally reliable: preserve feed aliases and corroborate collisions with enclosure/date/metadata. Never silently merge ambiguous items. Keep source provenance, no fake Pocket Casts UUIDs.
- One versioned sync contract: idempotent changes and incremental retrieval, including unsubscribe and dismissal; per-field/action conflict handling where appropriate. Store genuine action time separately from receipt time, consider clock skew and deterministic ties, and prevent stale retries from undoing intentional changes. Treat listening events separately from resume state; deduplicate event identities to avoid inflating statistics. Validate offline/out-of-order/retry behavior before real data.
- Respect RSS redirects, conditional requests, failures, backoff and publisher load. Use a safe XML parser, bounded downloads and appropriate timeouts. Retain historical episode records; distinguish missing feed item from unreachable audio. Do not assume old audio is still available. Use existing network/media libraries where practical. Evaluate public directory API terms/credentials before committing to providers.
- Audio storage is opt-in, bounded and visible; separate removable audio from persistent metadata. Stream publisher audio normally or use retained server audio when selected. No default proxying/mirroring of entire library. Verify browser playback/seek and CORS limits before promising full parity; use proportionate remedies only.
- Shared business rules for ordering, dismiss/restore, playlist and settings; native Android UX retained. Browser capability gaps (e.g. OS audio EQ, offline downloads, casting) must be explicit, not silently claimed as implemented.

## Migration and acceptance

Existing v1.3.2 Pocket Casts-specific trial is frozen at code commit `51bb7e02642a839a01e1589e5724b55336187f2b`, passed CT142 run 45503227327; exact RSS matching corrected in the user's preview, but live import not accepted. Database comparisons established missing historical records and changed enclosures. Direct import into our own catalogue removes the requirement to find an existing third-party episode UUID. Preserve original source IDs and evidence; map states, durations, history, preferences and playlists only when supported. Avoid blindly assuming archive or deleted flags have equivalent meaning.

Use synthetic fixtures in automated tests; private source backup only for explicitly controlled manual read-only/acceptance checks. Import into a fresh candidate database with counts, validation, reconciliation report and repeat-import safety. Preserve the backup and existing app data. The owner explicitly reviews the fresh migration before any live import/cutover. Do not push private feed URLs, records, preferences, logs or backups to GitHub. No data reset, automatic APK installation, merge, public release or deployment without applicable gates.

## Delivery sequence and gates

| Stage | Deliverable and evidence | Tracking |
| --- | --- | --- |
| 0 | Record approved decisions and inventory existing provider boundaries; review proposal before coding. | #26 |
| 1 | Minimal server catalogue and stable identity model; synthetic RSS fixtures prove direct retrieval, redirects, retained history, unsubscribe/resubscribe and deduplication. | #26 |
| 2 | Sync contract and Android adapter; two clients round-trip state, offline changes, retries, deliberate reset and conflicts without losing data. Keep Android playback/UX and existing package identity. | #26, #8-#16, #20-#24 |
| 3 | Direct backup importer: complete mapped catalogue/history/settings, repeat-safe import, counts and limitations; synthetic regression and controlled private reference comparison. | #17 |
| 4 | Optional server audio retention/storage limits, shared preferences, adaptive refresh/discovery; integrate web player and admin with shared behavior. | #26, #18 |
| 5 | Exact-head CI, controlled test CT via Tailscale, backup/restore check, one approved signed APK trial, phone/tablet/web parity and offline acceptance. No changes to daily Podcast Addict. | #19, #26, #18 |
| 6 | Fresh backup review, separately approved migration and owner cutover; remove remaining obsolete provider integration as appropriate; release only after acceptance. | #17, #19, #26 |

No speculative version bump or fresh APK for documentation. Existing Android CI remains on designated CT142; workbench CT140 is for Git and lightweight checks only. Establish minimal server CI on the approved runner when implementation is authorised, rather than borrowing other project runners. Upstream code remains an optional source of reviewed fixes, not a reason to reintroduce Pocket Casts services. Keep existing draft PR and feature issues, no new repository or permanent development branch.

**Next action:** owner reviews this consolidated architecture and authorises the first implementation slice (catalogue and identity, plus provider-boundary assessment). Exact backend framework, device protocol details, CT placement/resources and deployment credentials remain open engineering decisions. No CT provisioned and no implementation started.
