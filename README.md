<a id="top"></a>
<div align="center">

# 📱 MobileStudio

### Android IDE for Android

**Develop · Build · Run — directly on your Android device**

<sub>🌸 · 🌷 · 🌹 · 🪻</sub>

![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Supported-7F52FF?logo=kotlin&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-Supported-02303A?logo=gradle&logoColor=white)
![No Root Required](https://img.shields.io/badge/Root-Not%20Required-blue)
![Offline Capable](https://img.shields.io/badge/Works-Offline-lightgrey)
![Made with Love](https://img.shields.io/badge/Made%20with-%E2%9D%A4-red)
![Status](https://img.shields.io/badge/Status-Active%20Development-yellow)
![PRs Welcome](https://img.shields.io/badge/PRs-Welcome-brightgreen)
![Maintained](https://img.shields.io/badge/Maintained-Yes-success)
![Open Source](https://img.shields.io/badge/Open%20Source-%E2%9D%A4-blueviolet)

![GitHub Release](https://img.shields.io/github/v/release/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE)
![APK Downloads](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/badges/apk-downloads.json)
![GitHub License](https://img.shields.io/github/license/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE)
![GitHub Stars](https://img.shields.io/github/stars/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE?style=social)
![GitHub Forks](https://img.shields.io/github/forks/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE?style=social)
![GitHub Watchers](https://img.shields.io/github/watchers/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE?style=social)

![GitHub Issues](https://img.shields.io/github/issues/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE)
![GitHub Last Commit](https://img.shields.io/github/last-commit/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE)
![GitHub Commit Activity](https://img.shields.io/github/commit-activity/m/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE)
![GitHub Contributors](https://img.shields.io/github/contributors/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE)
![GitHub Repo Size](https://img.shields.io/github/repo-size/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE)
![GitHub Code Size](https://img.shields.io/github/languages/code-size/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE)
![Top Language](https://img.shields.io/github/languages/top/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE)

<br>

### 🌐 **[🇺🇸 English](#english)**  ·  **[🇰🇷 한국어](#korean)**

**[📥 Download](#-download)** · **[✨ Features](#-features)** · **[🗺️ Roadmap](#️-roadmap)** · **[❓ FAQ](#-faq)** · **[🤝 Contributing](#-contributing)** · **[🐞 Bug Reports](#-bug-reports)**

</div>

---

<a id="english"></a>
# 🇺🇸 English

## 📑 Table of Contents

- [🌹 The Big Update — 26.8.xx Rose](#-the-big-update--268xx-rose)
- [🔍 Why This Is Hard](#-why-this-is-hard)
- [🚀 Quick Start](#-quick-start)
- [🧩 Built With](#-built-with)
- [📥 Download](#-download)
- [📦 Versions](#-versions)
- [⚙️ Support Status](#️-support-status)
- [✨ Features](#-features)
- [📊 MobileStudio vs. Traditional Setup](#-mobilestudio-vs-traditional-setup)
- [🗺️ Roadmap](#️-roadmap)
- [📋 Recommended Hardware](#-recommended-hardware)
- [❓ FAQ](#-faq)
- [🤝 Contributing](#-contributing)

---

## 🌹 The Big Update — 26.8.xx Rose

MobileStudio 26.8.xx **Rose** introduces several major features and improvements.

### 🖥️ Windows `.exe` Support

MobileStudio is being expanded beyond Android development.

- Build `.exe` applications using **Clang**
- Run `.exe` applications using **Box64 + Wine**
- Use a full development environment directly from an Android device

> ⚠️ `.exe` support is currently under active development.

### 🐚 StudioShell

StudioShell provides a command-line development environment inside MobileStudio.

You can use tools such as:

- Git
- cURL
- tar
- Core development utilities
- Other command-line tools

### 🤖 AI Development

AI-assisted development is being reintroduced using **OpenCode**.

Planned capabilities include:

- AI-assisted coding
- Code analysis
- Error investigation
- Development assistance

> ⚠️ **NDK Support** — NDK functionality is currently not working correctly. Other features will be developed and stabilized first; NDK support will be fixed and improved afterward.

---

## 🔍 Why This Is Hard

Most "IDE" apps on Android are just code editors — they still need a PC to actually build anything. MobileStudio runs a real **Gradle + JVM toolchain natively on the device**, which means fighting Android's platform restrictions head-on:

- **`noexec` sandbox bypass** — Android blocks execution from `/data`. MobileStudio works around this at the native layer instead of requiring root.
- **In-process JVM launching** — a custom native launcher (`libgradle_exec.so`) loads and drives the JVM directly on-device.
- **Hidden API patching** — the bundled Kotlin compiler needs internal JDK APIs that Android blocks by default, patched at the DEX level.
- **Linux userland integration** — a proot-based environment brings in the Unix tooling (Git, cURL, tar, and more) that a real dev workflow needs.
- **SELinux-aware design** — every workaround above has to coexist with Android's SELinux policy instead of fighting it.

This is the difference between "a code editor for Android" and "an actual build system for Android."

---

## 🚀 Quick Start

1. Download the latest **stable** APK (🌼 Daisy) from [Download](#-download) below.
2. Install and open MobileStudio on your Android device.
3. Import or create a Gradle-based Android project.
4. Tap **Build** — MobileStudio compiles and runs it, right there on your device.

No PC. No root. Just your phone.

---

## 🧩 Built With

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Java](https://img.shields.io/badge/Java-ED8B00?logo=openjdk&logoColor=white)
![C/C++](https://img.shields.io/badge/C%2FC%2B%2B-00599C?logo=cplusplus&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A?logo=gradle&logoColor=white)
![Clang](https://img.shields.io/badge/Clang-262D3A?logo=llvm&logoColor=white)
![Git](https://img.shields.io/badge/Git-F05032?logo=git&logoColor=white)
![Wine](https://img.shields.io/badge/Wine-7F5AB6?logo=wine&logoColor=white)
![Android NDK](https://img.shields.io/badge/Android%20NDK-3DDC84?logo=android&logoColor=white)

---

## 📥 Download

<div align="center">

[![Download MobileStudio](https://img.shields.io/badge/Download-MobileStudio_26.6.23_Daisy-success?style=for-the-badge&logo=android)](https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/MobileStudio_26.6.23-Daisy/MobileStudio_26.6.23-Daisy.apk)

**Android 10+**

</div>

- 🌼 **26.6.23 Daisy** — the current **stable** release. This is what the button above downloads.
- 🌹 **26.8.xx Rose** (described above) is the **in-development** version.
- 🪻 **26.8.17 Lavender** — upcoming milestone build inside the Rose cycle, bringing **Gradle 9.x support**.

> ⚠️ MobileStudio is under active development. Some features may not work correctly on the stable build either.

---

## 📦 Versions

| Codename | Version | Track | Notes |
|---|---|---|---|
| 🌼 Daisy | 26.6.x | **Stable** | Latest stable release — recommended for general use |
| 🌷 Tulip | 26.7.x | Development | Feature improvements |
| 🌹 Rose | 26.8.x | Major Development | `.exe` build/run, StudioShell improvements, OpenCode AI, toolchain + NDK fixes |
| 🪻 Lavender | 26.8.17 | Upcoming Milestone | **Gradle 9.x support** |

> 💡 Every release gets its own flower codename.

---

## ⚙️ Support Status

### Gradle

| Version | Status |
|---|---|
| Gradle ≤ 8.0 | ❌ |
| Gradle 8.2 | ⭐ Recommended |
| Gradle 8.7 | ✅ Supported |
| Gradle ≥ 8.8 | ❌ Currently unsupported |
| Gradle 9.x | 🌱 Coming in **26.8.17 Lavender 🪻** |

### JDK

| Version | Status |
|---|---|
| JDK 17 | ⭐ Recommended |
| JDK 21 | ❌ Currently unsupported |

### Other Components

| Component | Status |
|---|---|
| Android SDK | ✅ Supported |
| Android NDK | ⚠️ Currently being fixed |
| CMake | 🚧 Currently being fixed |
| Python | 🚧 Currently being fixed |
| XML Preview | 🚧 Planned |
| Jetpack Compose Preview | 🚧 Planned |

---

## ✨ Features

### 💻 Development

- ✅ Gradle Build
- ✅ Kotlin real-time diagnostics
- ✅ Code completion
- ✅ Syntax highlighting
- ✅ File explorer
- ✅ Terminal
- ✅ APK installation
- ✅ Build log viewer
- ✅ Git support

### 🐚 StudioShell

- Git · cURL · tar · core command-line development tools

### 🖥️ Windows Application Support

- 🚧 `.exe` build with Clang
- 🚧 `.exe` execution with Box64 + Wine

### 🤖 AI

- 🚧 OpenCode integration
- 🚧 AI-assisted coding
- 🚧 Code analysis
- 🚧 Development assistance

### 🔄 Updates

- ✅ Automatic update support — starting from v26.6.23 Daisy, MobileStudio can automatically detect, download, and install the latest available version.

### 📸 Screenshots

*Coming soon — screenshots will be added in a future update.*

---

## 📊 MobileStudio vs. Traditional Setup

| | MobileStudio | Traditional Setup |
|---|---|---|
| Requires a PC | ❌ No | ✅ Yes |
| Requires root | ❌ No | — |
| Real Gradle build | ✅ Yes | ✅ Yes |
| On-device terminal | ✅ Yes (StudioShell) | Varies |
| AI-assisted coding | 🚧 Coming in Rose | Varies |
| NDK / native builds | ⚠️ In progress | ✅ Yes |

---

## 🗺️ Roadmap

| Feature | Status |
|---|---|
| Android Gradle Build | ✅ |
| Kotlin Diagnostics | ✅ |
| Code Completion | ✅ |
| Syntax Highlighting | ✅ |
| APK Installation | ✅ |
| File Explorer | ✅ |
| Terminal | ✅ |
| Git | ✅ |
| Auto Update | ✅ |
| StudioShell | 🚧 |
| JDK 21 | 🚧 |
| Gradle 9.x | 🌱 Planned — 26.8.17 Lavender 🪻 |
| NDK | 🔧 Fixing |
| CMake | 🔧 Fixing |
| Python | 🔧 Fixing |
| XML Preview | 🚧 Planned |
| Compose Preview | 🚧 Planned |
| `.exe` Build | 🚧 |
| `.exe` Runtime | 🚧 |
| OpenCode AI | 🚧 |

---

## 📋 Recommended Hardware

| Item | Minimum | Recommended |
|---|---|---|
| RAM | 4 GB | 8 GB+ |
| Free Storage | 15 GB | 30 GB+ |
| Android | Android 10 | Android 13+ |

Building large Android projects may require significantly more RAM and storage.

---

## ❓ FAQ

**Do I need root access?**
No. MobileStudio is built specifically to work around Android's `noexec` restrictions without requiring root.

**Can I build native (C/C++/NDK) projects right now?**
Not yet. NDK support is currently broken and is being fixed after other core features are stabilized.

**Can I use JDK 21?**
Not yet — JDK 17 is the currently recommended version.

**When will Gradle 9.x be supported?**
Starting with **26.8.17 "Lavender" 🪻**.

**Is the AI coding feature available now?**
Not yet — OpenCode integration is planned for the Rose (26.8.x) release.

**Why isn't a stable release of Rose available yet?**
Rose is a major development release with big architectural changes (`.exe` support, StudioShell, AI integration). It needs to stabilize before becoming the new Daisy.

---

## 🤝 Contributing

Contributions are welcome!

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes with a clear message
4. Open a Pull Request describing what you changed and why

For bugs or feature requests, please open a GitHub Issue first so it can be discussed before a PR is submitted.

<div align="center">

[⬆ Back to top](#top) · [🇰🇷 View in Korean](#korean)

</div>

---

<a id="korean"></a>
# 🇰🇷 한국어

## 📑 목차

- [🌹 대규모 업데이트 — 26.8.xx Rose](#-대규모-업데이트--268xx-rose)
- [🔍 왜 어려운가](#-왜-어려운가)
- [🚀 시작하기](#-시작하기)
- [🧩 사용 기술](#-사용-기술)
- [📥 다운로드](#-다운로드)
- [📦 버전](#-버전)
- [⚙️ 지원 현황](#️-지원-현황)
- [✨ 기능](#-기능)
- [📊 MobileStudio vs 기존 방식](#-mobilestudio-vs-기존-방식)
- [🗺️ 로드맵](#️-로드맵)
- [📋 권장 사양](#-권장-사양)
- [❓ 자주 묻는 질문](#-자주-묻는-질문)
- [🤝 기여하기](#-기여하기)

---

## 🌹 대규모 업데이트 — 26.8.xx Rose

MobileStudio 26.8.xx **Rose** 버전에서는 여러 주요 기능과 개선사항이 추가됩니다.

### 🖥️ Windows `.exe` 지원

MobileStudio는 Android 개발을 넘어 영역을 확장하고 있습니다.

- **Clang**을 이용한 `.exe` 애플리케이션 빌드
- **Box64 + Wine**을 이용한 `.exe` 애플리케이션 실행
- Android 기기에서 바로 사용하는 개발 환경

> ⚠️ `.exe` 지원은 현재 활발히 개발 중입니다.

### 🐚 StudioShell

StudioShell은 MobileStudio 내부에서 사용할 수 있는 커맨드라인 개발 환경입니다.

사용 가능한 도구:

- Git
- cURL
- tar
- 핵심 개발 유틸리티
- 기타 명령줄 도구

### 🤖 AI 개발

**OpenCode**를 기반으로 AI 기반 개발 기능이 다시 추가됩니다.

계획된 기능:

- AI 기반 코딩 지원
- 코드 분석
- 오류 조사
- 개발 지원

> ⚠️ **NDK 지원** — NDK 기능은 현재 정상적으로 작동하지 않습니다. 다른 기능을 먼저 개발·안정화한 후 NDK를 수정할 예정입니다.

---

## 🔍 왜 어려운가

대부분의 "Android용 IDE"는 코드 편집기 수준이라 실제 빌드는 PC가 필요합니다. MobileStudio는 실제 **Gradle + JVM 툴체인을 기기에서 직접** 구동합니다.

- **`noexec` 샌드박스 우회** — 루트 권한 없이 네이티브 레벨에서 우회
- **인프로세스 JVM 실행** — 네이티브 런처(`libgradle_exec.so`)가 기기에서 JVM을 직접 구동
- **Hidden API 패치** — 번들 Kotlin 컴파일러가 필요로 하는 내부 API를 DEX 레벨에서 패치
- **Linux 유저랜드 통합** — proot 기반 환경으로 Git, cURL, tar 등 실제 개발에 필요한 도구 제공
- **SELinux 인지 설계** — 위 모든 우회 기법이 SELinux 정책과 충돌 없이 공존

이것이 "Android용 코드 편집기"와 "진짜 Android용 빌드 시스템"의 차이입니다.

---

## 🚀 시작하기

1. 아래 [다운로드](#-다운로드)에서 최신 **안정 버전**(🌼 Daisy) APK 받기
2. 기기에 설치 후 MobileStudio 실행
3. Gradle 기반 Android 프로젝트 열기 또는 새로 만들기
4. **빌드** 버튼 탭 — 기기에서 바로 컴파일 및 실행

PC도, 루트도 필요 없습니다. 폰만 있으면 됩니다.

---

## 🧩 사용 기술

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Java](https://img.shields.io/badge/Java-ED8B00?logo=openjdk&logoColor=white)
![C/C++](https://img.shields.io/badge/C%2FC%2B%2B-00599C?logo=cplusplus&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A?logo=gradle&logoColor=white)
![Clang](https://img.shields.io/badge/Clang-262D3A?logo=llvm&logoColor=white)
![Git](https://img.shields.io/badge/Git-F05032?logo=git&logoColor=white)
![Wine](https://img.shields.io/badge/Wine-7F5AB6?logo=wine&logoColor=white)
![Android NDK](https://img.shields.io/badge/Android%20NDK-3DDC84?logo=android&logoColor=white)

---

## 📥 다운로드

<div align="center">

[![Download MobileStudio](https://img.shields.io/badge/Download-MobileStudio_26.6.23_Daisy-success?style=for-the-badge&logo=android)](https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/MobileStudio_26.6.23-Daisy/MobileStudio_26.6.23-Daisy.apk)

**Android 10 이상**

</div>

- 🌼 **26.6.23 Daisy** — 현재 **안정 버전**. 위 버튼으로 바로 다운로드됩니다.
- 🌹 **26.8.xx Rose** — 위에서 설명한 **개발 중** 버전.
- 🪻 **26.8.17 Lavender** — Rose 개발 주기 내 예정된 마일스톤 빌드로, **Gradle 9.x 지원**이 추가됩니다.

> ⚠️ MobileStudio는 활발히 개발 중입니다. 안정 버전에서도 일부 기능이 정상 작동하지 않을 수 있습니다.

---

## 📦 버전

| 코드네임 | 버전 | 트랙 | 비고 |
|---|---|---|---|
| 🌼 Daisy | 26.6.x | **안정** | 최신 안정 버전 — 일반 사용 권장 |
| 🌷 Tulip | 26.7.x | 개발 | 기능 개선 |
| 🌹 Rose | 26.8.x | 주요 개발 | `.exe` 빌드/실행, StudioShell 개선, OpenCode AI, 툴체인 + NDK 수정 |
| 🪻 Lavender | 26.8.17 | 예정된 마일스톤 | **Gradle 9.x 지원** |

> 💡 모든 릴리즈는 고유한 꽃 이름을 가지고 있습니다.

---

## ⚙️ 지원 현황

### Gradle

| 버전 | 상태 |
|---|---|
| Gradle 8.0 이하 | ❌ |
| Gradle 8.2 | ⭐ 권장 |
| Gradle 8.7 | ✅ 지원 |
| Gradle 8.8 이상 | ❌ 현재 미지원 |
| Gradle 9.x | 🌱 **26.8.17 Lavender 🪻**부터 지원 예정 |

### JDK

| 버전 | 상태 |
|---|---|
| JDK 17 | ⭐ 권장 |
| JDK 21 | ❌ 현재 미지원 |

### 기타 구성 요소

| 구성 요소 | 상태 |
|---|---|
| Android SDK | ✅ 지원 |
| Android NDK | ⚠️ 수정 중 |
| CMake | 🚧 수정 중 |
| Python | 🚧 수정 중 |
| XML 미리보기 | 🚧 예정 |
| Jetpack Compose 미리보기 | 🚧 예정 |

---

## ✨ 기능

### 💻 개발

- ✅ Gradle 빌드
- ✅ Kotlin 실시간 진단
- ✅ 코드 완성
- ✅ 문법 강조
- ✅ 파일 탐색기
- ✅ 터미널
- ✅ APK 설치
- ✅ 빌드 로그 뷰어
- ✅ Git 지원

### 🐚 StudioShell

- Git · cURL · tar · 핵심 명령줄 개발 도구

### 🖥️ Windows 애플리케이션 지원

- 🚧 Clang을 이용한 `.exe` 빌드
- 🚧 Box64 + Wine을 이용한 `.exe` 실행

### 🤖 AI

- 🚧 OpenCode 통합
- 🚧 AI 기반 코딩
- 🚧 코드 분석
- 🚧 개발 지원

### 🔄 업데이트

- ✅ 자동 업데이트 지원 — v26.6.23 Daisy부터 MobileStudio가 최신 버전을 자동으로 감지·다운로드·설치할 수 있습니다.

### 📸 스크린샷

*추후 추가 예정입니다.*

---

## 📊 MobileStudio vs 기존 방식

| | MobileStudio | 기존 방식 |
|---|---|---|
| PC 필요 | ❌ 불필요 | ✅ 필요 |
| 루트 필요 | ❌ 불필요 | — |
| 실제 Gradle 빌드 | ✅ 지원 | ✅ 지원 |
| 온디바이스 터미널 | ✅ 지원 (StudioShell) | 상이 |
| AI 기반 코딩 | 🚧 Rose에서 추가 예정 | 상이 |
| NDK / 네이티브 빌드 | ⚠️ 진행 중 | ✅ 지원 |

---

## 🗺️ 로드맵

| 기능 | 상태 |
|---|---|
| Android Gradle 빌드 | ✅ |
| Kotlin 진단 | ✅ |
| 코드 완성 | ✅ |
| 문법 강조 | ✅ |
| APK 설치 | ✅ |
| 파일 탐색기 | ✅ |
| 터미널 | ✅ |
| Git | ✅ |
| 자동 업데이트 | ✅ |
| StudioShell | 🚧 |
| JDK 21 | 🚧 |
| Gradle 9.x | 🌱 예정 — 26.8.17 Lavender 🪻 |
| NDK | 🔧 수정 중 |
| CMake | 🔧 수정 중 |
| Python | 🔧 수정 중 |
| XML 미리보기 | 🚧 예정 |
| Compose 미리보기 | 🚧 예정 |
| `.exe` 빌드 | 🚧 |
| `.exe` 실행 | 🚧 |
| OpenCode AI | 🚧 |

---

## 📋 권장 사양

| 항목 | 최소 | 권장 |
|---|---|---|
| RAM | 4 GB | 8 GB+ |
| 여유 저장공간 | 15 GB | 30 GB+ |
| Android | Android 10 | Android 13+ |

대규모 프로젝트를 빌드할 경우 더 많은 RAM과 저장공간이 필요할 수 있습니다.

---

## ❓ 자주 묻는 질문

**루트 권한이 필요한가요?**
아니요. MobileStudio는 루트 없이 Android의 `noexec` 제약을 우회하도록 설계되었습니다.

**지금 네이티브(C/C++/NDK) 프로젝트를 빌드할 수 있나요?**
아직입니다. NDK 지원은 현재 정상 작동하지 않으며, 다른 핵심 기능을 안정화한 후 수정될 예정입니다.

**JDK 21을 쓸 수 있나요?**
아직입니다. 현재는 JDK 17이 권장 버전입니다.

**Gradle 9.x는 언제 지원되나요?**
**26.8.17 "Lavender" 🪻**부터 지원됩니다.

**AI 코딩 기능을 지금 쓸 수 있나요?**
아직입니다. OpenCode 통합은 Rose(26.8.x) 릴리즈에서 계획되어 있습니다.

**Rose의 안정 버전은 왜 아직 없나요?**
Rose는 `.exe` 지원, StudioShell, AI 통합 등 큰 구조적 변화가 포함된 주요 개발 릴리즈입니다. 새로운 Daisy가 되기 전에 먼저 안정화가 필요합니다.

---

## 🤝 기여하기

기여를 환영합니다!

1. 저장소 Fork
2. 기능 브랜치 생성 (`git checkout -b feature/my-feature`)
3. 명확한 메시지로 커밋
4. 변경 사항과 이유를 설명하는 Pull Request 생성

버그나 기능 제안은 PR 전에 GitHub Issue로 먼저 남겨주시면 논의 후 진행할 수 있습니다.

<div align="center">

[⬆ 맨 위로](#top) · [🇺🇸 View in English](#english)

</div>

---

<div align="center">

## Star History

<a href="https://www.star-history.com/?type=date&repos=MobileStudio-AndroidIDE%2FMobileStudio_AndroidIDE">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=MobileStudio-AndroidIDE/MobileStudio_AndroidIDE&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=MobileStudio-AndroidIDE/MobileStudio_AndroidIDE&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=MobileStudio-AndroidIDE/MobileStudio_AndroidIDE&type=date&legend=top-left" />
 </picture>
</a>

---

## 🐞 Bug Reports / 버그 제보

Report bugs via GitHub Issues or email. · 버그 제보는 GitHub Issues 또는 이메일로 부탁드립니다.

**Email:** limsemin17@gmail.com

Please include / 가능하면 다음 정보를 함께 제공해주세요:

- MobileStudio version / 버전
- Android version / 버전
- Device info / 기기 정보
- What you were doing / 오류가 발생한 작업
- Logcat
- Error message / 오류 메시지

---

## 📜 License

See the [LICENSE](LICENSE) file for license information. · 라이선스 정보는 [LICENSE](LICENSE) 파일을 참고하세요.

---

<div align="center">

### 📱 MobileStudio

**A development environment built for Android.**

*Develop anywhere. Build anywhere.*

⭐ If you find MobileStudio useful, consider starring the repository. · 유용하다면 ⭐를 눌러주세요.

</div>
