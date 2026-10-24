import android.system.ErrnoException;
import android.system.Os;
import java.nio.file.Files;
import java.nio.file.Path;

/** Run with the framework/loader jars and NativeOsTestBridge dylib.
 *  From the repository root, after building the Java jars:
 *    test_dir=$(mktemp -d)
 *    jdk_dir=$(/usr/libexec/java_home -v 21)
 *    clang++ -std=c++17 -dynamiclib -I"$jdk_dir/include" -I"$jdk_dir/include/darwin" \
 *      runtime/src/native_os.cpp debug/NativeOsTestBridge.cpp -lcompression \
 *      -o "$test_dir/libnative-os-test.dylib"
 *    "$jdk_dir/bin/javac" -cp runtime/build/lib/android-framework.jar \
 *      -d "$test_dir" debug/OsChmodTest.java
 *    "$jdk_dir/bin/java" -cp "$test_dir:runtime/build/lib/android-framework.jar:runtime/build/lib/macapk-loader.jar" \
 *      OsChmodTest "$test_dir/libnative-os-test.dylib"
 */
public final class OsChmodTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void mode(Path path, int expected) throws Exception {
        int actual = ((Number) Files.getAttribute(path, "unix:mode")).intValue() & 07777;
        check(actual == expected, "mode " + Integer.toOctalString(actual)
                + " != " + Integer.toOctalString(expected));
    }
    private static void fails(Path path, int errno) throws Exception {
        try {
            Os.chmod(path.toString(), 0700);
            throw new AssertionError("chmod should fail: " + path);
        } catch (ErrnoException e) {
            check(e.errno == errno && "chmod".equals(e.functionName),
                    "wrong chmod error: " + e.errno);
        }
    }
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        Path dir = Files.createTempDirectory("macapk-chmod-");
        try {
            Path file = Files.writeString(dir.resolve("file"), "permission test");
            Os.chmod(file.toString(), 0640);
            mode(file, 0640);
            Path link = Files.createSymbolicLink(dir.resolve("link"), Path.of("file"));
            Os.chmod(link.toString(), 0604);
            mode(file, 0604);
            check(Files.isSymbolicLink(link), "chmod follows, preserves symlink");
            Os.chmod(dir.toString(), 01750);
            mode(dir, 01750);
            fails(dir.resolve("missing"), 2); // ENOENT
            fails(file.resolve("child"), 20); // ENOTDIR
            Path broken = Files.createSymbolicLink(dir.resolve("broken"), Path.of("missing"));
            fails(broken, 2);
            Path loop = Files.createSymbolicLink(dir.resolve("loop"), Path.of("loop"));
            fails(loop, 40); // Linux ELOOP, not Darwin 62
            fails(dir.resolve("x".repeat(256)), 36); // Linux ENAMETOOLONG
            try {
                Os.chmod(null, 0700);
                throw new AssertionError("null path should throw");
            } catch (NullPointerException expected) { checks++; }
        } finally {
            Files.setAttribute(dir, "unix:mode", 0700);
            try (var entries = Files.list(dir)) {
                for (Path entry : entries.toList()) Files.delete(entry);
            }
            Files.delete(dir);
        }
        System.out.println("PASS: Os.chmod " + checks + " checks");
    }
}
