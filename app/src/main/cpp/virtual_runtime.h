#ifndef VIRTUAL_RUNTIME_H
#define VIRTUAL_RUNTIME_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeInitialize(
    JNIEnv *env, jobject thiz,
    jstring runtimeDir,
    jstring nativeLibDir,
    jstring codeCacheDir);

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeShutdown(
    JNIEnv *env, jobject thiz,
    jlong handle);

JNIEXPORT jlong JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeExecute(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring executable,
    jobjectArray args,
    jobjectArray envVars,
    jstring cwd,
    jint timeoutMs,
    jboolean createProcessGroup);

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeReadStdout(
    JNIEnv *env, jobject thiz,
    jlong processHandle,
    jint timeoutMs);

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeReadStderr(
    JNIEnv *env, jobject thiz,
    jlong processHandle,
    jint timeoutMs);

JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeWait(
    JNIEnv *env, jobject thiz,
    jlong processHandle);

JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeWaitTimeout(
    JNIEnv *env, jobject thiz,
    jlong processHandle,
    jint timeoutMs);

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeKill(
    JNIEnv *env, jobject thiz,
    jlong processHandle);

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeKillGroup(
    JNIEnv *env, jobject thiz,
    jlong processHandle);

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeFreeProcess(
    JNIEnv *env, jobject thiz,
    jlong processHandle);

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeResolveDependencies(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring executable);

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeAnalyzeElf(
    JNIEnv *env, jobject thiz,
    jstring executable);

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeFindExecutable(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring name);

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeAddSearchPath(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring path);

JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeIsArm64Elf(
    JNIEnv *env, jobject thiz,
    jstring path);

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeGetSearchPaths(
    JNIEnv *env, jobject thiz,
    jlong handle);

JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeVfsAddMapping(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring virtualPath,
    jstring realPath);

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeVfsResolvePath(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring path);

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeGetLinkerInfo(
    JNIEnv *env, jobject thiz,
    jstring executable);

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeCheckPathSecurity(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring path,
    jstring root);

#ifdef __cplusplus
}
#endif

#endif
