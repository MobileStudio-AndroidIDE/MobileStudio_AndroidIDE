/*
 * cwd_fix.c — zero-dependency getcwd() override
 *
 * Android NDK 로 빌드하지만 bionic/liblog 심볼을 일절 사용하지 않음.
 * → glibc 기반 BellSoft JDK 환경에서도 LD_PRELOAD 로 로드 가능.
 *
 * JVM platformProperties() 가 getcwd(NULL,0) 을 호출하면 /tmp 반환.
 */

/* 헤더 include 없음 — 외부 심볼 참조 방지 */
typedef unsigned long size_t;
typedef __PTRDIFF_TYPE__ ssize_t;

#define CWD "/tmp"
/* 정적 버퍼: JVM 이 free() 해도 glibc free() 는 힙 외부 포인터를 무시함 */
static char s_buf[8] = {'/','t','m','p',0,0,0,0};

__attribute__((visibility("default")))
char *getcwd(char *buf, size_t size) {
    if (!buf) return s_buf;
    if (size < 5) return (char *)0;
    buf[0]='/'; buf[1]='t'; buf[2]='m'; buf[3]='p'; buf[4]='\0';
    return buf;
}

/* bionic 내부 심볼 */
__attribute__((visibility("default")))
char *__getcwd(char *b, size_t s) __attribute__((alias("getcwd")));

/* glibc get_current_dir_name() */
__attribute__((visibility("default")))
char *get_current_dir_name(void) { return s_buf; }