/*
 * Stub library providing missing TLS symbol for JDK 21 on Android.
 *
 * The symbol _ZN6Thread12_thr_currentE (Thread::_thr_current) is a
 * thread_local variable that the cross-compiled JDK 21 libjvm.so
 * references but doesn't export. Without this stub, dlopen of
 * libjvm.so fails with "cannot locate symbol".
 *
 * This stub provides the symbol so the linker can resolve it.
 * The actual Thread code will write through this pointer.
 */

/* Non-TLS global - the linker just needs to find the symbol */
__attribute__((visibility("default")))
void* _ZN6Thread12_thr_currentE = 0;
