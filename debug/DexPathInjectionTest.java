import dalvik.system.BaseDexClassLoader;
import dalvik.system.PathClassLoader;
import com.macapk.loader.AppClassLoader;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** Exercise the same reflected array replacement used by Android split installers. */
public final class DexPathInjectionTest {
    private static final ClassLoader PARENT = DexPathInjectionTest.class.getClassLoader();

    private static File jar(Path root, String label, String... names) throws Exception {
        Path dir = Files.createDirectory(root.resolve(label));
        List<String> args = new ArrayList<>(List.of("-d", dir.toString()));
        for (String name : names) {
            Path src = dir.resolve(name + ".java");
            Files.writeString(src, "package fixture; public class " + name
                    + " { public static String value() { return \"" + label + "\"; } }");
            args.add(src.toString());
        }
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])) != 0)
            throw new AssertionError("fixture compiler failed");
        Path jar = root.resolve(label + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : names) {
                out.putNextEntry(new JarEntry("fixture/" + name + ".class"));
                out.write(Files.readAllBytes(dir.resolve("fixture/" + name + ".class")));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("split resource.txt"));
            out.write(label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar.toFile();
    }

    private static Object pathList(BaseDexClassLoader loader) throws Exception {
        Field field = BaseDexClassLoader.class.getDeclaredField("pathList");
        field.setAccessible(true);
        return field.get(loader);
    }
    private static Field elements(Object pathList) throws Exception {
        Field field = pathList.getClass().getDeclaredField("dexElements");
        field.setAccessible(true);
        return field;
    }
    private static Object make(Object pathList, File file) throws Exception {
        Method method = pathList.getClass().getDeclaredMethod("makePathElements", List.class, File.class, List.class);
        method.setAccessible(true);
        List<Exception> errors = new ArrayList<>();
        Object result = method.invoke(pathList, List.of(file), null, errors);
        if (!errors.isEmpty()) throw new AssertionError(errors.toString());
        return result;
    }
    private static void inject(BaseDexClassLoader loader, File file, boolean first) throws Exception {
        Object list = pathList(loader);
        Field field = elements(list);
        Object current = field.get(list), added = make(list, file);
        int n = Array.getLength(current), m = Array.getLength(added);
        Object merged = Array.newInstance(current.getClass().getComponentType(), n + m);
        System.arraycopy(current, 0, merged, first ? m : 0, n);
        System.arraycopy(added, 0, merged, first ? 0 : n, m);
        field.set(list, merged);
    }
    private static void value(BaseDexClassLoader loader, String name, String expected) throws Exception {
        Class<?> type = loader.loadClass("fixture." + name);
        if (type.getClassLoader() != loader) throw new AssertionError("wrong defining loader");
        if (!expected.equals(type.getMethod("value").invoke(null))) throw new AssertionError("wrong path order");
    }
    private static void absent(BaseDexClassLoader loader, String name) throws Exception {
        try { loader.loadClass("fixture." + name); throw new AssertionError("removed element still visible"); }
        catch (ClassNotFoundException expected) { }
    }
    private static void exercise(BaseDexClassLoader loader, File patch, File tail) throws Exception {
        try (loader) {
            absent(loader, "Added");
            value(loader, "Loaded", "base");
            inject(loader, patch, true);
            value(loader, "Choice", "patch");
            value(loader, "Added", "patch");
            value(loader, "Loaded", "base"); // already-defined classes keep their identity
            String resource = new String(loader.getResourceAsStream("split resource.txt").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!resource.equals("patch")) throw new AssertionError("resource order differs from class order");
            if (java.util.Collections.list(loader.getResources("split resource.txt")).size() != 2)
                throw new AssertionError("resource enumeration dropped an element");
            Object list = pathList(loader);
            elements(list).set(list, make(list, patch));
            absent(loader, "Removed");
            loader.addDexPath(tail.getPath());
            value(loader, "Tail", "tail");
            value(loader, "Added", "patch");
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("macapk-dex-injection-");
        try {
            File base = jar(root, "base", "Loaded", "Choice", "Removed");
            File patch = jar(root, "patch", "Loaded", "Choice", "Added");
            File tail = jar(root, "tail", "Tail");
            exercise(new PathClassLoader(base.getPath(), PARENT), patch, tail);
            exercise(new AppClassLoader(new URL[] {base.toURI().toURL()}, PARENT,
                    null, root.toFile(), null, null), patch, tail);
            // Boot's conversion can be reused even when the source cannot be
            // converted in this JVM. Changing the source invalidates the mapping.
            Path apk = root.resolve("base.apk");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(apk))) {
                out.putNextEntry(new JarEntry("classes.dex"));
                out.write("deliberately invalid dex".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            try (BaseDexClassLoader loader = new AppClassLoader(new URL[] {base.toURI().toURL()},
                    PARENT, null, root.toFile(), null, apk.toString())) {
                inject(loader, apk.toFile(), true);
                value(loader, "Choice", "base");
                Files.write(apk, new byte[] {0}, java.nio.file.StandardOpenOption.APPEND);
                Object list = pathList(loader);
                Method make = list.getClass().getDeclaredMethod("makePathElements", List.class, File.class, List.class);
                make.setAccessible(true);
                List<Exception> errors = new ArrayList<>();
                Object result = make.invoke(list, List.of(apk.toFile()), null, errors);
                if (Array.getLength(result) != 0 || errors.size() != 1)
                    throw new AssertionError("changed APK reused a stale conversion");
            }
            // Mapped original code must survive unlinking when the path is edited later.
            try (BaseDexClassLoader loader = new PathClassLoader(base.getPath(), PARENT)) {
                Files.delete(base.toPath());
                inject(loader, patch, false);
                value(loader, "Choice", "base");
                value(loader, "Added", "patch");
                Object list = pathList(loader);
                Method make = list.getClass().getDeclaredMethod("makePathElements", List.class, File.class, List.class);
                make.setAccessible(true);
                Path bad = root.resolve("invalid.jar");
                Files.writeString(bad, "invalid archive");
                List<Exception> errors = new ArrayList<>();
                Object result = make.invoke(list, List.of(bad.toFile()), null, errors);
                if (Array.getLength(result) != 0 || errors.size() != 1)
                    throw new AssertionError("invalid element was not reported");
            }
            System.out.println("PASS: reflected split injection, defining loader, precedence, removal, resources, unlink lifetime, errors");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
