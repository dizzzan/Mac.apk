#include "macapk/apk_exec_path.h"
#include <dlfcn.h>
#include <jni.h>
#include <string>

namespace {
using ForkAndExec = jint (*)(JNIEnv*, jobject, jint, jbyteArray, jbyteArray,
    jbyteArray, jint, jbyteArray, jint, jbyteArray, jintArray, jboolean);
ForkAndExec host_fork_and_exec;

std::string bytes(JNIEnv* env, jbyteArray value) {
    if (!value) return {};
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte*>(result.data()));
    return result;
}

jint launch(JNIEnv* env, jobject self, jint mode, jbyteArray helper,
    jbyteArray program, jbyteArray arguments, jint argc, jbyteArray environment,
    jint envc, jbyteArray directory, jintArray fds, jboolean merge_error) {
    std::string path = bytes(env, program), cwd = bytes(env, directory);
    if (env->ExceptionCheck()) return -1;
    char launcher[PATH_MAX];
    if (!macapk::ApkExecLauncher(path.c_str(), cwd.c_str(), launcher, sizeof launcher))
        return host_fork_and_exec(env, self, mode, helper, program, arguments,
            argc, environment, envc, directory, fds, merge_error);

    // OpenJDK builds argv[0] from program. Carry the caller's original spelling
    // as the first argument; the sidecar helper removes its own argv[0]. All
    // pipe, environment, working-directory and wait semantics remain JDK-owned.
    std::string args = path;
    if (args.empty() || args.back() != '\0') args.push_back('\0');
    args += bytes(env, arguments);
    if (env->ExceptionCheck()) return -1;
    jbyteArray new_program = env->NewByteArray(std::strlen(launcher) + 1);
    if (!new_program) return -1;
    jbyteArray new_args = env->NewByteArray(args.size());
    if (!new_args) { env->DeleteLocalRef(new_program); return -1; }
    env->SetByteArrayRegion(new_program, 0, std::strlen(launcher) + 1,
        reinterpret_cast<const jbyte*>(launcher));
    env->SetByteArrayRegion(new_args, 0, args.size(), reinterpret_cast<const jbyte*>(args.data()));
    jint result = env->ExceptionCheck() ? -1 : host_fork_and_exec(env, self, mode,
        helper, new_program, new_args, argc + 1, environment, envc, directory, fds, merge_error);
    env->DeleteLocalRef(new_program);
    env->DeleteLocalRef(new_args);
    return result;
}
} // namespace

bool RegisterNativeProcessBridge(JNIEnv* env) {
    // The bundled OpenJDK 21 method is checked by descriptor before replacing
    // its binding. This delegates to the installed JDK's own implementation.
    jclass process = env->FindClass("java/lang/ProcessImpl");
    if (!process) return false;
    constexpr char signature[] = "(I[B[B[BI[BI[B[IZ)I";
    if (!env->GetMethodID(process, "forkAndExec", signature)) {
        env->DeleteLocalRef(process); return false;
    }
    host_fork_and_exec = reinterpret_cast<ForkAndExec>(
        dlsym(RTLD_DEFAULT, "Java_java_lang_ProcessImpl_forkAndExec"));
    if (!host_fork_and_exec) { env->DeleteLocalRef(process); return false; }
    JNINativeMethod method{const_cast<char*>("forkAndExec"),
        const_cast<char*>(signature), reinterpret_cast<void*>(launch)};
    bool ok = env->RegisterNatives(process, &method, 1) == JNI_OK;
    env->DeleteLocalRef(process);
    return ok;
}
