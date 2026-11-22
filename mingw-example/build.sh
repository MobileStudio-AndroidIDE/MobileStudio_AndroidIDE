#!/bin/bash
# mingw-example 빌드 스크립트 (Linux 호스트)

set -e

echo "=== mingw-example 빌드 ==="

# mingw-w64 gcc 확인
if ! command -v x86_64-w64-mingw32-gcc &> /dev/null; then
    echo "오류: x86_64-w64-mingw32-gcc가 설치되지 않았습니다."
    echo "설치: sudo apt install mingw-w64"
    exit 1
fi

# 컴파일
echo "dodger.c 컴파일 중..."
x86_64-w64-mingw32-gcc -O2 -o dodger.exe dodger.c -lgdi32 -luser32 -lwinmm -mwindows

if [ -f dodger.exe ]; then
    echo "=== 빌드 완료: dodger.exe ($(du -h dodger.exe | cut -f1)) ==="
    echo ""
    echo "실행: MobileStudio > 도구 > Exe Runner > dodger.exe 선택 > 실행"
    echo "또는 Wine으로 직접 실행: wine dodger.exe"
else
    echo "=== 빌드 실패 ==="
    exit 1
fi
