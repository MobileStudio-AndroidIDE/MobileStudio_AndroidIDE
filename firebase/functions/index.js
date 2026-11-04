const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");

admin.initializeApp();

const AI_API_KEY = defineSecret("AI_API_KEY");
const AI_SHARED_TOKEN = defineSecret("AI_SHARED_TOKEN");

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
