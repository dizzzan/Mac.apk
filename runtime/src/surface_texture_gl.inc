// SurfaceTexture's GLConsumer path. Android attaches each acquired buffer as an
// EGLImage to GL_TEXTURE_EXTERNAL_OES (frameworks/native/libs/gui/GLConsumer.cpp,
// bindTextureImageLocked). Binding that texture name to GL_TEXTURE_2D is invalid.
// Our producers supply RGBA in host memory, so a temporary 2D texture provides
// the EGLImage storage. KHR_image_base keeps the external sibling alive after
// both the image handle and the source texture are deleted; no GL resources
// need to be destroyed later from a Java release() thread without a context.
jboolean SurfaceTexture_uploadExternalFrame(JNIEnv* env, jclass, jint texture,
                                            jint width, jint height, jobject rgba) {
#ifdef MACAPK_HAS_ANGLE
    const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    const auto hasExtension = [extensions](const char* name) {
        if (!extensions) return false;
        const size_t length = std::strlen(name);
        for (const char* p = extensions; (p = std::strstr(p, name)); p += length) {
            if ((p == extensions || p[-1] == ' ') &&
                (p[length] == '\0' || p[length] == ' ')) return true;
        }
        return false;
    };
    if (!hasExtension("GL_OES_EGL_image_external")) return JNI_FALSE;
    const auto fail = [env](const char* message) {
        jclass ex = env->FindClass("java/lang/IllegalStateException");
        if (ex) { env->ThrowNew(ex, message); env->DeleteLocalRef(ex); }
    };
    const void* pixels = rgba ? env->GetDirectBufferAddress(rgba) : nullptr;
    const jlong bytes = rgba ? env->GetDirectBufferCapacity(rgba) : 0;
    if (!pixels || width <= 0 || height <= 0 ||
        static_cast<int64_t>(width) * height > bytes / 4) {
        fail("SurfaceTexture: invalid RGBA upload buffer");
        return JNI_TRUE;
    }
    using GetCurrent = void* (*)();
    using CreateImage = void* (*)(void*, void*, unsigned, void*, const int*);
    using DestroyImage = unsigned (*)(void*, void*);
    using BindImage = void (*)(unsigned, void*);
    static auto display = reinterpret_cast<GetCurrent>(dlsym(RTLD_DEFAULT, "eglGetCurrentDisplay"));
    static auto context = reinterpret_cast<GetCurrent>(dlsym(RTLD_DEFAULT, "eglGetCurrentContext"));
    static auto create = reinterpret_cast<CreateImage>(dlsym(RTLD_DEFAULT, "eglCreateImageKHR"));
    static auto destroy = reinterpret_cast<DestroyImage>(dlsym(RTLD_DEFAULT, "eglDestroyImageKHR"));
    static auto bind = reinterpret_cast<BindImage>(dlsym(RTLD_DEFAULT, "glEGLImageTargetTexture2DOES"));
    if (!display || !context || !create || !destroy || !bind) {
        fail("SurfaceTexture: external-image entry points unavailable");
        return JNI_TRUE;
    }

    // updateTexImage binds the consumer's external texture on the active unit,
    // as AOSP does. Its internal upload must preserve the unrelated 2D binding
    // and pixel-unpack state, including an app's PBO in an ES3 context.
    GLint old2d = 0, alignment = 0, unpackBuffer = 0, rowLength = 0, skipRows = 0, skipPixels = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &old2d);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &alignment);
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const bool es3 = version && std::strncmp(version, "OpenGL ES 3", 11) == 0;
    if (es3) {
        glGetIntegerv(0x88EF /*GL_PIXEL_UNPACK_BUFFER_BINDING*/, &unpackBuffer);
        glGetIntegerv(0x0CF2 /*GL_UNPACK_ROW_LENGTH*/, &rowLength);
        glGetIntegerv(0x0CF3 /*GL_UNPACK_SKIP_ROWS*/, &skipRows);
        glGetIntegerv(0x0CF4 /*GL_UNPACK_SKIP_PIXELS*/, &skipPixels);
        glBindBuffer(0x88EC /*GL_PIXEL_UNPACK_BUFFER*/, 0);
        glPixelStorei(0x0CF2, 0); glPixelStorei(0x0CF3, 0); glPixelStorei(0x0CF4, 0);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
    GLuint backing = 0;
    glGenTextures(1, &backing);
    glBindTexture(GL_TEXTURE_2D, backing);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glBindTexture(GL_TEXTURE_2D, old2d);
    glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
    if (es3) {
        glPixelStorei(0x0CF2, rowLength); glPixelStorei(0x0CF3, skipRows); glPixelStorei(0x0CF4, skipPixels);
        glBindBuffer(0x88EC, unpackBuffer);
    }
    // EGL_GL_TEXTURE_2D_KHR; EGL_IMAGE_PRESERVED_KHR must be true because
    // these pixels already contain the producer frame, rather than undefined
    // storage. No glGetError calls here: the app owns its GL error state.
    const int attributes[] = {0x30D2, 1, 0x3038};
    void* dpy = display();
    void* image = create(dpy, context(), 0x30B1,
                         reinterpret_cast<void*>(static_cast<uintptr_t>(backing)), attributes);
    if (image) {
        bind(GL_TEXTURE_EXTERNAL_OES, image);
        destroy(dpy, image);
    }
    glDeleteTextures(1, &backing);
    if (!image) fail("SurfaceTexture: EGLImage creation failed");
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// Consumed decoder or dedicated TextureView buffers, before window composition.
// Intermediate EGL consumers are filtered in Java to avoid recording them twice.
void SurfaceTexture_recordDecodedFrame(JNIEnv* env, jclass, jlong source, jintArray argb,
                                       jint width, jint height, jint stride, jboolean renderedSurface) {
    uint64_t ticket = StreamRecorderObserve(source, width, height, renderedSurface);
    if (!ticket || !argb || stride < width || width <= 0 || height <= 0 ||
        int64_t(stride) * (height - 1) + width > env->GetArrayLength(argb)) return;
    const double now = StreamRecorderHostSeconds();
    std::vector<uint32_t> pixels(size_t(width) * height);
    if (stride == width) env->GetIntArrayRegion(argb, 0, width * height, reinterpret_cast<jint*>(pixels.data()));
    else for (int y = 0; y < height && !env->ExceptionCheck(); ++y)
        env->GetIntArrayRegion(argb, y * stride, width, reinterpret_cast<jint*>(pixels.data() + y * width));
    if (!env->ExceptionCheck()) StreamRecorderVideo(ticket, std::move(pixels), width, height, now);
}

static const JNINativeMethod kSurfaceTextureGlMethods[] = {
    {(char*)"nativeRecordDecodedFrame", (char*)"(J[IIIIZ)V", (void*)SurfaceTexture_recordDecodedFrame},
    {(char*)"nativeUploadExternalFrame", (char*)"(IIILjava/nio/ByteBuffer;)Z",
     (void*)SurfaceTexture_uploadExternalFrame},
};
