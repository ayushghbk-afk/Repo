#include <jni.h>
#include <signal.h>
#include <sys/types.h>
#include <unistd.h>

JNIEXPORT jboolean JNICALL
Java_com_lenix_nativebridge_NativeBridge_nativeKillpg(JNIEnv *env, jobject thiz, jlong pid, jint sig) {
    (void)env;
    (void)thiz;
    if (pid <= 0) return JNI_FALSE;
    if (killpg((pid_t)pid, sig) == 0) return JNI_TRUE;
    if (kill((pid_t)pid, sig) == 0) return JNI_TRUE;
    return JNI_FALSE;
}
