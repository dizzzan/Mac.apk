import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;

/** Run with -Xmx128m and a large DEX/APK fixture: conversion must fit in the
 * worker, preserve all classes, and remove the transient snapshot/report. */
public final class DynamicDexWorkerTest {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("large dex/archive fixture required");
        List<byte[]> images = com.macapk.dex.Main.readDexes(Path.of(args[0]));
        if (images.stream().mapToLong(b -> b.length).sum() < (4L << 20))
            throw new IllegalArgumentException("fixture must contain at least 4 MiB of dex");
        Path dir = Files.createTempDirectory("macapk-dex-worker-");
        Path output = dir.resolve("converted.jar");
        Map<String, String> errors = new TreeMap<>();
        try {
            var convert = Class.forName("dalvik.system.MacApkDexConverter").getDeclaredMethod(
                    "convertFileImages", List.class, Path.class, Map.class);
            convert.setAccessible(true);
            int count = (Integer) convert.invoke(null, images, output, errors);
            if (count <= 0 || !errors.isEmpty()) throw new AssertionError("Incomplete conversion: " + errors);
            try (JarFile jar = new JarFile(output.toFile())) {
                long actual = jar.stream().filter(e -> e.getName().endsWith(".class")).count();
                if (count != actual) throw new AssertionError("Class count mismatch");
                if (args.length > 1 && jar.getEntry(args[1]) == null)
                    throw new AssertionError("Required class is missing: " + args[1]);
            }
            if (Files.exists(Path.of(output + ".input.zip")) || Files.exists(Path.of(output + ".result")))
                throw new AssertionError("Worker scratch files remain");
            Path staleInput = dir.resolve("test.999999999.1.part.jar.input.zip");
            Path staleReport = dir.resolve("mem.999999999.1.jar.result");
            Path unrelated = dir.resolve("keep.input.zip");
            Files.writeString(staleInput, "abandoned");
            Files.writeString(staleReport, "abandoned");
            Files.writeString(unrelated, "keep");
            var prune = Class.forName("dalvik.system.MacApkDexConverter").getDeclaredMethod("pruneOnce", Path.class);
            prune.setAccessible(true);
            prune.invoke(null, dir);
            if (Files.exists(staleInput) || Files.exists(staleReport) || !Files.exists(unrelated))
                throw new AssertionError("Abandoned worker cleanup touched the wrong files");
            System.out.println("PASS: " + count + " classes converted with a "
                    + (Runtime.getRuntime().maxMemory() >> 20) + " MiB parent heap");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
