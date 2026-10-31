#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Isolated capability probe only: no fallback to overwriting rename or copy. */
JNIEXPORT jint JNICALL
Java_dev_notecore_probes_AtomicPromoteProbe_renameNoReplace(
        JNIEnv *env, jclass type, jstring source, jstring target) {
    (void)type;
    const char *from = (*env)->GetStringUTFChars(env, source, NULL);
    if (from == NULL) return ENOMEM;
    const char *to = (*env)->GetStringUTFChars(env, target, NULL);
    if (to == NULL) { (*env)->ReleaseStringUTFChars(env, source, from); return ENOMEM; }
    int result = syscall(__NR_renameat2, AT_FDCWD, from, AT_FDCWD, to, RENAME_NOREPLACE);
    int error = result == 0 ? 0 : errno;
    (*env)->ReleaseStringUTFChars(env, target, to);
    (*env)->ReleaseStringUTFChars(env, source, from);
    return error;
}
