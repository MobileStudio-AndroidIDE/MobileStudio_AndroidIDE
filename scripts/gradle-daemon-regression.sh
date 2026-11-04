#!/usr/bin/env bash
# Gradle-daemon chain regression tests (A-F) — MobileStudio debug build.
#
#   A  $JAVA_HOME/bin/java -version          (wrapper -> launcher -> JVM)
#   B  $JAVA_HOME/bin/javac -version
#   C  JVM forks its own $JAVA_HOME/bin/java  via ProcessBuilder (fork/exec chain)
#   D  Gradle 9.7 daemon starts on first build
#   E  second build reuses the daemon
#   F  no stale GradleDaemon uses a previous APK's native libs
#
# Usage:
#   ./gradle-daemon-regression.sh run      # A,B,C,F + D/E judged from recent logcat
#   ./gradle-daemon-regression.sh watch    # real-time D/E: build twice in the app,
#                                          # the script watches logcat and judges
#   ./gradle-daemon-regression.sh report   # dump the [Wrapper]/[Exec]/GradleExec_C trail
set -u
PKG=com.aide.mobilestudio
ADB=${ADB:-adb}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PASS=0; FAIL=0

ok()  { printf 'PASS  %s\n' "$*"; PASS=$((PASS+1)); }
bad() { printf 'FAIL  %s\n' "$*"; FAIL=$((FAIL+1)); }

sh_on() { # run a shell command as the app, its exit code preserved.
    # adb shell joins its argv with spaces before handing the string to the
    # REMOTE shell, which would split our 'cmd; cmd2'. So build one properly
    # quoted remote command string ourselves.
    local esc
    esc=$(printf '%s' "$1" | sed "s/'/'\\\\''/g")
    $ADB shell "run-as $PKG sh -c '$esc'" </dev/null 2>&1 | tr -d '\r'
}

die() { echo "ERROR: $*" >&2; exit 2; }

$ADB get-state >/dev/null 2>&1 || die "no device (adb get-state failed)"

JH=$(sh_on 'cd files/toolchain/jdk 2>/dev/null && pwd -P' | tail -1)
case "$JH" in /data/*) ;; *) JH="/data/data/$PKG/files/toolchain/jdk" ;; esac
echo "== JAVA_HOME (canonical on device): $JH"

# ---------- A: java -version through the wrapper ----------
OUT=$(sh_on "$JH/bin/java -version"); RC=$?
if [ $RC -eq 0 ] && printf '%s' "$OUT" | grep -qiE 'openjdk version|"[0-9]+'; then
    ok "A  java -version rc=0 :: $(printf '%s' "$OUT" | grep -iE 'version' | head -1)"
else
    bad "A  java -version rc=$RC :: $(printf '%s' "$OUT" | tr '\n' '|' | cut -c1-400)"
fi

# ---------- B: javac -version ----------
OUT=$(sh_on "$JH/bin/javac -version"); RC=$?
if [ $RC -eq 0 ] && printf '%s' "$OUT" | grep -qE '^javac [0-9]'; then
    ok "B  javac -version rc=0 :: $(printf '%s' "$OUT" | head -1)"
else
    bad "B  javac -version rc=$RC :: $(printf '%s' "$OUT" | tr '\n' '|' | cut -c1-400)"
fi

# ---------- C: ProcessBuilder child fork (the Gradle-daemon mechanism) ----------
WORK=$(mktemp -d)
    # --release 17: the device JDK is Java 17 (class v61); host javac 21 emits v65
    if ! javac --release 17 -d "$WORK" "$SCRIPT_DIR/regression/PBChild.java" 2>"$WORK/javac.err"; then
    bad "C  host javac failed: $(cat "$WORK/javac.err")"
else
    # shell uid can read /data/local/tmp, the app uid cannot: pipe through
    # run-as into app storage, then run the class from there.
    $ADB push "$WORK/PBChild.class" /data/local/tmp/PBChild.class >/dev/null 2>&1
    $ADB shell "cat /data/local/tmp/PBChild.class | run-as $PKG sh -c 'cat > files/PBChild.class'" </dev/null >/dev/null 2>&1
    OUT=$(sh_on "$JH/bin/java -cp /data/data/$PKG/files PBChild"); RC=$?
    if [ $RC -eq 0 ] && printf '%s' "$OUT" | grep -q 'PB_CHILD_EXIT=0'; then
        ok "C  ProcessBuilder child rc=0 :: $(printf '%s' "$OUT" | grep 'PB_CHILD_OUT=' | cut -c1-160)"
    else
        bad "C  rc=$RC :: $(printf '%s' "$OUT" | tr '\n' '|' | cut -c1-500)"
    fi
fi
rm -rf "$WORK"

# ---------- F: no stale GradleDaemon holds a previous APK's lib dir ----------
APKPATH=$($ADB shell pm path "$PKG" </dev/null 2>/dev/null | head -1 | tr -d '\r' | sed 's/^package://')
LIBDIR=$(dirname "$APKPATH")/lib/arm64
STALE=0; RUNNING=0
for PID in $($ADB shell "ps -A -o PID,NAME" </dev/null 2>/dev/null | tr -d '\r' | awk '$2 ~ /GradleDaemon/ {print $1}'); do
    RUNNING=$((RUNNING+1))
    ENV_LIB=$(sh_on "tr '\0' '\n' < /proc/$PID/environ | sed -n 's/^MOBILESTUDIO_NATIVE_LIB_DIR=//p'")
    if [ "$ENV_LIB" != "$LIBDIR" ]; then
        STALE=$((STALE+1))
        bad "F  GradleDaemon $PID lib=$ENV_LIB (expected $LIBDIR)"
    fi
done
if [ $RUNNING -eq 0 ]; then
    ok "F  no running GradleDaemon (nothing stale)"
elif [ $STALE -eq 0 ]; then
    ok "F  $RUNNING GradleDaemon(s) all use current APK lib ($LIBDIR)"
fi

# ---------- D/E: judge from recent logcat ----------
judge_de() {
    LOG=$(printf '%s' "$1")
    DID_START=$(printf '%s' "$LOG" | grep -c 'Starting a Gradle Daemon' || true)
    BROKEN=$(printf '%s' "$LOG" | grep -cE "problem occurred starting process|error=-" || true)
    BUILT=$(printf '%s' "$LOG" | grep -cE 'BUILD SUCCESSFUL|BUILD FAILED' || true)
    REUSED=$(printf '%s' "$LOG" | grep -ciE 'reus|re-using|reusing' || true)
    if [ "$BROKEN" -gt 0 ]; then
        bad "D/E daemon launch error present ($BROKEN hit(s)) — see '$(printf '%s' "$LOG" | grep -E "problem occurred|error=-" | tail -1 | cut -c1-200)'"
    elif [ "$DID_START" -eq 0 ]; then
        echo "D/E no 'Starting a Gradle Daemon' in captured log — build once/twice in the app"
    elif [ "$BUILT" -ge 1 ]; then
        ok "D  daemon started and build progressed ($BUILT terminal state(s))"
        if [ "$REUSED" -gt 0 ] || [ "$BUILT" -ge 2 ]; then
            ok "E  reuse signal: reuse-hits=$REUSED terminal-states=$BUILT"
        else
            echo "E  build once more in the app to observe daemon reuse"
        fi
    else
        echo "D  daemon start requested; waiting for build to progress"
    fi
}

MODE=${1:-run}
LOGFILTER='MobileStudioBuild:V GradleExec_C:V NativeExec:V GradleRunner:V MobileStudioJavaLauncher:V ExecRedirect:V AndroidRuntime:E'
case "$MODE" in
    report)
        $ADB logcat -d -s $LOGFILTER 2>/dev/null | tail -250
        ;;
    watch)
        echo "== Watching logcat — build TWICE in the app (D then E), Ctrl-C to stop"
        $ADB logcat -c </dev/null 2>/dev/null || true
        CAP=""
        while true; do
            LINE=$($ADB logcat -d -s $LOGFILTER 2>/dev/null | tail -400)
            judge_de "$LINE"
            sleep 5
        done
        ;;
    run|*)
        judge_de "$($ADB logcat -d -s $LOGFILTER 2>/dev/null | tail -400)"
        ;;
esac

echo "== result: PASS=$PASS FAIL=$FAIL"
[ $FAIL -eq 0 ]
