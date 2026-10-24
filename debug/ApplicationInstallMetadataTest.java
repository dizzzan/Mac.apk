import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.io.File;

/** Run with MACAPK_LOG_LEVEL=99 on a plain JVM (no native log sink):
 * javac -cp 'runtime/build/lib/*' -d /tmp/macapk-tests debug/ApplicationInstallMetadataTest.java
 * MACAPK_LOG_LEVEL=99 java -cp '/tmp/macapk-tests:runtime/build/lib/*' ApplicationInstallMetadataTest /tmp/macapk-tests/install
 */
public final class ApplicationInstallMetadataTest {
    private static final String PACKAGE = "example.install.metadata";

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getAbsoluteFile();
        Application app = new Application();
        // SharedPrefsImpl can ask for targetSdk from a subclass constructor,
        // before Boot has supplied any of this Application's install metadata.
        app.getApplicationInfo();
        app.getPackageManager();
        app.macapkInit(PACKAGE, new File(root, "files"), new File(root, "cache"), null, null);
        // Also exercise a query between the two initialization passes.
        app.getApplicationInfo();
        File apk = new File(root, "base.apk");
        File libs = new File(root, "lib/arm64");
        app.macapkInitInstall(apk.getPath(), root, libs);
        app.macapkSetDataDir(new File(root, "data"));
        app.macapkInitVersion(23, 36, "2.0", 2);
        ApplicationInfo info = app.getApplicationInfo();
        verify(info, apk, libs);
        PackageManager pm = app.getPackageManager();
        verify(pm.getApplicationInfo(PACKAGE, 0), apk, libs);
        if (app.getApplicationInfo() != info || app.getPackageManager() != pm)
            throw new AssertionError("Initialized context must retain cache identity");
        if (!"2.0".equals(pm.getPackageInfo(PACKAGE, 0).versionName))
            throw new AssertionError("PackageManager retained pre-install version");
        System.out.println("Early context reads do not retain incomplete install metadata");
    }

    private static void verify(ApplicationInfo info, File apk, File libs) {
        if (!PACKAGE.equals(info.packageName) || !apk.getPath().equals(info.sourceDir)
                || !libs.getPath().equals(info.nativeLibraryDir) || info.targetSdkVersion != 36)
            throw new AssertionError("Incomplete install record: " + info.packageName
                    + " apk=" + info.sourceDir + " libs=" + info.nativeLibraryDir
                    + " sdk=" + info.targetSdkVersion);
    }
}
