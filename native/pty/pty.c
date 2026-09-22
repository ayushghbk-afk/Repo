#include <jni.h>
#include <fcntl.h>
#include <stdlib.h>
#include <unistd.h>
#include <pty.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <signal.h>
#include <termios.h>
#include <string.h>
#include <dirent.h>
#include <errno.h>

/*
 * Lenix PTY bridge — adapted from Stryker's proven terminal implementation.
 * Provides real PTY-backed process spawning for the terminal, with fallback
 * to pipe mode when /dev/ptmx is unavailable.
 *
 * Architecture (see Stryker's terminal.cpp / exec.c):
 * - createSubprocess: openptmx, grantpt, unlockpt, set UTF8, set window size,
 *   fork, setsid, open slave, dup2 to stdin/out/err, close extra fds, chdir,
 *   clearenv + putenv, execvp.
 * - setPtyWindowSize: ioctl TIOCSWINSZ
 * - waitFor: waitpid
 * - close: close fd
 * - openPty: legacy simple openpty for compatibility
 */

static int throw_runtime_exception(JNIEnv *env, const char *message) {
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (exClass != NULL) {
        (*env)->ThrowNew(env, exClass, message);
    }
    return -1;
}

static int create_subprocess(JNIEnv *env,
                             const char *cmd,
                             const char *cwd,
                             char *const argv[],
                             char **envp,
                             int *pProcessId,
                             jint rows,
                             jint cols) {
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        return throw_runtime_exception(env, "Cannot open /dev/ptmx");
    }

    char devname[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname)) != 0) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }

    struct winsize sz = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols,
        .ws_xpixel = 0,
        .ws_ypixel = 0
    };
    ioctl(ptm, TIOCSWINSZ, &sz);

    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        *pProcessId = (int)pid;
        return ptm;
    } else {
        // Child
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(-1);

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        // Close all other fds
        DIR *self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent *entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }

        clearenv();
        if (envp) {
            for (; *envp; ++envp) putenv(*envp);
        }

        if (chdir(cwd) != 0) {
            char *error_message;
            if (asprintf(&error_message, "chdir(\"%s\")", cwd) == -1)
                error_message = "chdir()";
            perror(error_message);
            fflush(stderr);
        }
        execvp(cmd, argv);
        char *error_message;
        if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1)
            error_message = "exec()";
        perror(error_message);
        _exit(1);
    }
}

/*
 * ADR-009: openpty(3) master fd for the terminal. Returns -1 on failure so
 * Kotlin can fall back to pipe-backed stdio.
 */
JNIEXPORT jint JNICALL
Java_com_lenix_nativebridge_NativeBridge_nativeOpenPty(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    int master = -1;
    int slave = -1;
    if (openpty(&master, &slave, NULL, NULL, NULL) != 0) {
        return -1;
    }
    close(slave);
    fcntl(master, F_SETFD, FD_CLOEXEC);
    return master;
}

JNIEXPORT jint JNICALL
Java_com_lenix_nativebridge_NativeBridge_nativeCreateSubprocess(JNIEnv *env,
                                                                  jobject thiz,
                                                                  jstring cmd,
                                                                  jstring cwd,
                                                                  jobjectArray args,
                                                                  jobjectArray envVars,
                                                                  jintArray processIdArray,
                                                                  jint rows,
                                                                  jint cols) {
    (void)thiz;
    jsize size = args ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = NULL;
    if (size > 0) {
        argv = (char **)malloc((size + 1) * sizeof(char *));
        if (!argv) return throw_runtime_exception(env, "Couldn't allocate argv array");
        for (int i = 0; i < size; ++i) {
            jstring arg_java_string = (jstring)(*env)->GetObjectArrayElement(env, args, i);
            const char *arg_utf8 = (*env)->GetStringUTFChars(env, arg_java_string, NULL);
            if (!arg_utf8) {
                free(argv);
                return throw_runtime_exception(env, "GetStringUTFChars() failed for argv");
            }
            argv[i] = strdup(arg_utf8);
            (*env)->ReleaseStringUTFChars(env, arg_java_string, arg_utf8);
        }
        argv[size] = NULL;
    }

    size = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char **envp = NULL;
    if (size > 0) {
        envp = (char **)malloc((size + 1) * sizeof(char *));
        if (!envp) {
            if (argv) {
                for (char **tmp = argv; *tmp; ++tmp) free(*tmp);
                free(argv);
            }
            return throw_runtime_exception(env, "malloc() for envp array failed");
        }
        for (int i = 0; i < size; ++i) {
            jstring env_java_string = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
            const char *env_utf8 = (*env)->GetStringUTFChars(env, env_java_string, 0);
            if (!env_utf8) {
                if (argv) {
                    for (char **tmp = argv; *tmp; ++tmp) free(*tmp);
                    free(argv);
                }
                for (int j = 0; j < i; ++j) free(envp[j]);
                free(envp);
                return throw_runtime_exception(env, "GetStringUTFChars() failed for env");
            }
            envp[i] = strdup(env_utf8);
            (*env)->ReleaseStringUTFChars(env, env_java_string, env_utf8);
        }
        envp[size] = NULL;
    }

    int procId = 0;
    const char *cmd_cwd = (*env)->GetStringUTFChars(env, cwd, NULL);
    const char *cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    int ptm = create_subprocess(env, cmd_utf8, cmd_cwd, argv, envp, &procId, rows, cols);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    (*env)->ReleaseStringUTFChars(env, cwd, cmd_cwd);

    if (argv) {
        for (char **tmp = argv; *tmp; ++tmp) free(*tmp);
        free(argv);
    }
    if (envp) {
        for (char **tmp = envp; *tmp; ++tmp) free(*tmp);
        free(envp);
    }

    // If create_subprocess threw, ptm is -1 and exception pending
    if (ptm < 0) {
        return -1;
    }

    jint *pProcId = (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) {
        close(ptm);
        return throw_runtime_exception(env, "GetPrimitiveArrayCritical(processIdArray) failed");
    }
    *pProcId = procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);

    return ptm;
}

JNIEXPORT void JNICALL
Java_com_lenix_nativebridge_NativeBridge_nativeSetPtyWindowSize(JNIEnv *env,
                                                                  jobject thiz,
                                                                  jint fd, jint rows,
                                                                  jint cols) {
    (void)env;
    (void)thiz;
    struct winsize sz = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols
    };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL
Java_com_lenix_nativebridge_NativeBridge_nativeSetPtyUTF8Mode(JNIEnv *env, jobject thiz,
                                                                jint fd) {
    (void)env;
    (void)thiz;
    struct termios tios;
    if (tcgetattr(fd, &tios) == 0) {
        if ((tios.c_iflag & IUTF8) == 0) {
            tios.c_iflag |= IUTF8;
            tcsetattr(fd, TCSANOW, &tios);
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_lenix_nativebridge_NativeBridge_nativeWaitFor(JNIEnv *env, jobject thiz,
                                                         jint pid) {
    (void)env;
    (void)thiz;
    int status;
    if (waitpid(pid, &status, 0) < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    } else {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_lenix_nativebridge_NativeBridge_nativeClose(JNIEnv *env, jobject thiz,
                                                       jint fileDescriptor) {
    (void)env;
    (void)thiz;
    close(fileDescriptor);
}

JNIEXPORT jboolean JNICALL
Java_com_lenix_nativebridge_NativeBridge_nativeKillpg(JNIEnv *env, jobject thiz,
                                                        jlong pid, jint signal) {
    (void)env;
    (void)thiz;
    // Kill process group: negative pid kills group
    int ret = killpg((pid_t)pid, signal);
    if (ret == 0) return JNI_TRUE;
    // Fallback: try kill with negative pid (same as killpg)
    ret = kill(-(pid_t)pid, signal);
    if (ret == 0) return JNI_TRUE;
    // Last resort: kill single pid
    ret = kill((pid_t)pid, signal);
    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}
