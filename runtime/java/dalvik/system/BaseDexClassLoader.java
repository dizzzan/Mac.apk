// dalvik.system.BaseDexClassLoader -- in AOSP this is the common superclass of
// PathClassLoader (the app's own loader) and DexClassLoader, sitting between
// java.lang.ClassLoader and those subclasses. Apps + the NDK GameActivity SDK
// (SmashHit) reference it by name and sometimes cast getClassLoader() to it to
// read findLibrary()/getLdLibraryPath(). A missing class threw
// NoClassDefFoundError during onCreate.
//
// Mac.apk's real loader is a URLClassLoader chain, so we slot BaseDexClassLoader
// in as a URLClassLoader subclass; AppClassLoader (our PathClassLoader analogue)
// and DexClassLoader both extend it, so getClassLoader() IS-A BaseDexClassLoader
// and the casts succeed.
package dalvik.system;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

public class BaseDexClassLoader extends URLClassLoader {
    // Android split-module loaders reach this exact field by reflection.
    private final DexPathList pathList;

    /** AOSP signature. dexPath is a colon-separated list of code paths;
     *  optimizedDirectory + librarySearchPath are advisory on a real device
     *  and ignored here. */
    public BaseDexClassLoader(String dexPath, File optimizedDirectory,
                              String librarySearchPath, ClassLoader parent) {
        super(buildUrls(dexPath), parent);
        pathList = new DexPathList(this, super.getURLs(), librarySearchPath);
        // ART searches a loader's JNI libraries in path-sorted order; see
        // ArtJniLibraryOrder. Before anything can load through this loader.
        ArtJniLibraryOrder.apply(this);
    }

    /** Internal constructor used by subclasses that already have URL[] form
     *  (AppClassLoader, the no-dex DexClassLoader path). */
    protected BaseDexClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
        pathList = new DexPathList(this, urls, null);
        ArtJniLibraryOrder.apply(this);
    }

    /** ART accepts a SLASH-separated class name here; HotSpot does not. On a
     *  device, BaseDexClassLoader.findClass reaches DexFile_defineClassNative
     *  (art/runtime/native/dalvik_system_DexFile.cc:490), which looks the name
     *  up through DotToDescriptor (art/libdexfile/dex/descriptors_names.cc:196)
     *  -- "replace '.' with '/', wrap in L...;" -- so "a/b/C" and "a.b.C" name
     *  the same class. HotSpot's URLClassLoader finds the ".class" entry for a
     *  slash name (it only rewrites dots) and then refuses it in defineClass
     *  with NoClassDefFoundError("IllegalName: a/b/C").
     *
     *  Real code relies on the ART spelling. The Firebase C++ SDK caches its
     *  Java helpers as embedded DEX files, opens them with
     *  new DexClassLoader(...) and calls loadClass with the JNI name it was
     *  given (firebase-cpp-sdk app/src/util_android.cc, FindClassInFiles:
     *  "jstring class_name_object = env->NewStringUTF(class_name)" on the
     *  non-desktop path; only the desktop path replaces the separator).
     *  Measured on Temple Run 2: Remote Config's
     *  com/google/firebase/remoteconfig/internal/cpp/JniConfigUpdateListener
     *  was found in the converted jar and then rejected, so
     *  FirebaseRemoteConfig was never created and the game's boot threw a
     *  NullReferenceException (v1.0.1635). AppClassLoader has done the same
     *  normalisation in its own override for a long time; this puts it where
     *  every DexClassLoader / PathClassLoader inherits it. A binary name never
     *  contains '/', so no name that worked before can change meaning. */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        String bn = binaryName(name);
        if (BOOT_ARRAYS && bn != null && bn.length() > 1 && bn.charAt(0) == '[') {
            Class<?> a = bootClasspathArray(bn);
            if (a != null) return a;
        }
        return super.loadClass(bn, resolve);
    }

    /** MACAPK_BOOT_ARRAY_LOADCLASS (default ON; =0 restores the old HotSpot answer, a
     *  ClassNotFoundException for EVERY array name). */
    static final boolean BOOT_ARRAYS =
        !"0".equals(System.getenv("MACAPK_BOOT_ARRAY_LOADCLASS"));

    /** ART answers loadClass("[B") and HotSpot does not. Every ART loader chain
     *  ends at java.lang.BootClassLoader, whose findClass is
     *  {@code return Class.classForName(name, false, null);}
     *  (libcore ojluni/src/main/java/java/lang/ClassLoader.java, class
     *  BootClassLoader), and Class_classForName turns the binary name into a
     *  descriptor (DotToDescriptor) and calls ClassLinker::FindClass, which builds
     *  array classes (art/runtime/native/java_lang_Class.cc: "This is especially
     *  handy for array types"). So on a device {@code loader.loadClass("[B")}
     *  returns byte[].class for any app loader, through the parent delegation in
     *  ClassLoader.loadClass. HotSpot's ClassLoader.loadClass only ever loads
     *  class FILES, so the same call threw ClassNotFoundException.
     *
     *  Real code relies on it: Chromium's jni_zero resolves every class through
     *  {@code g_class_loader->loadClass(env, j_class_name)} (third_party/jni_zero/
     *  jni_zero.cc DefaultClassResolver, chosen precisely because FindClass on a
     *  native thread sees only the boot loader), and its array helpers ask for
     *  "[B". Measured on Reddit 2026.21.0: Cronet's ThreadPoolForeg thread hit
     *  "jni_zero.cc:38 Failed to find class [B" and the CHECK aborted the process
     *  (Trace/BPT trap) shortly after the first screen.
     *
     *  The answer mirrors the BOOT class path only, as ART's does: a primitive
     *  element, or an element the boot class path holds (here: the loader that
     *  defined the framework, i.e. JDK + android.*). An array of an APP class is
     *  NOT answered here -- ART's BootClassLoader cannot see app classes either,
     *  and the app's own DexPathList holds no array classes -- so it keeps its
     *  old ClassNotFoundException. An element that THIS loader resolves to a
     *  different class (an app's child-first copy of a class our framework jar
     *  also carries, e.g. the GMS family) is rejected for the same reason: on a
     *  device that class is not on the boot class path at all. */
    private Class<?> bootClasspathArray(String bn) {
        int dims = 0;
        while (dims < bn.length() && bn.charAt(dims) == '[') dims++;
        String elem = bn.substring(dims);
        ClassLoader boot = BaseDexClassLoader.class.getClassLoader();
        try {
            if (elem.length() == 1) {
                // "BCDFIJSZ"; Class.forName validates the descriptor itself.
                return Class.forName(bn, false, boot);
            }
            if (elem.length() > 2 && elem.charAt(0) == 'L'
                    && elem.charAt(elem.length() - 1) == ';') {
                String elemName = elem.substring(1, elem.length() - 1);
                Class<?> bootElem = Class.forName(elemName, false, boot);
                Class<?> mine = loadClass(elemName, false);
                if (mine != bootElem) return null;
                return Class.forName(bn, false, boot);
            }
        } catch (ClassNotFoundException | LinkageError notOnBootClasspath) {
            // ART: BootClassLoader.findClass threw; ClassLoader.loadClass then asks
            // this loader's own findClass, which cannot define an array class.
        }
        return null;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return pathList.isModified() ? pathList.findClass(binaryName(name))
                : super.findClass(binaryName(name));
    }

    /** DexFile.loadClass(name, loader) entry point: define {@code binaryName}
     *  from {@code bytes} with THIS loader as the defining loader, as ART's
     *  DexFile_defineClassNative does (art/runtime/native/dalvik_system_DexFile.cc:
     *  "class_linker->DefineClass(soa.Self(), descriptor.c_str(), ..., class_loader,
     *  *dex_file, *dex_class_def)" with class_loader = the loader the caller
     *  passed), so a later {@code Class.forName(name)} / {@code loadClass(name)}
     *  on this loader finds it. A class this loader already holds is returned
     *  as is (ART's DefineClass returns the class another thread inserted first).
     *  Package-private: only dalvik.system.DexFile reaches it. */
    final Class<?> defineClassFromDexFile(String binaryName, byte[] bytes) {
        synchronized (getClassLoadingLock(binaryName)) {
            Class<?> c = findLoadedClass(binaryName);
            if (c != null) return c;
            return macapkDefineDexFileClass(binaryName, bytes);
        }
    }

    /** Hook for {@link #defineClassFromDexFile}; AppClassLoader overrides it to
     *  run the same class-file rewriter its own findClass applies. */
    protected Class<?> macapkDefineDexFileClass(String binaryName, byte[] bytes) {
        return defineClass(binaryName, bytes, 0, bytes.length);
    }

    static String binaryName(String name) {
        return name != null && name.indexOf('/') >= 0 ? name.replace('/', '.') : name;
    }

    /** AOSP exposes the library search path; native-lib loaders read it. We
     *  have no Android ld.so search path, so report empty (callers fall back
     *  to ClassLoader.findLibrary, which our AppClassLoader implements). */
    public String getLdLibraryPath() {
        return "";
    }

    /** AOSP hidden API: convert and append code to the same mutable path list
     * used by reflective split installers. Unusable inputs surface at lookup. */
    public void addDexPath(String dexPath) {
        addDexPath(dexPath, false);
    }

    /** AOSP signature: the boolean selects whether the new element is treated as
     *  trusted (affects hidden-API enforcement on device). Irrelevant here; we
     *  honor only the path side-effect. */
    public void addDexPath(String dexPath, boolean isTrusted) {
        if (dexPath != null && !dexPath.isEmpty()) pathList.addDexPath(dexPath);
    }

    /** Direct URL additions are part of the original JVM path; reflective
     * edits to dexElements are served in their actual array order. */
    @Override
    protected void addURL(URL url) {
        super.addURL(url);
        if (pathList != null) pathList.addOriginalURL(url);
    }

    protected final boolean macapkHasModifiedDexPath() { return pathList.isModified(); }

    /** Register Boot's already-validated conversion for repeated APK loading. */
    protected final void macapkRegisterInstalledDex(File source, URL jar) {
        MacApkDexConverter.registerInstalledSource(source, jar);
    }

    @Override
    public URL findResource(String name) {
        return pathList.isModified() ? pathList.findResource(name) : super.findResource(name);
    }

    @Override
    public java.util.Enumeration<URL> findResources(String name) throws java.io.IOException {
        return pathList.isModified() ? pathList.findResources(name) : super.findResources(name);
    }

    @Override
    public void close() throws java.io.IOException {
        try { super.close(); } finally { pathList.close(); }
    }

    /** Mac.apk-internal: open every jar on this loader's path NOW, then unlink
     *  the ephemeral ones.
     *
     *  URLClassPath opens a jar LAZILY, by path, on the first lookup that walks
     *  that far (JDK 21 URLClassPath.getLoader), and a path it cannot open at
     *  that moment is dropped for good ("Silently ignore for now"). ART maps its
     *  dex elements when the loader is constructed. So without this, a converted
     *  jar removed between construction and first lookup -- the app clearing its
     *  own getCodeCacheDir(), another process pruning the cache, an in-place
     *  update -- becomes a silent ClassNotFoundException. A findResource miss
     *  walks EVERY entry, and each JarLoader's constructor calls ensureOpen(),
     *  so afterwards every jar is held by fd for the loader's life, which is
     *  also what lets an in-memory dex's ephemeral jar be unlinked right away.
     *
     *  super.findResource is an invokespecial to URLClassLoader.findResource:
     *  AppClassLoader and DelegateLastClassLoader override the lookup with
     *  app-facing logic that must not run here. A raw .dex path that
     *  PathClassLoader also lists fails to open as a jar and is dropped, exactly
     *  as it would be at the first lookup. */
    final void macapkOpenNow() {
        macapkOpen();
        macapkReleaseEphemeral();
    }

    /** The open half of {@link #macapkOpenNow}: force every jar open. */
    final void macapkOpen() {
        super.findResource("META-INF/MACAPK-OPEN-NOW");
    }

    /** The release half of {@link #macapkOpenNow}: unlink this loader's
     *  ephemeral jars. Only after {@link #macapkOpen}, and only for a loader
     *  that reads classes through URLClassPath's held fd. */
    final void macapkReleaseEphemeral() {
        MacApkDexConverter.releaseEphemeral(super.getURLs());
    }

    /** AOSP declares findLibrary PUBLIC on BaseDexClassLoader (it delegates
     *  to pathList.findLibrary). The androidgamesdk GameActivity calls
     *  ((BaseDexClassLoader) getClassLoader()).findLibrary(libname) directly,
     *  so the app dex's invokevirtual resolves against THIS class; without a
     *  public declaration here resolution lands on the protected
     *  java.lang.ClassLoader.findLibrary and the JVM throws
     *  IllegalAccessError (Smash Hit died in onCreate this way). The base
     *  implementation has no library knowledge -- AppClassLoader overrides
     *  with the real translate-and-cache lookup. */
    public String findLibrary(String name) {
        return null;
    }

    /** AOSP override: BaseDexClassLoader.getPackage always returns a non-null
     *  Package, synthesizing an empty one if the package has not otherwise been
     *  defined (real Android has no per-package metadata for app classes, so it
     *  fabricates a blank Package on demand). Plain URLClassLoader returns null
     *  for an undefined package, which NPEs apps that do
     *  getClass().getPackage().getName(). We mirror AOSP: try the JDK's
     *  getDefinedPackage first, and if absent define a blank one.
     *
     *  Note: getPackage(String) is deprecated on the JDK ClassLoader but it is
     *  the method AOSP overrides and the one app bytecode invokes, so the
     *  signature must stay. */
    @Override
    @SuppressWarnings("deprecation")
    protected synchronized Package getPackage(String name) {
        if (name == null) return null;
        Package p = super.getPackage(name);
        if (p != null) return p;
        try {
            // definePackage with all-null metadata mirrors AOSP's synthesized
            // blank Package. It can race/throw IllegalArgumentException if a
            // concurrent caller already defined it; re-read in that case.
            return definePackage(name, null, null, null, null, null, null, null);
        } catch (IllegalArgumentException already) {
            return super.getPackage(name);
        }
    }

    /** AOSP returns "dalvik.system.BaseDexClassLoader[DexPathList[...]]"; some
     *  crash-reporting / logging libraries parse this. We approximate with the
     *  class name plus the configured search URLs so the string is informative
     *  and stable. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName()).append("[DexPathList[[");
        URL[] urls = getURLs();
        for (int i = 0; i < urls.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("dex file \"").append(urls[i]).append('"');
        }
        sb.append("]]]");
        return sb.toString();
    }

    protected static URL[] buildUrls(String dexPath) {
        if (dexPath == null || dexPath.isEmpty()) return new URL[0];
        String[] parts = dexPath.split(":");
        URL[] urls = new URL[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                urls[n++] = new File(p).toURI().toURL();
            } catch (MalformedURLException e) {
                // AOSP skips unusable paths, surfacing errors lazily at findClass.
            }
        }
        if (n < urls.length) {
            URL[] tight = new URL[n];
            System.arraycopy(urls, 0, tight, 0, n);
            return tight;
        }
        return urls;
    }
}
