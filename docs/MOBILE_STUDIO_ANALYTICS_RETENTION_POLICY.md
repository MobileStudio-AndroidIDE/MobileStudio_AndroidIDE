# MobileStudio Analytics — Retention Policy

**Applies to:** MobileStudio Android app analytics processed by PostHog Cloud
**Effective from:** 2026-11-23 (initial analytics rollout)

## 1. What we retain

| Data | Retention / Handling |
| --- | --- |
| Anonymous install / usage events (`app_session_started`, `app_session_ended`, `user_first_opened`, `user_returned`) | Retained for product analytics; deleted on request via "Delete Analytics Data" (server-side deletion flow, see follow-up A) |
| Usage durations (`duration_ms` on session events) | Aggregated for daily / average session metrics; raw events subject to retention below |
| Feature events (`project_created`, `*_editor_opened`) | Retained for feature adoption statistics |
| Build events (`build_started`, `build_completed`) | Retained for build health statistics (duration, success rate) |

## 2. Retention window

- **Default PostHog Cloud retention:** 30 days (project default), rolling.
- **Aggregation:** MobileStudio only queries aggregate / analytics (DAU, WAU, MAU, daily usage duration, feature counts, build statistics). No per-user data is retained or displayed in the admin dashboard.
- **Local retention:** the device only keeps the current anonymous UUID and last-seen day in `mobilestudio_settings`; queued (offline) events live in the SDK disk queue only until flushed or deleted.

## 3. Deletion

- Users can withdraw consent (per category) at any time via Settings → Privacy → Analytics Preferences, or decline all on first run.
- On withdrawal, pending local events for the withdrawn category are dropped and no further events of that category are captured.
- "Delete Analytics Data" triggers the server-side deletion flow (PostHog project deletion API invoked through the protected backend function that holds the admin key as a server-side secret). The anonymous user identifier is the only value sent to the backend.

## 4. Change log

| Date | Change |
| --- | --- |
| 2026-11-23 | Initial retention policy published with the MobileStudio Analytics rollout. |
