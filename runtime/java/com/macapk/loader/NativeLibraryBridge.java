package com.macapk.loader;

/** Android accepts ELF files in System.load's absolute-path form too, including
 * libraries an app has just unpacked into its data directory. HotSpot's dyld
 * loader accepts only Mach-O. ClassFileRewriter routes guest load calls here
 * so those files use the same translator as loadLibrary, without replacing
 * the app's ELF files or registering its JNI methods in the system loader.
 * Android Runtime.load0 passes the initiating class's loader to nativeLoad:
 * https://android.googlesource.com/platform/libcore/+/master/ojluni/src/main/java/java/lang/Runtime.java
 */
public final class NativeLibraryBridge {
    private static final StackWalker WALKER =
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private NativeLibraryBridge() {}

    public static void load(String path) {
        ClassLoader loader = WALKER.getCallerClass().getClassLoader();
        if (loader instanceof AppClassLoader app) app.loadAbsoluteLibrary(path);
        else System.load(path);
    }

    public static void load(Runtime runtime, String path) {
        java.util.Objects.requireNonNull(runtime);
        ClassLoader loader = WALKER.getCallerClass().getClassLoader();
        if (loader instanceof AppClassLoader app) app.loadAbsoluteLibrary(path);
        else runtime.load(path);
    }
}
