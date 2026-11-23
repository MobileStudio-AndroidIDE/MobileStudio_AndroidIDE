# MobileStudio Analytics — PostHog Dashboard & Verification

**Applies to:** MobileStudio Android app analytics (PostHog Cloud)
**Effective from:** 2026-11-23

## 1. Dashboard setup (PostHog Cloud)

Open the PostHog Cloud project (`https://us.i.posthog.com`, project API key `phc_oZR4vEy7BhXdRgDsyzQaLm9E3HzGvZSs2wqEZznqsQgC`).

### 1.1 Core insights (Insights → New insight)

| Metric | Insight type | Filter / notes |
| --- | --- | --- |
| DAU / WAU / MAU | Trends / Funnel | Event: `app_session_started` (or PostHog's own `$app_first_open` / device-based metrics) |
| Daily Usage Duration | Trends | Sum of `duration_ms` from `app_session_ended`, grouped by day |
| Avg Session Duration | Trends / Computed | Mean `duration_ms` of `app_session_ended` |
| Project Creation Statistics | Insights | Event: `project_created` |
| Language Editor Usage | Insights | Events: `xml_editor_opened`, `kotlin_editor_opened`, `java_editor_opened`, `cpp_editor_opened` |
| Build Statistics | Insights | Events: `build_started`, `build_completed` |
| Build Duration | Trends | `duration_ms` of `build_completed` |
| App Version Distribution | Insights | `$app_version` / release property on captured events |

### 1.2 Retention

- Configure PostHog retention (Project Settings → Data Management) to the agreed policy (default 30 days).
- Retention reports: use PostHog's retention/engagement reports on the anonymous user identifier.

### 1.3 Person / user separation

- MobileStudio sends a random anonymous UUID (`distinctId`) per install via `PostHog.identify(...)`.
- PostHog person profiles are used (identified only) — no personal data ever attached.

## 2. Event catalog quick reference

| Event | Category | Properties | Purpose |
| --- | --- | --- | --- |
| `user_first_opened` | Basic | – | First launch |
| `user_returned` | Basic | – | Return after first open |
| `app_session_started` | Usage | – | Session start |
| `app_session_ended` | Usage | `duration_ms` | Session end (foreground only) |
| `project_created` | Feature | – | Project created |
| `xml_editor_opened` | Feature | – | XML editor opened |
| `kotlin_editor_opened` | Feature | – | Kotlin editor opened |
| `java_editor_opened` | Feature | – | Java editor opened |
| `cpp_editor_opened` | Feature | – | C++ editor opened |
| `build_started` | Build | – | Build started |
| `build_completed` | Build | `duration_ms`, `success` | Build finished |

Build events intentionally carry **only** `duration_ms` and `success`. No project name, file path, source code, or content is ever sent.

## 3. Verification checklist (test events)

1. **Consent ON:** With all categories enabled, trigger `project_created` (create a project) and a build; verify events appear in PostHog (Debug view / Live events).
2. **Consent OFF / withdrawn:** Disable the category; trigger the event; verify nothing is sent and queued events of that category are not flushed (offline queue behavior).
3. **Build duration:** Run a build; verify `build_completed` with plausible `duration_ms` and `success` true/false.
4. **DAU / MAU:** Install / open the app (consented) and verify `user_first_opened` / `app_session_started` events arrive at `https://us.i.posthog.com/batch`.
5. **Usage time:** Background the app (session end via `onPause`) and verify `app_session_ended` `duration_ms` excludes background time.

## 4. Known limitations / follow-ups

- **Server-side data deletion** (follow-up A): client calls a protected backend endpoint that holds the PostHog admin/delete API key as a server-side secret; the anonymous UUID is the only client-supplied value.
- **Consent withdrawal + queued events** (follow-up B): design for dropping unconsented queued events before flush.
- **PostHog dashboard verification with real users** (follow-up C): send controlled test events to confirm DAU/MAU, usage time, feature events, build time and success rate reporting before release.
