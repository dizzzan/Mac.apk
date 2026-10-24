package dalvik.system;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** ART's reflected code-path list. Split installers replace dexElements after
 * calling makePathElements; those edits must affect the defining loader, not
 * just satisfy a field lookup. See libcore/dalvik/src/main/java/dalvik/system/
 * DexPathList.java. The original URLClassLoader remains the fast path until
 * an app edits this list. */
public final class DexPathList {
    private volatile Element[] dexElements;
    private Element[] originalElements;
    private final ClassLoader definingContext;
    private final List<File> nativeLibraryDirectories = new ArrayList<>();
    private IOException[] dexElementsSuppressedExceptions;
    private final Set<Element> opened = Collections.newSetFromMap(new IdentityHashMap<>());

    public DexPathList(ClassLoader context, String dexPath, String librarySearchPath,
                       File optimizedDirectory) {
        this(context, new URL[0], librarySearchPath);
        if (dexPath != null) addDexPath(dexPath);
    }

    DexPathList(ClassLoader context, URL[] urls, String librarySearchPath) {
        definingContext = context;
        List<Element> elements = new ArrayList<>();
        for (URL url : urls) elements.add(new Element(url));
        dexElements = elements.toArray(new Element[0]);
        originalElements = dexElements.clone();
        remember(dexElements);
        if (librarySearchPath != null) {
            for (String p : librarySearchPath.split(File.pathSeparator)) {
                if (!p.isEmpty()) nativeLibraryDirectories.add(new File(p));
            }
        }
    }

    synchronized void addOriginalURL(URL url) {
        boolean unchanged = !isModified();
        for (Element element : dexElements) if (element.url.equals(url)) return;
        Element element = new Element(url);
        dexElements = append(dexElements, new Element[] {element});
        remember(new Element[] {element});
        if (unchanged) originalElements = dexElements.clone();
    }

    boolean isModified() { return !Arrays.equals(dexElements, originalElements); }

    synchronized void addDexPath(String path) {
        List<File> files = new ArrayList<>();
        for (String p : path.split(File.pathSeparator)) if (!p.isEmpty()) files.add(new File(p));
        List<IOException> errors = new ArrayList<>();
        Element[] extra = makePathElements(files, null, errors);
        dexElements = append(dexElements, extra);
        remember(extra);
        if (!errors.isEmpty()) {
            if (dexElementsSuppressedExceptions != null)
                errors.addAll(Arrays.asList(dexElementsSuppressedExceptions));
            dexElementsSuppressedExceptions = errors.toArray(new IOException[0]);
        }
    }

    private static Element[] append(Element[] first, Element[] last) {
        Element[] result = Arrays.copyOf(first, first.length + last.length);
        System.arraycopy(last, 0, result, first.length, last.length);
        return result;
    }

    /** AOSP retains this three-argument method for reflective split installers. */
    private static Element[] makePathElements(List<File> files, File optimizedDirectory,
                                              List<IOException> suppressedExceptions) {
        List<Element> result = new ArrayList<>();
        for (File file : files) {
            if (!file.exists()) continue;
            try {
                URL url = file.toURI().toURL();
                if (file.isFile()) {
                    boolean dex = file.getName().endsWith(".dex");
                    if (!dex) {
                        try (ZipFile zip = new ZipFile(file)) {
                            dex = zip.getEntry("classes.dex") != null;
                        }
                    }
                    if (dex) url = MacApkDexConverter.convertFileToJarUrl(file);
                }
                Element element = new Element(url);
                if (element.openFailure != null) throw element.openFailure;
                result.add(element);
            } catch (IOException | RuntimeException failure) {
                suppressedExceptions.add(failure instanceof IOException ? (IOException) failure
                        : new IOException("Unable to load code from " + file, failure));
            }
        }
        return result.toArray(new Element[0]);
    }

    private static Element[] makeDexElements(List<File> files, File optimizedDirectory,
                                             List<IOException> suppressedExceptions) {
        return makePathElements(files, optimizedDirectory, suppressedExceptions);
    }

    private synchronized void remember(Element[] elements) {
        Collections.addAll(opened, elements);
    }

    Class<?> findClass(String name) throws ClassNotFoundException {
        Element[] elements = dexElements;
        remember(elements);
        List<Throwable> failures = new ArrayList<>();
        for (Element element : elements) {
            try {
                URL resource = element.findResource(name.replace('.', '/') + ".class");
                if (resource == null) continue;
                try (InputStream in = resource.openStream()) {
                    return ((BaseDexClassLoader) definingContext).defineClassFromDexFile(name, in.readAllBytes());
                }
            } catch (IOException | LinkageError failure) { failures.add(failure); }
        }
        ClassNotFoundException missing = new ClassNotFoundException(name);
        for (Throwable failure : failures) missing.addSuppressed(failure);
        if (dexElementsSuppressedExceptions != null)
            for (IOException failure : dexElementsSuppressedExceptions) missing.addSuppressed(failure);
        throw missing;
    }

    URL findResource(String name) {
        Element[] elements = dexElements;
        remember(elements);
        for (Element element : elements) {
            URL resource = element.findResource(name);
            if (resource != null) return resource;
        }
        return null;
    }

    Enumeration<URL> findResources(String name) {
        Element[] elements = dexElements;
        remember(elements);
        List<URL> resources = new ArrayList<>();
        for (Element element : elements) {
            URL resource = element.findResource(name);
            if (resource != null) resources.add(resource);
        }
        return Collections.enumeration(resources);
    }

    synchronized void close() throws IOException {
        remember(dexElements);
        IOException failure = null;
        for (Element element : opened) {
            try { if (element.zip != null) element.zip.close(); }
            catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        opened.clear();
        if (failure != null) throw failure;
    }

    public String findLibrary(String name) {
        for (File dir : nativeLibraryDirectories) {
            File file = new File(dir, "lib" + name + ".so");
            if (file.isFile()) return file.getAbsolutePath();
        }
        return null;
    }

    public List<String> getDexPaths() {
        List<String> paths = new ArrayList<>();
        for (Element element : dexElements) paths.add(element.path.getPath());
        return paths;
    }

    @Override public String toString() {
        return "DexPathList[" + Arrays.toString(dexElements)
                + ",nativeLibraryDirectories=" + nativeLibraryDirectories + "]";
    }

    /** Keep jars open as ART keeps dex mappings open: in-memory converters may
     * unlink their temporary jars immediately after loader construction. */
    static final class Element {
        private final File path;
        private final URL url;
        private final ZipFile zip;
        private final IOException openFailure;

        Element(URL url) {
            this.url = url;
            File file;
            try { file = new File(url.toURI()); }
            catch (Exception e) { file = new File(url.getPath()); }
            path = file;
            ZipFile openedZip = null;
            IOException failure = null;
            if (!path.isDirectory()) {
                try { openedZip = new ZipFile(path); }
                catch (IOException e) { failure = e; }
            }
            zip = openedZip;
            openFailure = failure;
        }

        public Element(File path) { this(fileUrl(path)); }

        private static URL fileUrl(File file) {
            try { return file.toURI().toURL(); }
            catch (IOException e) { throw new IllegalArgumentException(e); }
        }

        URL findResource(String name) {
            try {
                if (zip != null) {
                    ZipEntry entry = zip.getEntry(name);
                    if (entry == null) return null;
                    String encoded = new java.net.URI(null, null, "/" + name, null).getRawPath().substring(1);
                    return new URL(null, "jar:" + url.toExternalForm() + "!/" + encoded,
                            new URLStreamHandler() {
                                @Override protected URLConnection openConnection(URL resource) {
                                    return new URLConnection(resource) {
                                        @Override public void connect() { connected = true; }
                                        @Override public InputStream getInputStream() throws IOException {
                                            connect();
                                            return zip.getInputStream(entry);
                                        }
                                    };
                                }
                            });
                }
                if (path.isDirectory()) {
                    File resource = new File(path, name);
                    if (resource.isFile()) return resource.toURI().toURL();
                }
            } catch (IOException | java.net.URISyntaxException | IllegalStateException ignored) { }
            return null;
        }

        @Override public String toString() { return "dex file \"" + path + "\""; }
    }
}
