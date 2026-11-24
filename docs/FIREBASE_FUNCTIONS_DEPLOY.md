# MobileStudio Analytics — Firebase Functions 배포 가이드

## 개요

`mobileStudioAnalyticsDelete` 함수는 Android 앱의 "Delete Analytics Data" 요청을 받아 PostHog에서 해당 사용자의 이벤트를 삭제하는 서버리스 함수입니다.

## 필요한 Firebase Secrets

다음 3개의 비밀을 Firebase 프로젝트에 설정해야 합니다. APK나 소스 코드에 포함되어서는 안 됩니다.

| Secret | 설명 | 예시 |
|--------|------|------|
| `POSTHOG_PERSONAL_API_KEY` | PostHog 개인 API 키 (scope: person:write) | `phc_xxx...` |
| `POSTHOG_PROJECT_ID` | PostHog 프로젝트 ID (숫자) | `123456` |
| `ANALYTICS_SHARED_TOKEN` | 앱 인증용 공유 토큰 | 임의 문자열 |

## Firestore 컬렉션 (자동 생성)

함수 실행 시 다음 컬렉션이 Firestore에 자동 생성됩니다:

| 컬렉션 | 용도 | TTL |
|--------|------|-----|
| `analytics_rate_limits` | 사용자별 요청 제한 | 60초 창 |
| `analytics_idempotency` | 중복 삭제 요청 방지 | 300초 |

## 배포 명령

```bash
cd /home/limsemin/Projects/MobileStudio/firebase/functions

# 1. 의존성 설치
npm install

# 2. Firebase 로그인
firebase login

# 3. Secrets 설정 (프로젝트당 1회)
firebase functions:config:set \
  posthog.personal_api_key="phc_xxx..." \
  posthog.project_id="123456" \
  analytics.shared_token="your-shared-token"

# 4. 함수 배포
firebase deploy --only functions:mobileStudioAnalyticsDelete
```

## 배포 후 수동 작업

### 3.1 앱 설정 연결

배포된 Firebase Function URL을 Android 앱의 `mobilestudio_settings` SharedPreferences에 저장해야 합니다.

앱에서 `Settings → Privacy → Analytics Preferences → Delete Analytics Data`를 사용하려면 다음 prefs 키를 설정해야 합니다:

| Preference Key | 값 | 설정 방법 |
|----------------|-----|-----------|
| `analytics_delete_url` | `https://<region>-<project-id>.cloudfunctions.net/mobileStudioAnalyticsDelete` | 관리자 콘솔 또는 코드 |
| `analytics_delete_token` | `<ANALYTICS_SHARED_TOKEN 값>` | 관리자 콘솔 또는 코드 |

### 3.2 PostHog 프로젝트 설정

PostHog Cloud 프로젝트에서 다음을 확인해야 합니다:

- 프로젝트 API 키: `phc_oZR4vEy7BhXdRgDsyzQaLm9E3HzGvZSs2wqEZznqsQgC` (Production) 또는 테스트 프로젝트 키
- 개인 API 키 (Personal API Key): PostHog 계정 설정에서 생성, `person:write` 스코프 필요
- 프로젝트 ID: PostHog 프로젝트 설정 페이지에서 확인

## 보안 고려사항

1. **관리자 키 미포함**: `POSTHOG_PERSONAL_API_KEY`는 Firebase Secret에만 저장되며, Android APK에는 포함되지 않습니다.
2. **앱 인증**: `ANALYTICS_SHARED_TOKEN`으로 앱 호출을 인증합니다. 이 토큰은 APK에 하드코딩되지 않고, Firebase Secret에 저장됩니다. 앱에서는 prefs에서 읽어 Authorization 헤더에 전달합니다.
3. **Rate Limiting**: 사용자별 5 요청/60초로 제한 (Firestore 기반, 콜드 스타트 후에도 유지)
4. **Idempotency**: 동일한 distinctId에 300초 내 중복 요청은 409 Conflict 반환 (Firestore 기반)
5. **HTTPS 강제**: Android 앱에서 엔드포인트가 `https://`로 시작하는지 검증
6. **로그 보안**: API 키나 토큰 값을 서버 로그에 기록하지 않음

## 배포 실패 시 확인 사항

- Firebase CLI 버전: `firebase --version` (v12 이상 권장)
- Firebase 프로젝트 선택: `firebase use --add`
- Firestore 데이터베이스 활성화 여부 (Firestore가 없는 경우 Rate Limiting/Idempotency 작동 안 함)
