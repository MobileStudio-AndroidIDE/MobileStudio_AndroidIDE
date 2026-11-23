# MobileStudio Analytics — Integration Testing

**Applies to:** MobileStudio Android app analytics (PostHog Cloud)
**Effective from:** 2026-11-23

## 1. Test setup

### 1.1 PostHog test project

Create a dedicated PostHog Cloud project for MobileStudio integration testing (separate from production):

| Setting | Value |
| --- | --- |
| Host | `https://us.i.posthog.com` |
| Project API Key | `phc_...` (test project key) |
| Session Replay | Disabled |
| Autocapture | Disabled |
| Screen tracking | Disabled |

Update `AnalyticsConfig.POSTHOG_API_KEY` and `AnalyticsConfig.POSTHOG_HOST` to point at the test project before running integration tests.

### 1.2 Firebase delete function (server-side deletion)

Deploy `firebase/functions/index.js` (`mobileStudioAnalyticsDelete`):

```bash
cd firebase/functions
npm install
firebase deploy --only functions:mobileStudioAnalyticsDelete
```

Configure the following Firebase secrets (never in the APK):

| Secret | Description |
| --- | --- |
| `POSTHOG_PERSONAL_API_KEY` | PostHog personal API key (scoped: `person:write`) |
| `POSTHOG_PROJECT_ID` | PostHog project ID (numeric) |
| `ANALYTICS_SHARED_TOKEN` | Shared bearer token the app sends for auth |

The function URL (e.g. `https://asia-northeast3-<project>.cloudfunctions.net/mobileStudioAnalyticsDelete`) must be stored in the app's `mobilestudio_settings` SharedPreferences under key `analytics_delete_url`, and the shared token under `analytics_delete_token`. These are set by the admin after deployment — not embedded in the APK.

## 2. Integration test activity

`AnalyticsIntegrationTestActivity` sends one controlled event per consent category to PostHog. It is reachable from the app (debug builds only) via `Context.launchAnalyticsIntegrationTest()`.

**Prerequisites:**
- Enable the categories you want to verify in Settings → Privacy → Analytics Preferences (all OFF by default).
- The test app build must be signed and installed on a device/emulator with network access to `https://us.i.posthog.com`.

**Test flow:**

| Step | Action | Expected result |
| --- | --- | --- |
| 1 | Open Settings → Privacy → Analytics Preferences. Enable BASIC + USAGE + FEATURE + BUILD. Save. | Toggles persist after restart. |
| 2 | Launch Analytics Integration Test. | Screen shows one button per category. |
| 3 | Tap "Send BASIC event". | `user_first_opened` event arrives in PostHog Debug view. |
| 4 | Tap "Send USAGE event". | `app_session_started` event arrives. |
| 5 | Tap "Send FEATURE event". | `project_created` event arrives. |
| 6 | Tap "Send BUILD event". | `build_completed` with `duration_ms=1234`, `success=true` arrives. |
| 7 | Tap "Flush PostHog queue". | All queued events are delivered. |
| 8 | Disable BASIC in Settings. Tap "Send BASIC event" again. | Nothing is sent (beforeSend drops it). |
| 9 | Restart the app. | Consent state persists; BASIC still OFF. |

## 3. Consent withdrawal verification

| Step | Action | Expected result |
| --- | --- | --- |
| 1 | Enable all categories; send events for each. | Events arrive in PostHog. |
| 2 | Withdraw FEATURE (uncheck in Settings). | New `project_created` / editor events are NOT sent. |
| 3 | Send a FEATURE event (e.g. open a project). | Event is dropped by `beforeSend` — not queued. |
| 4 | Put device in Airplane mode (offline); send events. | Events are persisted locally but not flushed. |
| 5 | Withdraw USAGE while offline. | No further session events are produced. |
| 6 | Restore connectivity (Airplane off); flush queue. | Only events for categories still enabled are flushed. |
| 7 | Force-stop the app; restart. | Consent state persists; withdrawn categories remain OFF. |

## 4. DAU / WAU / MAU verification

PostHog computes DAU/WAU/MAU from distinct IDs. Verify in the PostHog dashboard:

| Metric | Insight query | Expected |
| --- | --- | --- |
| DAU | Trends: `app_session_started`, unique users, daily | ≥1 on test days |
| WAU | Trends: `app_session_started`, unique users, weekly | ≥1 |
| MAU | Trends: `app_session_started`, unique users, monthly | ≥1 |
| New vs returning | `user_first_opened` vs `user_returned` counts | Both present after first + second day |

## 5. Usage time and midnight split verification

| Step | Action | Expected result |
| --- | --- | --- |
| 1 | Enable USAGE; open app; keep foreground for ~30 s. | `app_session_ended` with `duration_ms` ≈ 30000. |
| 2 | Background the app (Home button); return after ~20 s. | Two `app_session_ended` events; second `duration_ms` excludes background time. |
| 3 | Keep app open past midnight (00:00 local). | Daily usage is split across day keys; PostHog shows usage on both days. |

## 6. Build time and success rate verification

| Step | Action | Expected result |
| --- | --- | --- |
| 1 | Enable BUILD; run a Gradle build in MobileStudio. | `build_started` then `build_completed(success=true, duration_ms>0)`. |
| 2 | Run a build that fails (e.g. invalid project). | `build_completed(success=false, duration_ms>0)`. |
| 3 | Query PostHog: `build_completed` success rate = success / total. | Matches observed build outcomes. |
| 4 | Query PostHog: avg `duration_ms` of `build_completed`. | Plausible build duration (not 0 or extreme). |

## 7. App restart and offline recovery verification

| Step | Action | Expected result |
| --- | --- | --- |
| 1 | Enable all categories; use app normally. | Events queued locally. |
| 2 | Disable network (Airplane mode); continue using app. | No network errors; events persist in SDK disk queue. |
| 3 | Force-stop app while offline; restart. | Consent persists; abnormal-exit recovery closes previous session. |
| 4 | Restore network; app resumes. | `PostHog.flush()` delivers pending events (subject to consent gates). |

## 8. Delete Analytics Data verification

| Step | Action | Expected result |
| --- | --- | --- |
| 1 | Enable all categories; send several events. | Events exist in PostHog for the test distinctId. |
| 2 | Settings → Privacy → Analytics Preferences → "Delete Analytics Data". | Toast shows "Analytics data deleted on the server." on success. |
| 3 | Check PostHog: persons/bulk_delete accepted (202). | `persons_queued_for_deletion` > 0. |
| 4 | Re-enable categories; send new events. | New events arrive under the same distinctId (fresh anonymous id after re-init). |

**Failure cases:**
- If `analytics_delete_url` is not set: toast shows "Analytics delete failed: analytics_delete_endpoint_not_configured".
- If network fails: toast shows the exception message.
- If the server returns non-2xx: toast shows the server error body (first 500 chars).

## 9. Privacy contract checks

| Check | Method | Expected |
| --- | --- | --- |
| No PII | Inspect event payloads in PostHog Debug. | No email / phone / name / ad ID / Android ID. |
| No project content | Inspect `project_created`, editor events. | No project name, file path, source code, or content. |
| Build events minimal | Inspect `build_completed`. | Only `duration_ms` and `success`. |
| Consent defaults OFF | Fresh install; no consent screen interaction. | All four categories OFF; app fully usable. |

## 10. Known limitations

- Robolectric is not available in this project, so UI-level consent tests require a physical device / emulator.
- Server-side deletion requires the Firebase function to be deployed with secrets; the app cannot complete deletion until `analytics_delete_url` and `analytics_delete_token` are configured in SharedPreferences.
- PostHog event deletion is asynchronous (queued, not immediate); a 202 response means deletion was queued, not completed.
