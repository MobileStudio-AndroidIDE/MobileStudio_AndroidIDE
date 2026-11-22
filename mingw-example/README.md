# mingw-example - 간단한 GUI 게임 (Windows)

이 프로젝트는 MobileStudio의 **도구 > Exe Runner**에서 실행할 수 있는 간단한 GUI 게임입니다.

## 게임 설명

**Dodger** - 화살표 키(←/→)로 플레이어(파란색 바)를 이동시켜 떨어지는 장애물(빨간색 사각형)을 피하는 게임입니다.

- 장애물을 피할 때마다 점수 +10
- 장애물에 닿으면 게임 오버 (스페이스바로 재시작)

## 빌드 방법

### Linux 호스트 (mingw-w64 교차 컴파일)

```bash
# mingw-w64 설치 (Ubuntu/Debian)
sudo apt install mingw-w64

# 컴파일
x86_64-w64-mingw32-gcc -O2 -o dodger.exe dodger.c -lgdi32 -luser32 -lwinmm -mwindows
```

### Windows 호스트 (MinGW)

```bash
gcc -O2 -o dodger.exe dodger.c -lgdi32 -luser32 -lwinmm -mwindows
```

## MobileStudio에서 실행

1. MobileStudio 앱 실행
2. **도구 > Exe Runner** 선택
3. `dodger.exe` 파일이 `toolchain/exe-runner/` 디렉토리에 있는지 확인
4. **실행** 버튼 클릭
5. Wine + X11로 게임이 실행됩니다 (X 서버가 실행 중이어야 함)

## 파일 구성

| 파일 | 설명 |
|------|------|
| `dodger.c` | 게임 소스 코드 (Win32 API + GDI) |
| `dodger.exe` | 컴파일된 Windows 실행 파일 (PE32+, x86-64) |
| `build.sh` | Linux에서 mingw로 컴파일하는 스크립트 |
