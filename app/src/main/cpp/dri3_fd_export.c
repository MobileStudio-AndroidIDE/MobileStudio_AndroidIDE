/*
 * dri3_fd_export.c - DRI3 buffer fd export via JNI
 *
 * Extracts the native file descriptor from an Android AHardwareBuffer so that
 * Wine/X11 clients can import the buffer via SCM_RIGHTS on the Unix domain
 * socket (DRI3 BufferFromPixmap).
 *
 * Uses dlopen/dlsym on libandroid.so to resolve AHardwareBuffer_getNativeHandle
 * at runtime, so the module works on API 26+ without linking against
 * libandroid at build time.
 */
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DRI3-FD-EXPORT", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DRI3-FD-EXPORT", __VA_ARGS__)

/* AHardwareBuffer_Handle layout (from NDK headers) */
typedef struct {
    int32_t numFds;
    int32_t numInts;
    int32_t reserved;
    /* fds[] and ints[] follow inline */
} AHBufferHandle;

/* Function pointer type for AHardwareBuffer_getNativeHandle */
typedef int32_t (*AHBuffer_GetNativeHandleFn)(void* buffer, AHBufferHandle** outHandle);

static AHBuffer_GetNativeHandleFn g_getNativeHandle = NULL;

/* Resolve AHardwareBuffer_getNativeHandle from libandroid.so via dlopen */
static AHBuffer_GetNativeHandleFn resolve_get_native_handle(void) {
    if (g_getNativeHandle) return g_getNativeHandle;

    void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        LOGE("dlopen(libandroid.so) failed: %s", dlerror());
        return NULL;
    }

    /* Symbol name may vary by API level; try common variants */
    g_getNativeHandle = (AHBuffer_GetNativeHandleFn)dlsym(lib, "AHardwareBuffer_getNativeHandle");
    if (!g_getNativeHandle) {
        LOGE("dlsym(AHardwareBuffer_getNativeHandle) failed: %s", dlerror());
    } else {
        LOGI("Resolved AHardwareBuffer_getNativeHandle via libandroid.so");
    }

    dlclose(lib);
    return g_getNativeHandle;
}

/*
 * Java_com_aide_mobilestudio_NativeHardwareBuffer_exportFd
 *
 * Parameters:
 *   hardwareBuffer - java object (android.hardware.buffer.AHardwareBuffer)
 *
 * Returns the native file descriptor (fds[0]) or -1 on failure.
 */
JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_NativeHardwareBuffer_exportFd(JNIEnv* env, jobject thiz, jobject hardwareBuffer) {
    if (!hardwareBuffer) {
        LOGE("exportFd: null hardwareBuffer");
        return -1;
    }

    /* Get the native pointer (mNativePtr field) from the Java AHardwareBuffer object */
    jclass cls = (*env)->GetObjectClass(env, hardwareBuffer);
    if (!cls) {
        LOGE("exportFd: GetObjectClass failed");
        return -1;
    }

    jfieldID fid = (*env)->GetFieldID(env, cls, "mNativePtr", "J");
    if (!fid) {
        LOGE("exportFd: field mNativePtr not found (may be obfuscated or API-specific)");
        (*env)->DeleteLocalRef(env, cls);
        return -1;
    }

    jlong nativePtr = (*env)->GetLongField(env, hardwareBuffer, fid);
    (*env)->DeleteLocalRef(env, cls);

    if (nativePtr == 0) {
        LOGE("exportFd: mNativePtr is 0");
        return -1;
    }

    /* Resolve the native function */
    AHBuffer_GetNativeHandleFn getNativeHandle = resolve_get_native_handle();
    if (!getNativeHandle) {
        LOGE("exportFd: AHardwareBuffer_getNativeHandle not available");
        return -1;
    }

    /* Call AHardwareBuffer_getNativeHandle */
    AHBufferHandle* handle = NULL;
    int32_t result = getNativeHandle((void*)(uintptr_t)nativePtr, &handle);
    if (result != 0 || !handle) {
        LOGE("exportFd: AHardwareBuffer_getNativeHandle returned %d, handle=%p", result, (void*)handle);
        return -1;
    }

    /* Extract first fd from the handle */
    if (handle->numFds <= 0) {
        LOGI("exportFd: handle has no fds (numFds=%d)", handle->numFds);
        return -1;
    }

    /* fds[] follows the fixed header inline */
    int32_t* fds = (int32_t*)((uint8_t*)handle + sizeof(AHBufferHandle));
    jint fd = fds[0];

    LOGI("exportFd: extracted fd=%d (numFds=%d, numInts=%d)", fd, handle->numFds, handle->numInts);
    return fd;
}
