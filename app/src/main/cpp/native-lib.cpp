// JNI shim for nodejs-mobile. Adapted from
// JaneaSystems/nodejs-mobile-samples/android/native-gradle/.../native-lib.cpp
// (MIT). Two responsibilities:
//
//  1. startNodeWithArguments(env, argv) → invokes node::Start on the calling
//     thread, with the args the Kotlin side built. Blocks until Node exits.
//  2. Pipe stdout/stderr to logcat — otherwise Fastify's startup logs vanish.

#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <unistd.h>

// Forward-declared from libnode; the real signature lives in node.h.
namespace node {
    int Start(int argc, char* argv[]);
}

#define LOG_TAG "jss-android"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ----- stdout/stderr → logcat -----------------------------------------------

static int s_pipe_stdout[2];
static int s_pipe_stderr[2];
static pthread_t s_thread_stdout;
static pthread_t s_thread_stderr;

static void* pump(void* arg) {
    int fd = *static_cast<int*>(arg);
    int prio = (fd == s_pipe_stdout[0]) ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR;
    char buf[512];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf) - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = 0;
        __android_log_write(prio, LOG_TAG, buf);
    }
    return nullptr;
}

static void redirect_stdio_to_logcat() {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    pipe(s_pipe_stdout); pipe(s_pipe_stderr);
    dup2(s_pipe_stdout[1], STDOUT_FILENO);
    dup2(s_pipe_stderr[1], STDERR_FILENO);
    pthread_create(&s_thread_stdout, nullptr, pump, &s_pipe_stdout[0]);
    pthread_create(&s_thread_stderr, nullptr, pump, &s_pipe_stderr[0]);
    pthread_detach(s_thread_stdout);
    pthread_detach(s_thread_stderr);
}

// ----- JNI entry point -------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL
Java_live_jss_jss_1android_NodeBridge_startNodeWithArguments(
    JNIEnv* env, jclass /*clazz*/, jobjectArray jArgv) {

    static bool stdio_redirected = false;
    if (!stdio_redirected) {
        redirect_stdio_to_logcat();
        stdio_redirected = true;
    }

    jsize argc = env->GetArrayLength(jArgv);
    if (argc <= 0) {
        LOGE("startNodeWithArguments called with empty argv");
        return -1;
    }

    auto argv = static_cast<char**>(calloc(argc + 1, sizeof(char*)));
    for (jsize i = 0; i < argc; i++) {
        auto jArg = static_cast<jstring>(env->GetObjectArrayElement(jArgv, i));
        const char* utf = env->GetStringUTFChars(jArg, nullptr);
        argv[i] = strdup(utf);
        env->ReleaseStringUTFChars(jArg, utf);
        env->DeleteLocalRef(jArg);
    }
    argv[argc] = nullptr;

    LOGI("node::Start with %d args, argv[0]=%s, argv[1]=%s",
         argc, argv[0], argc > 1 ? argv[1] : "(none)");

    int rc = node::Start(argc, argv);

    LOGI("node::Start returned %d", rc);

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return rc;
}
