// TextureView native windows retain their own Java Surface. A SurfaceTexture
// is a BufferQueue consumer, not the application's global Cocoa window.
struct MacApkTextureWindow { jobject surface; jobject texture; unsigned refs; };
static std::mutex g_texture_window_mu;
static std::vector<MacApkTextureWindow*> g_texture_windows;
static bool macapk_texture_windows_enabled() {
    static const bool on = [] {
        const char* value = std::getenv("MACAPK_NATIVE_TEXTURE_WINDOW");
        return value && std::strcmp(value, "1") == 0;
    }();
    return on;
}
static jobject macapk_texture_window_surface(JNIEnv* env, void* window) {
    if (!macapk_texture_windows_enabled() || !env || env->ExceptionCheck()) return nullptr;
    std::lock_guard<std::mutex> lock(g_texture_window_mu);
    for (auto* w : g_texture_windows)
        if (w == window) return env->NewLocalRef(w->surface);
    return nullptr;
}
static void* macapk_texture_window_from_surface(JNIEnv* env, jobject surface, bool* handled) {
    if (!surface) { *handled = true; return nullptr; }
    if (!macapk_texture_windows_enabled() || env->ExceptionCheck()) return nullptr;
    jclass cls = env->GetObjectClass(surface);
    jmethodID valid = cls ? env->GetMethodID(cls, "isValid", "()Z") : nullptr;
    bool is_valid = valid && env->CallBooleanMethod(surface, valid);
    if (env->ExceptionCheck() || !is_valid) {
        *handled = true;
        if (cls) env->DeleteLocalRef(cls);
        return nullptr;
    }
    jmethodID get = cls ? env->GetMethodID(cls, "macapkGetSurfaceTexture",
        "()Landroid/graphics/SurfaceTexture;") : nullptr;
    jobject texture = get ? env->CallObjectMethod(surface, get) : nullptr;
    if (cls) env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    if (!texture) return nullptr;
    *handled = true;
    std::lock_guard<std::mutex> lock(g_texture_window_mu);
    for (auto* w : g_texture_windows) {
        if (env->IsSameObject(w->texture, texture)) {
            ++w->refs;
            env->DeleteLocalRef(texture);
            return w;
        }
    }
    jobject sg = env->NewGlobalRef(surface), tg = env->NewGlobalRef(texture);
    env->DeleteLocalRef(texture);
    if (!sg || !tg) {
        if (sg) env->DeleteGlobalRef(sg);
        if (tg) env->DeleteGlobalRef(tg);
        return nullptr;
    }
    auto* w = new (std::nothrow) MacApkTextureWindow{sg, tg, 1};
    try {
        if (w) g_texture_windows.push_back(w);
    } catch (const std::bad_alloc&) { delete w; w = nullptr; }
    if (!w) { env->DeleteGlobalRef(sg); env->DeleteGlobalRef(tg); }
    return w;
}
static bool macapk_texture_window_ref(void* window, bool acquire) {
    if (!macapk_texture_windows_enabled()) return false;
    AttachGuard ag;
    if (!ag.env) return false;
    std::lock_guard<std::mutex> lock(g_texture_window_mu);
    for (auto i = g_texture_windows.begin(); i != g_texture_windows.end(); ++i) {
        auto* w = *i;
        if (w != window) continue;
        if (acquire) ++w->refs;
        else if (--w->refs == 0) {
            ag.env->DeleteGlobalRef(w->surface);
            ag.env->DeleteGlobalRef(w->texture);
            g_texture_windows.erase(i);
            delete w;
        }
        return true;
    }
    return false;
}
static int macapk_texture_window_size(void* window, bool width) {
    if (!macapk_texture_windows_enabled()) return -1;
    AttachGuard ag;
    if (!ag.env || ag.env->ExceptionCheck()) return -1;
    jobject texture = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_texture_window_mu);
        for (auto* w : g_texture_windows) if (w == window) {
            texture = ag.env->NewLocalRef(w->texture); break;
        }
    }
    if (!texture) return -1;
    jclass cls = ag.env->GetObjectClass(texture);
    jmethodID get = cls ? ag.env->GetMethodID(cls,
        width ? "macapkGetDefaultWidth" : "macapkGetDefaultHeight", "()I") : nullptr;
    int size = get ? ag.env->CallIntMethod(texture, get) : 0;
    if (ag.env->ExceptionCheck()) { ag.env->ExceptionClear(); size = 0; }
    if (cls) ag.env->DeleteLocalRef(cls);
    ag.env->DeleteLocalRef(texture);
    return size > 0 ? size : 1;
}
