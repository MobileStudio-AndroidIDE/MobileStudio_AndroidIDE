



































































































































































































































































































































<a id="top"></a>

<div align="center"><img src="https://capsule-render.vercel.app/api?type=waving&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=220&section=header&text=MobileStudio&fontSize=60&fontColor=ffffff&animation=fadeIn&fontAlignY=35&desc=Android%20IDE%20for%20Android&descAlignY=60&descSize=18&descColor=ffffff" width="100%" alt="MobileStudio — Android IDE for Android"/><img src="https://readme-typing-svg.demolab.com/?font=Fira+Code&size=20&pause=1000&color=E75480&center=true&vCenter=true&width=700&lines=Develop+%C2%B7+Build+%C2%B7+Run;Directly+on+your+Android+device;No+PC.+No+Root.+Just+your+phone" alt="Develop, Build, Run — directly on your Android device"/>
<sub>✨ 🌼 · 🌷 · 🌹 · 🪻 ✨</sub>


















🌐 🇺🇸 English · 🇰🇷 한국어
📥 Download · ✨ Features · 🗺️ Roadmap · ❓ FAQ · 🤝 Contributing





</div><img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
<a id="english"></a>

<details open> <summary><h2>🇺🇸 English</h2></summary>
[!TIP]
⚡ At a Glance — No PC · No root · On-device Gradle builds · Native arm64-v8a tools · AI coding with API or local models · Native development · Vulkan, Lua, and Godot game development

📑 Table of Contents
🌹 Rose — 26.10.15
🔍 Why MobileStudio?
🚀 Quick Start
🧩 Built With
📥 Download
📦 Version Overview
⚙️ Support Status
✨ Features
📊 MobileStudio vs. Traditional Setup
🗺️ Roadmap
📋 Recommended Hardware
❓ FAQ
🤝 Contributing
🐞 Bug Reports
📜 License
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🌹 Rose — 26.10.15
MobileStudio 26.10.15 “Rose” expands on-device development with native build tools, AI-assisted coding, and game development capabilities.

🖥️ Windows .exe Build Support
✅ Build Windows .exe applications using Clang
🚧 Run .exe applications with Box64 + Wine — under development
🛠️ Work toward a broader development environment directly on Android
🐚 StudioShell
StudioShell brings command-line development tools to MobileStudio using binaries ported or built for arm64-v8a.

Git
cURL
tar
Core command-line utilities
StudioShell is designed to use native ARM64 tools rather than a proot-based Linux environment.

🤖 AI-Assisted Coding
Starting with 26.10.15 Rose, MobileStudio supports AI coding through API-connected services and local models powered by llama.cpp.

AI-assisted code generation
AI-driven code editing
Code analysis
Error investigation
API model connections
Local model inference with llama.cpp
🎮 Game Development
Create games directly on your Android device.

✅ Vulkan-based rendering
✅ Lua scripting
✅ Build and play projects on-device
✅ Godot Engine support
[!NOTE]
Feature availability and stability can vary by device and project. MobileStudio is actively developed.

<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🔍 Why MobileStudio?
Many Android coding apps focus on editing code, while the actual build process still happens on a PC. MobileStudio aims to bring a real development workflow to Android.

On-device Gradle builds — compile Android projects directly on your device.
Native ARM64 tooling — use command-line tools built for arm64-v8a.
No root required — designed to work within Android's app environment.
Integrated development workflow — editor, diagnostics, terminal, build logs, and APK installation.
AI-assisted development — connect API models or run supported local models.
Beyond Android apps — native projects and game development are also part of the toolkit.
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🚀 Quick Start
Download MobileStudio from the GitHub Releases page.
Install and open the app on your Android device.
Import an existing Gradle-based Android project or create one.
Open the project and tap Build.
Review the build output and install the generated APK when available.
No PC. No root. Build right on your phone.

<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🧩 Built With
<div align="center">





Git





</div><img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
📥 Download · 다운로드
<div align="center">


Android 10+

</div>
Release	Track	Description
🌼 Daisy — 26.6.x	Stable	Stable release line
🌷 Tulip — 26.7.x	Development	Feature improvements
🪻 Lavender — 26.8.x	Development	Major toolchain and platform improvements
🌹 Rose — 26.10.15	Development	AI models, native build support, and game development updates
[!NOTE]
Check the Releases page for the latest APK and release notes. Some features may be experimental.

<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
⚙️ Support Status
Gradle
Version	Status
Gradle ≤ 8.0	❌ Unsupported
Gradle 8.2	✅ Supported — legacy; not recommended
Gradle 8.7	✅ Supported
Gradle 8.8+	✅ Supported
Gradle 9.7.0	✅ Supported
Gradle 9.8.0	✅ Supported
JDK
Version	Status
JDK 17	✅ Supported
JDK 21	⭐ Recommended — supported since Rose
Other Components
Component	Status
Android SDK	✅ Supported
Android NDK	✅ Supported
CMake	✅ Supported
Python	🔧 In progress
XML Preview	🚧 Planned
Jetpack Compose Preview	🚧 Planned
Windows .exe build	✅ Supported
Windows .exe execution	🚧 In progress
AI API connections	✅ Supported
Local AI via llama.cpp	✅ Supported
Vulkan / Lua game development	✅ Supported
Godot Engine	✅ Supported
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
✨ Features · 기능
💻 Android Development
✅ Gradle builds on-device
✅ Kotlin real-time diagnostics
✅ Code completion
✅ Syntax highlighting
✅ File explorer
✅ Integrated terminal
✅ APK installation
✅ Build log viewer
✅ Git support
🐚 StudioShell
✅ Native arm64-v8a command-line tools
✅ Git, cURL, tar, and core utilities
✅ No proot-based environment required
🧰 Native Development
✅ Android NDK support
✅ C/C++ development
✅ CMake support
🖥️ Windows Application Development
✅ Build .exe files with Clang
🚧 Execute .exe files using Box64 + Wine
🤖 AI
✅ API-connected AI models
✅ Local models through llama.cpp
✅ AI code generation and editing
✅ Code analysis and development assistance
🎮 Game Development
✅ Vulkan-based rendering
✅ Lua scripting
✅ Godot Engine support
✅ On-device game development
🔄 Updates
✅ Automatic update support — available since v26.6.23 Daisy. MobileStudio can detect, download, and install available updates.
📸 Screenshots
Screenshots will be added soon.

<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
📊 MobileStudio vs. Traditional Setup
Capability	MobileStudio	Traditional PC Setup
Requires a PC	❌ No	✅ Usually
Requires root	❌ No	—
Real Gradle builds	✅ On-device	✅
Integrated terminal	✅ StudioShell	Varies
AI coding	✅ API and local models	Varies
NDK / native builds	✅ Supported	✅
Windows .exe build	✅ Clang	✅
.exe execution on Android	🚧 In progress	—
Game development	✅ Vulkan, Lua, Godot	Varies
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🗺️ Roadmap
Feature	Status
Android Gradle builds	✅
Kotlin diagnostics	✅
Code completion	✅
Syntax highlighting	✅
APK installation	✅
File explorer	✅
Integrated terminal	✅
Git support	✅
Automatic updates	✅
StudioShell	🚧 Active development
JDK 21	✅
Gradle 9.7.0+	✅
Android NDK	✅
CMake	✅
Python	🔧 In progress
XML Preview	🚧 Planned
Jetpack Compose Preview	🚧 Planned
.exe build	✅
.exe runtime	🚧 In progress
AI assistant	✅
API and local AI models	✅
Vulkan + Lua game development	✅
Godot Engine	✅
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
📋 Recommended Hardware
Item	Minimum	Recommended
RAM	4 GB	8 GB+
Free storage	15 GB	30 GB+
Android	Android 10	Android 13+
Large projects, native builds, and local AI models may require additional memory and storage.

<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
❓ FAQ · Frequently Asked Questions
<details> <summary><b>Do I need root access?</b></summary>
No. MobileStudio is designed to run without root access.

</details><details> <summary><b>Can I build native C/C++ projects with the NDK?</b></summary>
Yes. NDK support is available in MobileStudio releases after 26.10.15 Rose.

</details><details> <summary><b>Is JDK 21 supported?</b></summary>
Yes. JDK 21 is supported starting with 26.10.15 Rose and is recommended.

</details><details> <summary><b>Which Gradle versions are supported?</b></summary>
Gradle 8.2, 8.7, 8.8+, 9.7.0, and 9.8.0 are supported. Gradle 8.2 is a legacy version and is not recommended.

</details><details> <summary><b>Can I use AI coding features?</b></summary>
Yes. Starting with 26.10.15 Rose, you can connect AI models through APIs or use supported local models powered by llama.cpp. AI features include code generation, code editing, and analysis.

</details><details> <summary><b>Does MobileStudio support game development?</b></summary>
Yes. MobileStudio supports Vulkan-based rendering, Lua scripting, and Godot Engine for on-device game development.

</details><details> <summary><b>Can MobileStudio run Windows applications?</b></summary>
Clang-based .exe building is supported. Running Windows applications through Box64 + Wine is still under development.

</details><details> <summary><b>Does StudioShell use proot or an emulator?</b></summary>
StudioShell is designed around native arm64-v8a command-line tools and does not require a proot-based Linux environment.

</details><details> <summary><b>Are all features stable?</b></summary>
Not yet. MobileStudio is actively developed, and some features may be experimental or device-dependent.

</details><img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🤝 Contributing · Contributing
Contributions are welcome! 🌷

Fork the repository.
Create a feature branch: git checkout -b feature/my-feature
Commit your changes with a clear message.
Open a Pull Request describing what you changed and why.
For bugs or feature requests, please open a GitHub Issue first so the idea can be discussed.

<div align="center">

</div><img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🐞 Bug Reports · Bug 제보
Please report bugs through GitHub Issues.

Include the following details when possible:

MobileStudio version
Android version
Device information
Steps to reproduce the issue
Logcat output
Error message
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
📜 License · License
See the LICENSE file for license information.

<div align="center">
⬆ Back to top · 🇰🇷 한국어

</div></details><img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
<a id="korean"></a>

<details open> <summary><h2>🇰🇷 한국어</h2></summary>
[!TIP]
⚡ 한눈에 보기 — PC 불필요 · 루트 불필요 · 기기에서 실제 Gradle 빌드 · arm64-v8a 네이티브 도구 · API 및 로컬 모델 AI 코딩 · 네이티브 개발 · Vulkan, Lua, Godot 게임 개발

📑 목차
🌹 Rose — 26.10.15
🔍 MobileStudio가 특별한 이유
🚀 시작하기
🧩 사용 기술
📥 다운로드
📦 버전 안내
⚙️ 지원 현황
✨ 기능
📊 MobileStudio vs 기존 방식
🗺️ 로드맵
📋 권장 사양
❓ 자주 묻는 질문
🤝 기여하기
🐞 버그 제보
📜 라이선스
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🌹 Rose — 26.10.15
**MobileStudio 26.10.15 "Rose"**는 네이티브 빌드 도구, AI 코딩, 게임 개발 기능을 확장한 버전입니다.

🖥️ Windows .exe 빌드 지원
✅ Clang을 이용한 Windows .exe 빌드
🚧 Box64 + Wine을 이용한 .exe 실행 — 개발 중
🛠️ Android 기기에서 더 넓은 개발 환경을 제공하기 위한 확장
🐚 StudioShell
StudioShell은 arm64-v8a용으로 포팅하거나 빌드한 명령줄 도구를 MobileStudio 내부에서 사용할 수 있도록 합니다.

Git
cURL
tar
핵심 명령줄 유틸리티
proot 기반 Linux 환경 대신 ARM64 네이티브 도구를 사용하는 것을 목표로 합니다.

🤖 AI 기반 코딩
26.10.15 Rose부터 API 연결 모델과 llama.cpp 기반 로컬 모델을 이용한 AI 코딩을 지원합니다.

AI 코드 생성
AI 코드 수정
코드 분석
오류 조사
API 모델 연결
llama.cpp를 이용한 로컬 모델 추론
AI가 MobileStudio 내부에서 코드를 작성하고 수정할 수 있도록 설계되었습니다.

🎮 게임 개발
Android 기기에서 직접 게임을 개발할 수 있습니다.

✅ Vulkan 기반 렌더링
✅ Lua 스크립팅
✅ 기기에서 프로젝트 빌드 및 플레이
✅ Godot 엔진 지원
[!NOTE]
기능의 사용 가능 여부와 안정성은 기기 및 프로젝트에 따라 다를 수 있습니다. MobileStudio는 활발히 개발 중입니다.

<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🔍 MobileStudio가 특별한 이유
대부분의 Android 코딩 앱은 코드 편집에 집중하며, 실제 빌드는 PC에서 진행해야 하는 경우가 많습니다. MobileStudio는 Android 기기에서 개발 과정을 직접 수행하는 것을 목표로 합니다.

온디바이스 Gradle 빌드 — Android 프로젝트를 기기에서 직접 컴파일
ARM64 네이티브 도구 — arm64-v8a용으로 빌드된 명령줄 도구 사용
루트 불필요 — Android 앱 환경에서 동작하도록 설계
통합 개발 환경 — 편집기, 진단, 터미널, 빌드 로그, APK 설치
AI 기반 개발 — API 모델 연결 또는 지원되는 로컬 모델 실행
Android를 넘어 — 네이티브 프로젝트와 게임 개발도 지원
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🚀 시작하기
GitHub Releases에서 MobileStudio를 다운로드합니다.
Android 기기에 설치하고 실행합니다.
기존 Gradle 기반 Android 프로젝트를 가져오거나 새 프로젝트를 만듭니다.
프로젝트를 열고 Build 버튼을 누릅니다.
빌드 로그를 확인하고 생성된 APK를 설치합니다.
PC도, 루트도 필요 없습니다. 폰에서 바로 빌드하세요.

<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🧩 사용 기술
<div align="center">





Git





</div><img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
📥 다운로드
<div align="center">


Android 10 이상

</div>
릴리즈	트랙	설명
🌼 Daisy — 26.6.x	안정	안정 버전
🌷 Tulip — 26.7.x	개발	기능 개선
🪻 Lavender — 26.8.x	개발	주요 툴체인 및 플랫폼 개선
🌹 Rose — 26.10.15	개발	AI 모델, 네이티브 빌드, 게임 개발 업데이트
[!NOTE]
최신 APK와 릴리즈 노트는 Releases 페이지에서 확인해주세요. 일부 기능은 실험적일 수 있습니다.

<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
⚙️ 지원 현황
Gradle
버전	상태
Gradle 8.0 이하	❌ 미지원
Gradle 8.2	✅ 지원 — 구버전, 권장하지 않음
Gradle 8.7	✅ 지원
Gradle 8.8 이상	✅ 지원
Gradle 9.7.0	✅ 지원
Gradle 9.8.0	✅ 지원
JDK
버전	상태
JDK 17	✅ 지원
JDK 21	⭐ 권장 — Rose부터 지원
기타 구성 요소
구성 요소	상태
Android SDK	✅ 지원
Android NDK	✅ 지원
CMake	✅ 지원
Python	🔧 수정 중
XML 미리보기	🚧 예정
Jetpack Compose 미리보기	🚧 예정
Windows .exe 빌드	✅ 지원
Windows .exe 실행	🚧 개발 중
AI API 연결	✅ 지원
llama.cpp 로컬 AI	✅ 지원
Vulkan / Lua 게임 개발	✅ 지원
Godot 엔진	✅ 지원
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
✨ 기능
💻 Android 개발
✅ 기기에서 Gradle 빌드
✅ Kotlin 실시간 진단
✅ 코드 완성
✅ 문법 강조
✅ 파일 탐색기
✅ 통합 터미널
✅ APK 설치
✅ 빌드 로그 뷰어
✅ Git 지원
🐚 StudioShell
✅ arm64-v8a 네이티브 명령줄 도구
✅ Git, cURL, tar 및 핵심 유틸리티
✅ proot 기반 환경 불필요
🧰 네이티브 개발
✅ Android NDK 지원
✅ C/C++ 개발
✅ CMake 지원
🖥️ Windows 애플리케이션 개발
✅ Clang을 이용한 .exe 빌드
🚧 Box64 + Wine을 이용한 .exe 실행
🤖 AI
✅ API 연결 AI 모델
✅ llama.cpp 로컬 모델
✅ AI 코드 생성 및 수정
✅ 코드 분석 및 개발 지원
🎮 게임 개발
✅ Vulkan 기반 렌더링
✅ Lua 스크립팅
✅ Godot 엔진 지원
✅ 기기에서 게임 개발
🔄 업데이트
✅ 자동 업데이트 — v26.6.23 Daisy부터 최신 업데이트를 감지하고 다운로드 및 설치할 수 있습니다.
📸 스크린샷
추후 추가 예정입니다.

<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
📊 MobileStudio vs 기존 방식
항목	MobileStudio	기존 PC 방식
PC 필요	❌ 불필요	✅ 일반적으로 필요
루트 필요	❌ 불필요	—
실제 Gradle 빌드	✅ 기기에서 실행	✅
통합 터미널	✅ StudioShell	환경에 따라 다름
AI 코딩	✅ API 및 로컬 모델	환경에 따라 다름
NDK / 네이티브 빌드	✅ 지원	✅
Windows .exe 빌드	✅ Clang	✅
Android에서 .exe 실행	🚧 개발 중	—
게임 개발	✅ Vulkan, Lua, Godot	환경에 따라 다름
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🗺️ 로드맵
기능	상태
Android Gradle 빌드	✅
Kotlin 진단	✅
코드 완성	✅
문법 강조	✅
APK 설치	✅
파일 탐색기	✅
통합 터미널	✅
Git 지원	✅
자동 업데이트	✅
StudioShell	🚧 개발 및 개선 중
JDK 21	✅
Gradle 9.7.0 이상	✅
Android NDK	✅
CMake	✅
Python	🔧 수정 중
XML 미리보기	🚧 예정
Jetpack Compose 미리보기	🚧 예정
.exe 빌드	✅
.exe 실행	🚧 개발 중
AI 어시스턴트	✅
API 및 로컬 AI 모델	✅
Vulkan + Lua 게임 개발	✅
Godot 엔진	✅
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
📋 권장 사양
항목	최소	권장
RAM	4 GB	8 GB 이상
여유 저장공간	15 GB	30 GB 이상
Android	Android 10	Android 13 이상
대규모 프로젝트, 네이티브 빌드, 로컬 AI 모델은 더 많은 메모리와 저장공간이 필요할 수 있습니다.

<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
❓ 자주 묻는 질문
<details> <summary><b>루트 권한이 필요한가요?</b></summary>
아니요. MobileStudio는 루트 권한 없이 동작하도록 설계되었습니다.

</details><details> <summary><b>NDK로 C/C++ 프로젝트를 빌드할 수 있나요?</b></summary>
네. 26.10.15 Rose 이후 버전부터 NDK 지원을 사용할 수 있습니다.

</details><details> <summary><b>JDK 21을 지원하나요?</b></summary>
네. 26.10.15 Rose부터 JDK 21을 지원하며 권장합니다.

</details><details> <summary><b>어떤 Gradle 버전을 지원하나요?</b></summary>
Gradle 8.2, 8.7, 8.8 이상, 9.7.0, 9.8.0을 지원합니다. Gradle 8.2는 구버전이므로 권장하지 않습니다.

</details><details> <summary><b>AI 코딩 기능을 사용할 수 있나요?</b></summary>
네. 26.10.15 Rose부터 API를 통한 모델 연결과 llama.cpp 기반 로컬 모델을 지원합니다. 코드 생성, 수정, 분석 등의 기능을 제공합니다.

</details><details> <summary><b>게임 개발을 지원하나요?</b></summary>
네. Vulkan 렌더링, Lua 스크립팅, Godot 엔진을 이용한 기기 내 게임 개발을 지원합니다.

</details><details> <summary><b>Windows 프로그램을 실행할 수 있나요?</b></summary>
Clang을 이용한 .exe 빌드는 지원합니다. Box64 + Wine을 통한 Windows 애플리케이션 실행은 아직 개발 중입니다.

</details><details> <summary><b>StudioShell은 proot나 에뮬레이터를 사용하나요?</b></summary>
StudioShell은 arm64-v8a 네이티브 명령줄 도구를 기반으로 설계되었으며, proot 기반 Linux 환경을 필요로 하지 않습니다.

</details><details> <summary><b>모든 기능이 안정적인가요?</b></summary>
아직은 아닙니다. MobileStudio는 활발히 개발 중이며 일부 기능은 실험적이거나 기기에 따라 다르게 동작할 수 있습니다.

</details><img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🤝 기여하기
기여를 환영합니다! 🌷

저장소를 Fork합니다.
기능 브랜치를 생성합니다: git checkout -b feature/my-feature
변경 사항을 명확한 메시지로 Commit합니다.
변경 내용과 이유를 설명하는 Pull Request를 생성합니다.
버그 제보나 기능 제안은 PR 전에 GitHub Issue로 먼저 남겨주세요.

<div align="center">

</div><img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
🐞 버그 제보
GitHub Issues를 통해 버그를 제보해주세요.

가능하면 다음 정보를 함께 제공해주세요.

MobileStudio 버전
Android 버전
기기 정보
오류 발생 과정
Logcat
오류 메시지
<img width="100%" height="6" src="https://capsule-render.vercel.app/api?type=rect&color=0:FFD1DC,35:FF8FA3,70:E75480,100:B39DDB&height=6"/>
📜 라이선스
라이선스 정보는 LICENSE 파일을 참고하세요.

<div align="center">
⬆ 맨 위로 · 🇺🇸 English

</div></details><img src="https://capsule-render.vercel.app/api?type=waving&color=0:B39DDB,35:E75480,70:FF8FA3,100:FFD1DC&height=130&section=footer" width="100%"/><div align="center">
📱 MobileStudio
An Android development environment built for Android.

Develop anywhere. Build anywhere.

⭐ If MobileStudio is useful to you, consider starring the repository.

</div>
기술 업무 정확도 향상
Plus(으)로 업그레이드하고 향상된 추론 수준으로 코드를 디버깅하고, 시스템을 파악하며, 수학 또는 과학 문제를 해결하세요.



⋮⋮⋮⋮
