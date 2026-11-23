# MobileStudio Analytics — Privacy Policy

**Applies to:** MobileStudio Android app analytics processed by PostHog Cloud
**Effective from:** 2026-11-23 (initial analytics rollout)

## 1. Data we collect

MobileStudio Analytics collects **anonymous, non-identifiable** usage data only:

| Category | Events / Data | Purpose |
| --- | --- | --- |
| Basic | `user_first_opened`, `user_returned` | DAU / WAU / MAU, new vs returning users |
| Usage | `app_session_started`, `app_session_ended` (+ `duration_ms`) | Daily usage time, average session duration, launch count |
| Feature | `project_created`, `xml_editor_opened`, `kotlin_editor_opened`, `java_editor_opened`, `cpp_editor_opened` | Feature adoption |
| Build | `build_started`, `build_completed` (+ `duration_ms`, `success`) | Build statistics (count, duration, success rate) |

## 2. What we never collect

- No personally identifiable information (email, phone number, name, account identifiers).
- No advertising IDs / Android IDs / precise location.
- No project names, file paths, source code, or edit content. Build events carry duration and success only.

## 3. Identifiers

- Users are separated by a **random anonymous UUID** generated per install. No personal identifiers are transmitted or stored.

## 4. Consent

- All four categories default to **OFF**.
- First-run consent screen (English) lets users allow/deny each category individually; "Decline All" and "Save Preferences & Continue" are provided.
- Consent is persisted locally and can be changed later via Settings → Privacy → Analytics Preferences.
- The app remains fully functional without analytics consent.

## 5. Deletion & opt-out

- Withdrawing consent stops event capture immediately and drops pending local events of the withdrawn category.
- "Delete Analytics Data" removes locally stored analytics state and triggers the server-side deletion flow.

## 6. Data sharing with PostHog

Events are sent to the configured PostHog ingestion endpoint (`https://us.i.posthog.com`) using the public project API key (`phc_...`) embedded in the client build. The key is a **public client key** identifying the project; it is not a secret/admin credential and is not used to read or delete data from the client.

## 7. Contact

Questions about this policy or the analytics implementation can be raised via the MobileStudio issue tracker.
