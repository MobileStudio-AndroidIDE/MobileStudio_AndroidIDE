package com.aide.mobilestudio

import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * NativeHardwareBuffer - Android AHardwareBuffer의 네이티브 파일 디스크립터(fd)를
 * JNI를 통해 추출하여 X11/DRI3 버퍼 공유에 사용합니다.
 *
 * Wine/GLX 클라이언트는 DRI3 BufferFromPixmap 요청 시 이 fd를 SCM_RIGHTS로
 * 전달받아 Android 하드웨어 버퍼를 직접 메모리 맵핑할 수 있습니다.
 *
 * minSdk 26 호환을 위해 AHardwareBuffer_getNativeHandle은 libandroid.so에서
 * dlopen/dlsym으로 런타임에 해결됩니다 (JNI 레이어에서 처리).
 */
object NativeHardwareBuffer {
    private const val TAG = "NativeHardwareBuffer"

    /** 라이브러리 로드 상태 */
    @Volatile private var isLoaded = false

    /**
     * 네이티브 라이브러리 (libdri3_fd_export.so) 로드.
     * 앱 시작 시 한 번 호출됩니다.
     */
    fun load() {
        if (isLoaded) return
        try {
            System.loadLibrary("dri3_fd_export")
            isLoaded = true
            Log.i(TAG, "libdri3_fd_export.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Failed to load libdri3_fd_export.so: ${e.message}")
            // Fallback: fd export will return -1 (dummy fd path)
        }
    }

    /**
     * [hardwareBuffer]의 네이티브 파일 디스크립터를 반환합니다.
     * 라이브러리가 로드되지 않았거나 fd를 추출할 수 없으면 -1을 반환합니다.
     */
    fun exportFd(hardwareBuffer: Any?): Int {
        if (!isLoaded || hardwareBuffer == null) return -1
        return exportFdNative(hardwareBuffer)
    }

    /** JNI 네이티브 메서드 선언 */
    private external fun exportFdNative(hardwareBuffer: Any): Int
}
