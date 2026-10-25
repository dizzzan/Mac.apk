import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Run with --patch-module java.base=runtime/build/lib/java_base_patch. */
public class ProcFileReaderTest {
    static int checks;
    static void check(boolean ok) { if (!ok) throw new AssertionError("check " + (checks + 1)); checks++; }
    static String read(Reader reader) throws IOException {
        try (reader) { StringWriter out = new StringWriter(); reader.transferTo(out); return out.toString(); }
    }
    public static void main(String[] args) throws Exception {
        String key = "macapk.process.name", old = System.getProperty(key);
        Path real = Files.createTempFile("proc-reader-test", ".txt");
        try {
            System.setProperty(key, "com.example.player");
            String numeric = "/proc/" + ProcessHandle.current().pid() + "/cmdline";
            check(read(new FileReader("/proc/self/cmdline")).equals("com.example.player\0"));
            check(read(new FileReader(numeric)).equals("com.example.player\0"));
            check(read(new FileReader(new File(numeric))).equals("com.example.player\0"));
            check(read(new FileReader(numeric, StandardCharsets.UTF_8)).equals("com.example.player\0"));
            check(read(new FileReader(new File(numeric), StandardCharsets.UTF_8)).equals("com.example.player\0"));
            try (BufferedReader reader = new BufferedReader(new FileReader(numeric))) {
                check(reader.readLine().trim().equals("com.example.player"));
                check(reader.readLine() == null);
            }
            System.setProperty(key, "com.example.player:service");
            check(read(new FileReader(numeric)).equals("com.example.player:service\0"));
            check(read(new FileReader("/proc/self/smaps")).contains("KernelPageSize:       16 kB"));
            Files.writeString(real, "ordinary file\n", StandardCharsets.UTF_8);
            check(read(new FileReader(real.toFile(), StandardCharsets.UTF_8)).equals("ordinary file\n"));
            try { new FileReader("/proc/9223372036854775807/cmdline"); throw new AssertionError(); }
            catch (FileNotFoundException expected) { checks++; }
            try { new FileReader((String) null); throw new AssertionError(); }
            catch (NullPointerException expected) { checks++; }
            try { new FileReader((File) null); throw new AssertionError(); }
            catch (NullPointerException expected) { checks++; }
        } finally {
            if (old == null) System.clearProperty(key); else System.setProperty(key, old);
            Files.delete(real);
        }
        System.out.println(checks + " proc FileReader checks passed");
    }
}
