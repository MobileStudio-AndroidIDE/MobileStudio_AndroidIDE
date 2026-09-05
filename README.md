<div align="center">

# 📱 MobileStudio

### Android IDE for Android

**Develop · Build · Run — directly on your Android device**

<br>

![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Supported-7F52FF?logo=kotlin&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-Supported-02303A?logo=gradle&logoColor=white)
![No Root Required](https://img.shields.io/badge/Root-Not%20Required-blue)
![Made with Love](https://img.shields.io/badge/Made%20with-%E2%9D%A4-red)
![Status](https://img.shields.io/badge/Status-Active%20Development-yellow)
![PRs Welcome](https://img.shields.io/badge/PRs-Welcome-brightgreen)
![Maintained](https://img.shields.io/badge/Maintained-Yes-success)

![GitHub Release](https://img.shields.io/github/v/release/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE)
![GitHub Downloads](https://img.shields.io/github/downloads/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/total)
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

**[📥 Download](#-download)** · **[✨ Features](#-features)** · **[🗺️ Roadmap](#️-roadmap)** · **[❓ FAQ](#-faq)** · **[🤝 Contributing](#-contributing)** · **[🐞 Bug Reports](#-bug-reports)**

</div>

---

## 📑 Table of Contents

- [🌹 The Big Update — 26.8.xx Rose](#-the-big-update--268xx-rose)
- [🔍 Why This Is Hard](#-why-this-is-hard)
- [📥 Download](#-download)
- [📦 Versions](#-versions)
- [⚙️ Support Status](#️-support-status)
- [✨ Features](#-features)
- [🗺️ Roadmap](#️-roadmap)
- [📋 Recommended Hardware](#-recommended-hardware)
- [❓ FAQ](#-faq)
- [🤝 Contributing](#-contributing)
- [⭐ Star History](#-star-history)
- [🌎 Language / 한국어](#-language)
- [🐞 Bug Reports](#-bug-reports)
- [📜 License](#-license)

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

## 📥 Download

<div align="center">

[![Download MobileStudio](https://img.shields.io/badge/Download-MobileStudio_26.6.23_Daisy-success?style=for-the-badge&logo=android)](https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/MobileStudio_26.6.23-Daisy/MobileStudio_26.6.23-Daisy.apk)

**Android 10+**

</div>

- 🌼 **26.6.23 Daisy** — the current **stable** release. This is what the button above downloads.
- 🌹 **26.8.xx Rose** (described above) is the **in-development** version — not yet in the stable download.

> ⚠️ MobileStudio is under active development. Some features may not work correctly on the stable build either.

---

## 📦 Versions

| Codename | Version | Track | Notes |
|---|---|---|---|
| 🌼 Daisy | 26.6.x | **Stable** | Latest stable release — recommended for general use |
| 🌷 Tulip | 26.7.x | Development | Feature improvements |
| 🌹 Rose | 26.8.x | Major Development | `.exe` build/run, StudioShell improvements, OpenCode AI, toolchain + NDK fixes |

---

## ⚙️ Support Status

### Gradle

| Version | Status |
|---|---|
| Gradle ≤ 8.0 | ❌ |
| Gradle 8.2 | ⭐ Recommended |
| Gradle 8.7 | ✅ Supported |
| Gradle ≥ 8.8 | ❌ Currently unsupported |
| Gradle 9.x | 🚧 Planned |

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
| Gradle 9.x | 🚧 |
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

**Can I use JDK 21 or Gradle 9?**
Not yet — JDK 17 and Gradle 8.2–8.7 are the currently supported range. JDK 21 and Gradle 9.x are on the roadmap.

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

---

## ⭐ Star History

<div align="center">

[![Star History Chart](https://api.star-history.com/svg?repos=MobileStudio-AndroidIDE/MobileStudio_AndroidIDE&type=Date)](https://star-history.com/#MobileStudio-AndroidIDE/MobileStudio_AndroidIDE&Date)

</div>

---

## 🌎 Language

MobileStudio supports documentation in:

- 🇺🇸 English (this document)
- 🇰🇷 한국어 (below)

<details>
<summary><b>🇰🇷 한국어 보기</b></summary>

### 📱 MobileStudio 소개

MobileStudio는 Android 기기에서 직접 Gradle 기반 Android 프로젝트를 개발하고 빌드할 수 있는 IDE입니다.

단순한 코드 편집기를 넘어 Android 기기에서 다음과 같은 개발 환경을 제공하는 것을 목표로 합니다.

- Android 앱 개발
- Gradle 빌드
- 터미널
- Git
- StudioShell
- 다양한 개발 도구
- AI 기반 개발 지원

### 🔍 왜 어려운가

대부분의 "Android용 IDE"는 코드 편집기 수준이라 실제 빌드는 PC가 필요합니다. MobileStudio는 실제 Gradle + JVM 툴체인을 기기에서 직접 구동합니다.

- Android의 `noexec` 샌드박스를 루트 없이 우회
- 네이티브 런처(`libgradle_exec.so`)로 JVM을 기기에서 직접 구동
- 번들 Kotlin 컴파일러를 위한 Hidden API 패치
- proot 기반 Linux 유저랜드 통합 (Git, cURL, tar 등)
- SELinux 정책과 공존하는 설계

### 🌹 26.8.xx Rose

Rose 버전에서는 MobileStudio의 개발 범위를 더욱 확장합니다.

**🖥️ Windows `.exe`**
- Clang을 이용한 `.exe` 빌드
- Box64 + Wine을 이용한 `.exe` 실행

**🐚 StudioShell**
MobileStudio 내부에서 Git, cURL, tar 등의 명령줄 도구를 사용할 수 있도록 개발하고 있습니다.

**🤖 AI**
OpenCode를 기반으로 AI 개발 기능을 다시 추가할 예정입니다.

> ⚠️ 현재 NDK 기능은 정상적으로 작동하지 않습니다. 다른 기능을 먼저 개발한 후 NDK 기능을 수정할 예정입니다.

### 📥 다운로드

현재 안정 버전은 **26.6.23 Daisy**입니다. Rose(26.8.x)는 개발 중인 버전으로 아직 안정 버전에 포함되지 않았습니다.

### ❓ 자주 묻는 질문

- **루트가 필요한가요?** 아니요, 루트 없이 동작하도록 설계되었습니다.
- **지금 NDK(C/C++) 빌드가 되나요?** 아직입니다. 다른 기능을 안정화한 후 수정될 예정입니다.
- **JDK 21이나 Gradle 9를 쓸 수 있나요?** 아직입니다. 현재는 JDK 17, Gradle 8.2~8.7만 지원됩니다.

### 🤝 기여하기

버그 제보나 기능 제안은 GitHub Issue로 먼저 남겨주세요. Pull Request도 언제든 환영합니다.

</details>

---

## 🐞 Bug Reports

버그를 발견했거나 개선사항이 있다면 GitHub Issues 또는 아래 이메일을 통해 알려주세요.

**Email:** limsemin17@gmail.com

버그 제보 시 가능하다면 다음 정보를 함께 제공해주세요.

- MobileStudio 버전
- Android 버전
- 기기 정보
- 오류가 발생한 작업
- Logcat
- 오류 메시지

Logcat을 함께 보내주시면 문제를 확인하는 데 큰 도움이 됩니다.

---

## 📜 License

See the [LICENSE](LICENSE) file for license information.

---

<div align="center">

### 📱 MobileStudio

**A development environment built for Android.**

*Develop anywhere. Build anywhere.*

⭐ If you find MobileStudio useful, consider starring the repository.

</div>
