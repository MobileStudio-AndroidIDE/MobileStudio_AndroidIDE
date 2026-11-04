# Line Counter

파일을 저장할 때 그 파일의 줄 수를 집계해 토스트로 보여주는 코드 확장입니다.

MobileStudio 확장 API의 전체 생명주기를 흐르는 샘플로, 빌드 → 설치 → 실행까지
한 번에 검증하는 데 사용하세요.

## 동작

- `onFileSave` 훅에 반응합니다.
- 총 줄 / 코드 줄 / 주석(`//`, `/* */`, `#`) / 공백 줄을 각각 집계합니다.
- 프로젝트 밖의 파일(예: 앱 데이터 브라우저)은 건드리지 않습니다.

## 요구 권한

| 권한 | 이유 |
|---|---|
| `project.read` | 저장된 파일 내용 읽기 |

`showToast` 는 별도 권한을 요구하지 않습니다.

## 설치와 실행

앱에서 **Extensions → Build .msext** 를 누르고 이 프로젝트를 고르면
`javac → d8 → classes.dex` 가 자동으로 수행된 뒤 패키징됩니다.

설치 시 권한 승인 dialog, 검사된 SHA-256 고정, `entryPoint` ↔ `classes.dex`
일관성 검사가 순서대로 통과해야 합니다.

## 생명주기 훅

이 확장은 `onFileSave` 하나만 씁니다. MobileStudio가 발화시키는 훅은
`onEnable`, `onDisable`, `onUninstall`, `onProjectOpen`, `onProjectClose`,
`onBuildStart`, `onBuildFinish`, `onFileOpen`, `onFileSave`, `onShutdown`
총 10개입니다. 모든 훅은 `ExtensionDispatch` 단일 백그라운드 스레드에서
예외 격리와 함께 실행되므로, 확장이 죽어도 IDE는 멈추지 않습니다.

## 빌드 (호스트)

```sh
./build.sh            # javac + d8 + zip -> examples/line-counter-v1.0.0.msext
```

요구 도구: JDK, `android.jar`, Android SDK build-tools의 `d8`. 앱이 쓰는 확장 API
스텁 jar(`app/src/main/assets/extensions/extension-api.jar`)를 classpath로 씁니다.
컴파일 전용이며 런타임에는 로드되지 않습니다 — `PathClassLoader`는 항상 앱 쪽
인터페이스를 먼저 찾습니다.
