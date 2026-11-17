package com.aide.mobilestudio

import android.util.Log

/**
 * NativeSocketFdSender - Unix 도메인 소켓을 통해 파일 디스크립터를
 * SCM_RIGHTS ancillary 데이터로 원격 피어(Wine X11 클라이언트)에 전달합니다.
 *
 * Java의 Socket API는 SCM_RIGHTS를 지원하지 않으므로, JNI(libdri3_fd_send.so)를
 * 통해 sendmsg()로 fd를 전송합니다.
 *
 * 사용 흐름:
 *   1. MiniXServer가 클라이언트 연결 시 소켓 fd를 현재 스레드 컨텍스트에 등록
 *   2. DRI3 BufferFromPixmap 요청 처리 시, exportFd()로 확보한 fd를
 *      sendFd(clientSocketFd, exportedFd)로 클라이언트에 전달
 */
object NativeSocketFdSender {
    private const val TAG = "NativeSocketFdSender"

    @Volatile private var isLoaded = false

    fun load() {
        if (isLoaded) return
        try {
            System.loadLibrary("dri3_fd_send")
            isLoaded = true
            Log.i(TAG, "libdri3_fd_send.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Failed to load libdri3_fd_send.so: ${e.message}")
        }
    }

    /**
     * [targetSocket] 소켓을 통해 [fdToSend]를 SCM_RIGHTS로 전송합니다.
     * 성공 시 0, 실패 시 -1을 반환합니다.
     */
    fun sendFd(targetSocket: Any?, fdToSend: Int): Int {
        if (!isLoaded || targetSocket == null) return -1
        return sendFdNative(targetSocket, fdToSend)
    }

    private external fun sendFdNative(targetSocket: Any, fdToSend: Int): Int
}
