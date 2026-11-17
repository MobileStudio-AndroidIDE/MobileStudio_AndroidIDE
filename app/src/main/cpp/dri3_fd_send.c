/*
 * dri3_fd_send.c - Send a file descriptor over a Unix domain socket via SCM_RIGHTS
 *
 * Allows the MiniXServer to pass an exported AHardwareBuffer fd to a connected
 * Wine/X11 client (DRI3 BufferFromPixmap response) using ancillary data on a
 * Unix domain socket. Java's Socket API does not expose SCM_RIGHTS, so this is
 * implemented in JNI.
 *
 * JNI function:
 *   Java_com_aide_mobilestudio_NativeSocketFdSender_sendFd(
 *       JNIEnv*, jobject, jobject targetSocket, jint fdToSend)
 * Returns 0 on success, -1 on failure.
 *
 * The [targetSocket] is a java.net.Socket (or LocalSocket) instance. We resolve
 * its FileDescriptor via getFileDescriptor() reflection, extract the raw int fd
 * from FileDescriptor.fd, then send it via sendmsg(SCM_RIGHTS).
 */
#include <jni.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DRI3-FD-SEND", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DRI3-FD-SEND", __VA_ARGS__)

static jmethodID g_getFileDescriptor = NULL;
static jfieldID g_fileDescriptor_fd = NULL;

/* Cache reflection IDs lazily */
static jint resolve_fd_from_socket(JNIEnv* env, jobject targetSocket, jint* outFd) {
    jclass sockCls = (*env)->GetObjectClass(env, targetSocket);
    if (!sockCls) {
        LOGE("sendFd: GetObjectClass failed");
        return -1;
    }

    if (!g_getFileDescriptor) {
        g_getFileDescriptor = (*env)->GetMethodID(env, sockCls, "getFileDescriptor", "()Ljava/io/FileDescriptor;");
        if (!g_getFileDescriptor) {
            LOGE("sendFd: GetMethodID(getFileDescriptor) failed");
            (*env)->DeleteLocalRef(env, sockCls);
            return -1;
        }
    }

    jobject fdObj = (*env)->CallObjectMethod(env, targetSocket, g_getFileDescriptor);
    if (!fdObj || (*env)->ExceptionOccurred(env)) {
        (*env)->ExceptionClear(env);
        LOGE("sendFd: getFileDescriptor() returned null or threw");
        (*env)->DeleteLocalRef(env, sockCls);
        return -1;
    }

    if (!g_fileDescriptor_fd) {
        jclass fdCls = (*env)->GetObjectClass(env, fdObj);
        g_fileDescriptor_fd = (*env)->GetFieldID(env, fdCls, "fd", "I");
        (*env)->DeleteLocalRef(env, fdCls);
        if (!g_fileDescriptor_fd) {
            LOGE("sendFd: GetFieldID(FileDescriptor.fd) failed");
            (*env)->DeleteLocalRef(env, fdObj);
            (*env)->DeleteLocalRef(env, sockCls);
            return -1;
        }
    }

    jint fd = (*env)->GetIntField(env, fdObj, g_fileDescriptor_fd);
    (*env)->DeleteLocalRef(env, fdObj);
    (*env)->DeleteLocalRef(env, sockCls);
    *outFd = fd;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_NativeSocketFdSender_sendFd(
        JNIEnv* env, jobject thiz, jobject targetSocket, jint fdToSend) {

    if (!targetSocket || fdToSend < 0) {
        LOGE("sendFd: invalid args (socket=%p, toSend=%d)", (void*)targetSocket, fdToSend);
        return -1;
    }

    jint targetFd = -1;
    if (resolve_fd_from_socket(env, targetSocket, &targetFd) != 0 || targetFd < 0) {
        LOGE("sendFd: could not resolve target socket fd");
        return -1;
    }

    struct msghdr msg = {0};
    struct iovec iov[1];
    char dummy = 0;
    iov[0].iov_base = &dummy;
    iov[0].iov_len = 1;
    msg.msg_iov = iov;
    msg.msg_iovlen = 1;

    union {
        struct cmsghdr align;
        char buf[CMSG_SPACE(sizeof(int))];
    } cmsgbuf;
    msg.msg_control = cmsgbuf.buf;
    msg.msg_controllen = sizeof(cmsgbuf.buf);

    struct cmsghdr* cptr = CMSG_FIRSTHDR(&msg);
    cptr->cmsg_level = SOL_SOCKET;
    cptr->cmsg_type = SCM_RIGHTS;
    cptr->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cptr), &fdToSend, sizeof(int));

    ssize_t n = sendmsg(targetFd, &msg, 0);
    if (n < 0) {
        LOGE("sendFd: sendmsg failed: %s (errno=%d)", strerror(errno), errno);
        return -1;
    }

    LOGI("sendFd: sent fd=%d over socket=%d (bytes=%zd)", fdToSend, targetFd, n);
    return 0;
}
