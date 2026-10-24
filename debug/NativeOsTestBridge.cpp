// Standalone JNI registration for Os contract tests, without launching an APK.
// Build with runtime/src/native_os.cpp, the JDK include/{,darwin} directories,
// and -dynamiclib -lcompression. Pass the dylib path to OsChmodTest.
#include <jni.h>
namespace macapk { namespace osnative {
void RegisterNativeOsMethods(JNIEnv*);
}}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    macapk::osnative::RegisterNativeOsMethods(env);
    return JNI_VERSION_1_6;
}
