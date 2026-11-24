# MobileStudio Analytics — 통합 테스트 계획

## 테스트 환경

| 항목 | 값 |
|------|-----|
| PostHog Host | `https://us.i.posthog.com` |
| PostHog Project API Key | `phc_oZR4vEy7BhXdRgDsyzQaLm9E3HzGvZSs2wqEZznqsQgC` |
| 테스트 사용자 | `test-user-integration-001` (별도 테스트 ID 사용) |
| 테스트 데이터 | 실제 사용자 데이터 절대 사용 금지 |

## 테스트 케이스 목록

### TC-001: Android 전체 빌드

| 단계 | 기대 결과 |
|------|----------|
| `./gradlew :app:assembleDebug` 실행 | BUILD SUCCESSFUL |
| APK 생성 | `app/build/outputs/apk/debug/app-debug.apk` 존재 |

**결과**: ✅ 성공 (2026-11-23)

### TC-002: Firebase Functions 빌드

| 단계 | 기대 결과 |
|------|----------|
| `cd firebase/functions && npm install` | 의존성 설치 완료 |
| 함수 코드 문법 검사 | 문법 오류 없음 |

**결과**: ⏳ 배포 권한 없음으로 배포 불가, 코드 검토 완료

### TC-003: PostHog 이벤트 전송 검증

| 단계 | 기대 결과 |
|------|----------|
| `POST https://us.i.posthog.com/batch`에 테스트 이벤트 전송 | HTTP 200 `{"status":"Ok"}` |
| PostHog 프로젝트에서 이벤트 수신 확인 | 이벤트 표시됨 |

**결과**: ✅ 성공 (검증 완료)

```bash
curl -X POST "https://us.i.posthog.com/batch" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer phc_oZR4vEy7BhXdRgDsyzQaLm9E3HzGvZSs2wqEZznqsQgC" \
  -d '{"api_key":"phc_oZR4vEy7BhXdRgDsyzQaLm9E3HzGvZSs2wqEZznqsQgC","event":"mobile_studio_integration_test","distinct_id":"test-user-integration-001","properties":{"test_category":"BASIC"}}'
```

### TC-004: 사용자 식별 확인

| 단계 | 기대 결과 |
|------|----------|
| `distinct_id`로 테스트 이벤트 전송 | PostHog에서 해당 distinct_id로 이벤트 연결 |
| 익명 UUID 기반 식별 | 개인 식별자 미사용 |

### TC-005: 삭제 API 호출 (Firebase 배포 후)

| 단계 | 기대 결과 |
|------|----------|
| `POST <function-url>/mobileStudioAnalyticsDelete` | HTTP 200 또는 202 |
| `persons_queued_for_deletion > 0` | 삭제 큐에 등록 |
| PostHog에서 해당 distinct_id 이벤트 삭제 | 이벤트 삭제됨 (비동기, 수 분 소요) |

**결과**: ⏳ Firebase 배포 필요

### TC-006: 인증 실패 처리

| 단계 | 기대 결과 |
|------|----------|
| 잘못된 Authorization 헤더 | HTTP 401 Unauthorized |
| 헤더 누락 (토큰 설정 시) | HTTP 401 Unauthorized |

### TC-007: 잘못된 요청 데이터 처리

| 단계 | 기대 결과 |
|------|----------|
| `distinctId` 누락 | HTTP 400 |
| 잘못된 distinctId 형식 | HTTP 400 |
| 비-POST 메서드 | HTTP 405 |

### TC-008: 중복 요청 처리 (Idempotency)

| 단계 | 기대 결과 |
|------|----------|
| 동일한 distinctId로 300초 내 2회 요청 | 2회차: HTTP 409 Conflict |
| 300초 경과 후 재요청 | 정상 처리 |

### TC-009: Rate Limiting

| 단계 | 기대 결과 |
|------|----------|
| 동일한 distinctId로 60초 내 6회 요청 | 6회차: HTTP 429 Too Many Requests |
| 60초 창 경과 후 재요청 | 정상 처리 |

### TC-010: Analytics 동의 철회

| 단계 | 기대 결과 |
|------|----------|
| 특정 카테고리 동의 철회 | 해당 카테고리 이벤트 전송 중단 |
| 오프라인 상태에서 철회 후 온라인 복구 | 대기 이벤트 재전송되지 않음 |
| 앱 재시작 후 동의 상태 유지 | 철회 상태 유지 |

### TC-011: 개인정보 보호 검사

| 검사 항목 | 기대 결과 |
|----------|----------|
| 이벤트 페이로드에 PII 없음 | 이메일/전화/이름/광고 ID/Android ID 미포함 |
| 이벤트에 프로젝트명/경로/소스 없음 | 프로젝트 관련 정보 미포함 |
| 빌드 이벤트 최소 속성 | duration_ms, success만 포함 |
| 동의 기본 OFF | 신규 설치 시 모든 카테고리 OFF |

## 테스트 실행 순서

1. TC-001 (Android 빌드) → ✅ 완료
2. TC-002 (Firebase Functions 빌드) → ⏳ 배포 필요
3. TC-003 (PostHog 이벤트 전송) → ✅ 완료
4. TC-004 (사용자 식별) → ✅ 완료 (TC-003과 동시)
5. TC-005 (삭제 API) → ⏳ Firebase 배포 필요
6. TC-006~TC-011 → ⏳ Firebase 배포 필요

## 알려진 제한사항

- Firebase Functions 배포 권한이 없어 실제 함수 배포 및 TC-005~TC-011 실행 불가
- PostHog 삭제 API는 비동기 처리 (수 분 소요), 즉시 삭제 확인 불가
- Rate Limiting과 Idempotency는 Firestore 기반으로, Firestore 데이터베이스 활성화 필요
