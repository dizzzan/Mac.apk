import com.macapk.loader.AppClassLoader;
import com.macapk.loader.ClassFileRewriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import javax.tools.ToolProvider;

/** Standalone regression; run on macOS with a full JDK and Command Line Tools:
 * MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 java -cp \
 * runtime/build/lib/android-framework.jar:runtime/build/lib/macapk-loader.jar \
 * debug/NativeLibraryLoadingTest.java
 * Uses only generated fixtures, never a third-party APK. */
public class NativeLibraryLoadingTest {
    static int checks;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("macapk-native-load-test");
        try {
            dependencyClosure(tmp);
            nativeRegistration(tmp);
            System.out.println("Native library loading: " + checks + " checks passed");
        } finally {
            try (var files = Files.walk(tmp)) {
                for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    static Path elf(Path dir, String name, String... needed) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{0x7f, 'E', 'L', 'F', 2, 1, 1});
        b.putShort(18, (short) 183); // EM_AARCH64
        b.putLong(32, 64); // e_phoff
        b.putShort(54, (short) 56).putShort(56, (short) 1);
        b.putInt(64, 2).putLong(72, 256); // PT_DYNAMIC, p_offset
        b.putLong(96, (needed.length + 3L) * 16); // p_filesz
        int str = 1024, dyn = 256;
        b.putLong(dyn, 5).putLong(dyn + 8, 1024); dyn += 16;
        for (String dep : needed) {
            b.putLong(dyn, 1).putLong(dyn + 8, str - 1024); dyn += 16;
            for (byte c : dep.getBytes(java.nio.charset.StandardCharsets.UTF_8)) b.put(str++, c);
            b.put(str++, (byte) 0);
        }
        b.putLong(dyn, 10).putLong(dyn + 8, str - 1024 + 1);
        Path p = dir.resolve(name);
        Files.write(p, b.array());
        return p;
    }

    static void dependencyClosure(Path tmp) throws Exception {
        Method closure = AppClassLoader.class.getDeclaredMethod("dependencyClosureAvailable",
            java.io.File.class, Map.class, java.util.Set.class);
        closure.setAccessible(true);
        Path leaf = elf(tmp, "libleaf.so", "libc.so", "liblog.so");
        Path app = elf(tmp, "libapp.so", "libleaf.so", "libc++_shared.so");
        Path cpp = elf(tmp, "libc++_shared.so", "libc.so", "libdl.so");
        Map<String, java.io.File[]> exposed = new HashMap<>();
        exposed.put("libleaf.so", new java.io.File[]{leaf.toFile()});
        check((boolean) closure.invoke(null, leaf.toFile(), exposed, new HashSet<>()), "NDK system deps are available");
        check(!(boolean) closure.invoke(null, app.toFile(), exposed, new HashSet<>()), "missing libc++ defers init");
        exposed.put("libc++_shared.so", new java.io.File[]{cpp.toFile()});
        check((boolean) closure.invoke(null, app.toFile(), exposed, new HashSet<>()), "complete dependency closure is available");
        elf(tmp, "libleaf.so", "libunpacked-provider.so");
        check(!(boolean) closure.invoke(null, app.toFile(), exposed, new HashSet<>()), "transitive missing provider defers init");
    }

    static void nativeRegistration(Path tmp) throws Exception {
        Path src = tmp.resolve("LoadFixture.java");
        Files.writeString(src, """
            public class LoadFixture {
                public static void system(String p) { System.load(p); }
                public static void runtime(Runtime r, String p) { r.load(p); }
                public static native int ping();
                public static native int loads();
                public static java.lang.reflect.Field descriptor() throws Exception {
                    return java.io.FileDescriptor.class.getDeclaredField("descriptor");
                }
            }
            class UnpackedFixture {
                public static void absolute(String p) { System.load(p); }
                public static void named(String p) { System.loadLibrary(p); }
                public static native int ping();
            }
            """);
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "21", "-d", tmp.toString(), src.toString()) == 0, "fixture compiles");
        byte[] original = Files.readAllBytes(tmp.resolve("LoadFixture.class"));
        var rewritten = ClassFileRewriter.maybeRewrite(original.clone(), "LoadFixture");
        check(rewritten.rewritten, "prescan admits load calls without a class initializer");
        check(!ClassFileRewriter.maybeRewrite(rewritten.bytes.clone(), "LoadFixture").rewritten,
            "native load rewriting is idempotent");

        Path c = tmp.resolve("fixture.c"), dylib = tmp.resolve("libfixture.dylib");
        Files.writeString(c, """
            #include <jni.h>
            static int count;
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
                ++count;
                JNIEnv* env;
                (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
                jclass sys = (*env)->FindClass(env, "java/lang/System");
                jmethodID get = (*env)->GetStaticMethodID(env, sys, "getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
                jmethodID set = (*env)->GetStaticMethodID(env, sys, "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
                jstring key = (*env)->NewStringUTF(env, "macapk.fixture.loads");
                jstring zero = (*env)->NewStringUTF(env, "0");
                jstring old = (*env)->CallStaticObjectMethod(env, sys, get, key, zero);
                const char* text = (*env)->GetStringUTFChars(env, old, 0);
                char next[32]; snprintf(next, sizeof(next), "%d", atoi(text) + 1);
                (*env)->ReleaseStringUTFChars(env, old, text);
                (*env)->CallStaticObjectMethod(env, sys, set, key, (*env)->NewStringUTF(env, next));
                return JNI_VERSION_1_6;
            }
            JNIEXPORT jint JNICALL Java_LoadFixture_ping(JNIEnv* env, jclass cls) { return 73; }
            JNIEXPORT jint JNICALL Java_LoadFixture_loads(JNIEnv* env, jclass cls) { return count; }
            JNIEXPORT jint JNICALL Java_UnpackedFixture_ping(JNIEnv* env, jclass cls) { return 91; }
            """);
        Files.writeString(c, "#include <stdio.h>\n#include <stdlib.h>\n" + Files.readString(c));
        Path include = Path.of(System.getProperty("java.home"), "include");
        check(new ProcessBuilder("clang", "-dynamiclib", "-I" + include, "-I" + include.resolve("darwin"),
            "-o", dylib.toString(), c.toString()).inheritIO().start().waitFor() == 0, "JNI fixture compiles");

        // A translator test double returns the generated Mach-O fixture. The
        // test exercises cache selection and JVM registration, independently
        // of the ELF translator (the real APK run covers that boundary).
        Path translator = tmp.resolve("translator");
        Files.writeString(translator, "#!/bin/sh\nwhile [ $# -gt 0 ]; do\n"
            + "case \"$1\" in --out) shift; target=$1;; esac\nshift\ndone\n"
            + "cp '" + dylib.toString().replace("'", "'\\''") + "' \"$target\"\n");
        check(translator.toFile().setExecutable(true), "translator fixture is executable");
        try (AppClassLoader loader = new AppClassLoader(new URL[]{tmp.toUri().toURL()},
                NativeLibraryLoadingTest.class.getClassLoader(), null, tmp.resolve("code").toFile(), translator.toFile(), null)) {
            Class<?> fixture = loader.loadClass("LoadFixture");
            check(fixture.getClassLoader() == loader, "fixture belongs to the guest loader");
            check(((java.lang.reflect.Field) fixture.getMethod("descriptor").invoke(null)).getName().equals("fd"),
                "native load routing composes with the libcore-field rewrite");
            Method system = fixture.getMethod("system", String.class);
            Method runtime = fixture.getMethod("runtime", Runtime.class, String.class);
            expect(NullPointerException.class, () -> system.invoke(null, (Object) null));
            expect(UnsatisfiedLinkError.class, () -> system.invoke(null, "relative.so"));
            expect(NullPointerException.class, () -> runtime.invoke(null, null, dylib.toString()));
            expect(UnsatisfiedLinkError.class, () -> system.invoke(null, tmp.resolve("missing.so").toString()));
            java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
            var threads = new java.util.ArrayList<Thread>();
            for (int i = 0; i < 8; i++) {
                Thread thread = new Thread(() -> {
                    try { system.invoke(null, dylib.toString()); }
                    catch (Throwable t) { error.compareAndSet(null, t); }
                });
                threads.add(thread); thread.start();
            }
            for (Thread thread : threads) thread.join();
            check(error.get() == null, "concurrent first loads preserve registration: " + error.get());
            check(fixture.getMethod("ping").invoke(null).equals(73), "System.load registers natives with the guest");
            runtime.invoke(null, Runtime.getRuntime(), dylib.toString());
            check(fixture.getMethod("loads").invoke(null).equals(1), "repeat Runtime.load runs JNI_OnLoad once");
            Path alias = tmp.resolve("alias.dylib");
            Files.createSymbolicLink(alias, dylib);
            system.invoke(null, alias.toString());
            check(fixture.getMethod("loads").invoke(null).equals(1), "canonical aliases share one native image");

            Path unpacked = Files.createDirectory(tmp.resolve("unpacked"));
            Path elf = elf(unpacked, "libunpacked.so", "libc.so");
            byte[] elfOriginal = Files.readAllBytes(elf);
            Class<?> dynamic = loader.loadClass("UnpackedFixture");
            Method absolute = dynamic.getMethod("absolute", String.class);
            Method named = dynamic.getMethod("named", String.class);
            Method ping = dynamic.getMethod("ping");
            absolute.setAccessible(true); named.setAccessible(true); ping.setAccessible(true);
            absolute.invoke(null, elf.toString());
            check(ping.invoke(null).equals(91), "unpacked ELF route registers the guest's native method");
            String translated = loader.findLibrary("unpacked");
            check(translated.contains("/absolute/") && Files.isRegularFile(Path.of(translated)), "loadLibrary reuses the absolute-load cache");
            named.invoke(null, "unpacked");
            check(System.getProperty("macapk.fixture.loads").equals("2"), "mixed load forms initialize exactly one unpacked image");
            check(java.util.Arrays.equals(elfOriginal, Files.readAllBytes(elf)), "ELF source is unchanged");

            Path missing = elf(unpacked, "libmissing-dep.so", "libnot-unpacked-yet.so");
            expect(UnsatisfiedLinkError.class, () -> absolute.invoke(null, missing.toString()));
            Path cycle = elf(unpacked, "libcycle-a.so", "libcycle-b.so");
            elf(unpacked, "libcycle-b.so", "libcycle-a.so");
            expect(UnsatisfiedLinkError.class, () -> absolute.invoke(null, cycle.toString()));
            Path arm32 = elf(unpacked, "libarm32.so");
            byte[] arm32Bytes = Files.readAllBytes(arm32); arm32Bytes[4] = 1;
            Files.write(arm32, arm32Bytes);
            expect(UnsatisfiedLinkError.class, () -> absolute.invoke(null, arm32.toString()));
        }
    }

    interface Throwing { void run() throws Exception; }
    static void expect(Class<? extends Throwable> type, Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + type); }
        catch (InvocationTargetException e) { check(type.isInstance(e.getCause()), "expected " + type + ", got " + e.getCause()); }
    }
}
