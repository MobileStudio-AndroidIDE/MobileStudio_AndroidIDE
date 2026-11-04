# MobileStudio AI Firebase Backend

This optional backend keeps AI provider keys out of the Android APK. The app only stores an HTTPS proxy URL and an optional shared token. Firebase is not required by the Android app; any HTTPS proxy that accepts the same JSON contract can be used.

## Setup

```bash
cd firebase/functions
npm install
firebase functions:secrets:set AI_API_KEY
firebase functions:secrets:set AI_SHARED_TOKEN
firebase deploy --only functions:mobileStudioAi
```

After deployment, copy the HTTPS function URL into **AI Assistant > AI Proxy URL** inside MobileStudio. If you set `AI_SHARED_TOKEN`, enter the same token in the app.

## Optional Environment

- `AI_API_URL`: upstream provider endpoint.
- `AI_MODEL`: model name used by the backend. The default is `gpt-5.2-codex` for coding-focused Android IDE work.

The function expects the app's project/file/problem context and returns JSON with:

```json
{
  "message": "Explanation for the user",
  "edits": [
    { "path": "app/src/main/Example.kt", "content": "..." }
  ]
}
```

Requests include `mode`, `projectSummary`, `currentFilePath`, `currentFileContent`, optional `selectedText`, and parsed build problems. MobileStudio always previews returned edits as a diff and asks the user before applying them.
