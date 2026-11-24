const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");

admin.initializeApp();

const AI_API_KEY = defineSecret("AI_API_KEY");
const AI_SHARED_TOKEN = defineSecret("AI_SHARED_TOKEN");
const POSTHOG_PERSONAL_API_KEY = defineSecret("POSTHOG_PERSONAL_API_KEY");
const POSTHOG_PROJECT_ID = defineSecret("POSTHOG_PROJECT_ID");
const ANALYTICS_SHARED_TOKEN = defineSecret("ANALYTICS_SHARED_TOKEN");

const db = admin.firestore();

// ---------------------------------------------------------------------------
// Firestore-based rate limiter (per distinctId, 5 requests per 60 seconds).
// Uses Firestore transactions for atomicity across cold starts.
// ---------------------------------------------------------------------------
const RATE_LIMIT_MAX = 5;
const RATE_LIMIT_WINDOW_MS = 60 * 1000;
const RATE_LIMIT_COLLECTION = "analytics_rate_limits";

async function checkRateLimit(distinctId) {
  const docRef = db.collection(RATE_LIMIT_COLLECTION).doc(distinctId);
  const now = admin.firestore.Timestamp.now();
  const windowStartMs = now.toMillis() - RATE_LIMIT_WINDOW_MS;

  let allowed = false;
  try {
    await db.runTransaction(async (t) => {
      const doc = await t.get(docRef);
      const data = doc.exists() ? doc.data() : null;

      // Reset if window expired
      if (data && data.windowStart && data.windowStart.toMillis() < windowStartMs) {
        t.set(docRef, { count: 1, windowStart: now }, { merge: true });
        allowed = true;
        return;
      }

      const count = data ? (data.count || 0) : 0;
      if (count >= RATE_LIMIT_MAX) {
        allowed = false;
        return;
      }

      t.set(docRef, { count: count + 1, windowStart: data ? data.windowStart : now }, { merge: true });
      allowed = true;
    });
  } catch (e) {
    // On transaction failure, fail closed (reject request)
    allowed = false;
  }
  return allowed;
}

// ---------------------------------------------------------------------------
// Firestore-based idempotency (prevents duplicate deletion requests).
// TTL: 300 seconds. Uses Firestore transactions for atomicity.
// ---------------------------------------------------------------------------
const IDEMPOTENCY_TTL_MS = 300 * 1000;
const IDEMPOTENCY_COLLECTION = "analytics_idempotency";

async function checkIdempotency(distinctId) {
  const docRef = db.collection(IDEMPOTENCY_COLLECTION).doc(distinctId);
  const now = admin.firestore.Timestamp.now();
  const cutoffMs = now.toMillis() - IDEMPOTENCY_TTL_MS;

  let cached = null;
  try {
    await db.runTransaction(async (t) => {
      const doc = await t.get(docRef);
      if (!doc.exists()) {
        // First request: mark as pending
        t.set(docRef, {
          status: "pending",
          createdAt: now,
          distinctId
        });
        cached = null;
        return;
      }

      const data = doc.data();
      const createdAtMs = data.createdAt ? data.createdAt.toMillis() : 0;

      // Expired: treat as new request and overwrite
      if (createdAtMs < cutoffMs) {
        t.set(docRef, {
          status: "pending",
          createdAt: now,
          distinctId
        }, { merge: true });
        cached = null;
        return;
      }

      // Still within TTL: duplicate request
      cached = {
        status: data.status || "pending",
        createdAt: data.createdAt
      };
    });
  } catch (e) {
    // On transaction failure, allow request to proceed (fail open for idempotency)
    cached = null;
  }
  return cached;
}

async function recordIdempotencyResult(distinctId, status, result) {
  const docRef = db.collection(IDEMPOTENCY_COLLECTION).doc(distinctId);
  const now = admin.firestore.Timestamp.now();
  try {
    await db.runTransaction(async (t) => {
      const doc = await t.get(docRef);
      t.set(docRef, {
        status,
        createdAt: doc.exists() && doc.data().createdAt ? doc.data().createdAt : now,
        updatedAt: now,
        distinctId,
        result
      }, { merge: true });
    });
  } catch (e) {
    // Best-effort; do not block the response
  }
}

exports.mobileStudioAi = onRequest(
  {
    region: process.env.FUNCTION_REGION || "asia-northeast3",
    secrets: [AI_API_KEY, AI_SHARED_TOKEN],
    cors: false,
    timeoutSeconds: 180,
    memory: "512MiB"
  },
  async (req, res) => {
    try {
      if (req.method !== "POST") {
        res.status(405).json({ message: "POST only" });
        return;
      }

      const sharedToken = AI_SHARED_TOKEN.value();
      if (sharedToken) {
        const auth = req.get("authorization") || "";
        if (auth !== `Bearer ${sharedToken}`) {
          res.status(401).json({ message: "Unauthorized" });
          return;
        }
      }

      const payload = sanitizeRequest(req.body || {});
      const apiKey = AI_API_KEY.value();
      if (!apiKey) {
        res.status(500).json({ message: "AI_API_KEY secret is not configured" });
        return;
      }

      const upstream = await fetch(process.env.AI_API_URL || "https://api.openai.com/v1/responses", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${apiKey}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          model: process.env.AI_MODEL || "gpt-5.2-codex",
          input: [
            {
              role: "system",
              content: [
                {
                  type: "input_text",
                  text: systemPrompt()
                }
              ]
            },
            {
              role: "user",
              content: [
                {
                  type: "input_text",
                  text: JSON.stringify(payload)
                }
              ]
            }
          ],
          text: {
            format: {
              type: "json_object"
            }
          }
        })
      });

      const text = await upstream.text();
      if (!upstream.ok) {
        res.status(upstream.status).json({ message: "AI provider failed", detail: text.slice(0, 2000) });
        return;
      }

      const parsed = JSON.parse(text);
      const answer = extractJsonAnswer(parsed);
      res.json(answer);
    } catch (error) {
      res.status(500).json({ message: error.message || String(error) });
    }
  }
);

function sanitizeRequest(body) {
  return {
    prompt: String(body.prompt || "").slice(0, 12000),
    mode: String(body.mode || "chat").slice(0, 80),
    projectRootName: String(body.projectRootName || "").slice(0, 120),
    projectSummary: String(body.projectSummary || "").slice(0, 30000),
    currentFilePath: String(body.currentFilePath || "").slice(0, 500),
    currentFileContent: String(body.currentFileContent || "").slice(0, 220000),
    selectedText: String(body.selectedText || "").slice(0, 120000),
    problems: Array.isArray(body.problems) ? body.problems.slice(0, 40) : [],
    responseContract: body.responseContract || {}
  };
}

function systemPrompt() {
  return [
    "You are MobileStudio AI, a coding-only Android IDE assistant for MobileStudio.",
    "Return strict JSON only.",
    "Schema: {\"message\":\"short explanation\",\"edits\":[{\"path\":\"relative/path\",\"content\":\"full file content\",\"explanation\":\"why\"}]}",
    "For small replacements you may return {path, oldText, newText, explanation}.",
    "Never use absolute paths. Never write outside the project root. Prefer minimal safe edits.",
    "Treat selectedText as the primary target when it is non-empty.",
    "Support Kotlin, Java, XML layouts, Jetpack Compose, Gradle, Manifest, R8, NDK/CMake, and Android crash diagnostics.",
    "For security and performance scans, report prioritized findings and include edits only for safe, clear fixes.",
    "If the user asks to create an app or feature, create or modify the required Android files.",
    "If build errors are supplied, fix root causes rather than hiding errors.",
    "Do not claim changes were applied. The Android app shows a diff and asks the user to approve."
  ].join("\n");
}

function extractJsonAnswer(providerResponse) {
  const outputText =
    providerResponse.output_text ||
    providerResponse.choices?.[0]?.message?.content ||
    providerResponse.output?.flatMap((item) => item.content || [])
      .map((part) => part.text || "")
      .join("");

  if (!outputText) {
    return { message: "AI returned no text.", edits: [] };
  }

  try {
    const parsed = JSON.parse(outputText);
    return {
      message: String(parsed.message || parsed.text || ""),
      edits: Array.isArray(parsed.edits) ? parsed.edits : []
    };
  } catch {
    return { message: outputText, edits: [] };
  }
}

/**
 * Server-side analytics data deletion endpoint.
 *
 * The client calls this endpoint with its anonymous distinctId. This function
 * holds the PostHog Personal API Key as a Firebase secret - it NEVER reaches
 * the Android APK or any client source code.
 *
 * It deletes all events associated with the given distinctId from the
 * configured PostHog project via the persons/bulk_delete API.
 */
exports.mobileStudioAnalyticsDelete = onRequest(
  {
    region: process.env.FUNCTION_REGION || "asia-northeast3",
    secrets: [POSTHOG_PERSONAL_API_KEY, POSTHOG_PROJECT_ID, ANALYTICS_SHARED_TOKEN],
    cors: false,
    timeoutSeconds: 60,
    memory: "256MiB"
  },
  async (req, res) => {
    // No secret or token values are logged anywhere in this function.
    try {
      if (req.method !== "POST") {
        res.status(405).json({ ok: false, message: "POST only" });
        return;
      }

      const sharedToken = ANALYTICS_SHARED_TOKEN.value();
      if (sharedToken) {
        const auth = req.get("authorization") || "";
        if (auth !== `Bearer ${sharedToken}`) {
          res.status(401).json({ ok: false, message: "Unauthorized" });
          return;
        }
      }

      const body = req.body || {};
      const distinctId = body.distinctId;
      if (!distinctId || typeof distinctId !== "string") {
        res.status(400).json({ ok: false, message: "distinctId is required" });
        return;
      }
      // Validate distinctId format: only allow UUID-like or alphanumeric identifiers
      if (!/^[A-Za-z0-9\-_:]{1,128}$/.test(distinctId)) {
        res.status(400).json({ ok: false, message: "Invalid distinctId format" });
        return;
      }

      // Idempotency: reject duplicate deletion requests within TTL
      const cached = checkIdempotency(distinctId);
      if (cached) {
        res.status(409).json({
          ok: false,
          message: "Delete request already processed",
          distinctId
        });
        return;
      }

      // Rate limiting
      if (!checkRateLimit(distinctId)) {
        res.status(429).json({ ok: false, message: "Too many requests" });
        return;
      }

      const apiKey = POSTHOG_PERSONAL_API_KEY.value();
      const projectId = POSTHOG_PROJECT_ID.value();
      if (!apiKey) {
        res.status(500).json({ ok: false, message: "POSTHOG_PERSONAL_API_KEY secret is not configured" });
        return;
      }
      if (!projectId) {
        res.status(500).json({ ok: false, message: "POSTHOG_PROJECT_ID secret is not configured" });
        return;
      }

      const phHost = process.env.POSTHOG_API_HOST || "https://us.posthog.com";
      const url = `${phHost}/api/projects/${encodeURIComponent(projectId)}/persons/bulk_delete/`;

      // Upstream call with explicit timeout via AbortController
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 45 * 1000);

      let upstream;
      try {
        upstream = await fetch(url, {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${apiKey}`,
            "Content-Type": "application/json"
          },
          body: JSON.stringify({
            distinct_ids: [distinctId],
            delete_events: true,
            delete_recordings: true,
            keep_person: true
          }),
          signal: controller.signal
        });
      } finally {
        clearTimeout(timeoutId);
      }

      const text = await upstream.text();
      if (!upstream.ok) {
        res.status(upstream.status).json({
          ok: false,
          message: "PostHog delete failed",
          detail: text.slice(0, 2000)
        });
        return;
      }

      let parsed = {};
      try { parsed = JSON.parse(text); } catch { /* ignore parse errors */ }

      const personsQueued = parsed.persons_queued_for_deletion ?? 0;
      const personsFound = parsed.persons_found ?? 0;
      const deletionErrors = parsed.deletion_errors ?? [];

      if (deletionErrors.length > 0) {
        await recordIdempotencyResult(distinctId, "failed", {
          persons_found: personsFound,
          persons_queued_for_deletion: personsQueued,
          errors: deletionErrors.slice(0, 10)
        });
        res.status(202).json({
          ok: false,
          message: "Some deletions failed",
          persons_found: personsFound,
          persons_queued_for_deletion: personsQueued,
          errors: deletionErrors.slice(0, 10)
        });
        return;
      }

      await recordIdempotencyResult(distinctId, "completed", {
        persons_found: personsFound,
        persons_queued_for_deletion: personsQueued
      });
      res.json({
        ok: true,
        message: "Analytics data deletion queued",
        persons_found: personsFound,
        persons_queued_for_deletion: personsQueued
      });
    } catch (error) {
      // Do not log the raw error which could contain secrets
      await recordIdempotencyResult(distinctId, "failed", {
        error: "Internal server error"
      });
      res.status(500).json({ ok: false, message: "Internal server error" });
    }
  }
);
