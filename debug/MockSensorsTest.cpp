// Real JNI/NDK sensor queues: feature catalog, initial/change-only delivery,
// independent consumers and re-enable, plus unchanged continuous sensors.
#include <jni.h>
#include <dlfcn.h>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <chrono>
#include <cstdint>
namespace macapk { void RegisterAllNativeMethods(JNIEnv*); }
struct Event {
    int32_t version, sensor, type, reserved;
    int64_t timestamp;
    float data[16];
    uint32_t flags;
    int32_t reserved1[3];
};
static_assert(sizeof(Event) == 104);
int main(int argc, char** argv) {
    assert(argc == 3);
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!shim) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    JavaVM* vm{}; JNIEnv* env{};
    std::string cp = std::string("-Djava.class.path=") + argv[2];
    std::string light = std::string("-Dtest.light=") + (std::getenv("MACAPK_TEST_LIGHT") ? std::getenv("MACAPK_TEST_LIGHT") : "200");
    JavaVMOption options[] = {{cp.data(), nullptr}, {(char*)"-Xcheck:jni", nullptr}, {light.data(), nullptr}};
    JavaVMInitArgs args{}; args.version = JNI_VERSION_1_8; args.nOptions = 3; args.options = options;
    assert(JNI_CreateJavaVM(&vm, (void**)&env, &args) == JNI_OK);
    auto init = (void(*)(JavaVM*))dlsym(shim, "__macapk_init_assets");
    assert(init); init(vm); macapk::RegisterAllNativeMethods(env);
    jclass test = env->FindClass("MockSensorsTest");
    assert(test && !env->ExceptionCheck());
    auto run = env->GetStaticMethodID(test, "run", "()V"); assert(run && !env->ExceptionCheck());
    env->CallStaticVoidMethod(test, run);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); return 1; }
#define FN(name, signature) auto name = reinterpret_cast<signature>(dlsym(shim, "__macapk_arm64_" #name)); assert(name)
    FN(ASensorManager_getInstance, void*(*)());
    FN(ASensorManager_getSensorList, int(*)(void*, void const* const**));
    FN(ASensorManager_getDefaultSensor, void*(*)(void*, int));
    FN(ALooper_prepare, void*(*)(int));
    FN(ASensorManager_createEventQueue, void*(*)(void*,void*,int,void*,void*));
    FN(ASensorManager_destroyEventQueue, int(*)(void*,void*));
    FN(ASensorEventQueue_registerSensor, int(*)(void*,void const*,int32_t,int64_t));
    FN(ASensorEventQueue_disableSensor, int(*)(void*,void const*));
    FN(ASensorEventQueue_getEvents, long(*)(void*,void*,size_t));
    FN(ASensorEventQueue_hasEvents, int(*)(void*));
    void* manager = ASensorManager_getInstance();
    bool enabled = std::getenv("MACAPK_MOCK_SENSORS") && std::string(std::getenv("MACAPK_MOCK_SENSORS")) == "1";
    assert(ASensorManager_getSensorList(manager, nullptr) == (enabled ? 13 : 7));
    auto lightSensor = ASensorManager_getDefaultSensor(manager, 5);
    if (!enabled) { assert(!lightSensor); std::puts("PASS NDK default catalog"); return 0; }
    assert(lightSensor);
    auto looper = ALooper_prepare(1); assert(looper);
    auto q1 = ASensorManager_createEventQueue(manager, looper, 8, nullptr, nullptr);
    auto q2 = ASensorManager_createEventQueue(manager, looper, 9, nullptr, nullptr);
    assert(q1 && q2);
    auto receive = [&](void* queue, int type, float value) {
        Event event{};
        for (int i = 0; i < 100; i++) {
            long n = ASensorEventQueue_getEvents(queue, &event, 1);
            if (n) { assert(n == 1 && event.type == type && event.data[0] == value && event.timestamp > 0); return; }
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
        assert(!"NDK sensor event timed out");
    };
    assert(ASensorEventQueue_registerSensor(q1, lightSensor, 10000, 0) == 0);
    assert(ASensorEventQueue_registerSensor(q2, lightSensor, 10000, 0) == 0);
    receive(q1, 5, 777); receive(q2, 5, 777);
    std::this_thread::sleep_for(std::chrono::milliseconds(80));
    Event event{};
    assert(ASensorEventQueue_getEvents(q1, &event, 1) == 0);
    assert(ASensorEventQueue_hasEvents(q1) == 0);
    jclass mocks = env->FindClass("android/hardware/MockSensors"); assert(mocks && !env->ExceptionCheck());
    auto setter = env->GetStaticMethodID(mocks, "setValues", "(I[F)V"); assert(setter && !env->ExceptionCheck());
    jfloatArray v = env->NewFloatArray(1); assert(v && !env->ExceptionCheck());
    float changed = 876; env->SetFloatArrayRegion(v, 0, 1, &changed); assert(!env->ExceptionCheck());
    env->CallStaticVoidMethod(mocks, setter, 5, v); assert(!env->ExceptionCheck());
    receive(q1, 5, 876); receive(q2, 5, 876);
    assert(ASensorEventQueue_disableSensor(q1, lightSensor) == 0);
    assert(ASensorEventQueue_registerSensor(q1, lightSensor, 10000, 0) == 0);
    receive(q1, 5, 876);
    assert(ASensorEventQueue_disableSensor(q1, lightSensor) == 0);
    auto pressure = ASensorManager_getDefaultSensor(manager, 6); assert(pressure);
    assert(ASensorEventQueue_registerSensor(q1, pressure, 10000, 0) == 0);
    receive(q1, 6, 1013.25f); receive(q1, 6, 1013.25f);
    assert(ASensorManager_destroyEventQueue(manager, q1) == 0);
    assert(ASensorManager_destroyEventQueue(manager, q2) == 0);
    std::puts("PASS NDK mock sensor queues, changes and continuous delivery");
}
