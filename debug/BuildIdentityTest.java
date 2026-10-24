import android.os.Build;
import android.os.SystemProperties;
import java.util.Objects;

/** Run in separate JVMs with Build or SystemProperties initialized first. */
public class BuildIdentityTest {
    private static int checks;
    private static void equal(Object expected, Object actual, String name) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Class.forName(args[0].equals("properties")
                ? "android.os.SystemProperties" : "android.os.Build");
        equal(args[1], Build.BRAND, "brand");
        equal(args[2], Build.MANUFACTURER, "manufacturer");
        equal(args[3], Build.MODEL, "model");
        equal(Build.BRAND, SystemProperties.get("ro.product.brand"), "property brand");
        equal(Build.MANUFACTURER, SystemProperties.get("ro.product.manufacturer"), "property manufacturer");
        equal(Build.MODEL, SystemProperties.get("ro.product.model"), "property model");
        String fingerprint = args[1] + "/macapk/macapk:" + Build.VERSION.RELEASE
                + "/PR1/1:user/release-keys";
        equal(fingerprint, Build.FINGERPRINT, "derived fingerprint");
        equal(fingerprint, SystemProperties.get("ro.build.fingerprint"), "property fingerprint");
        equal(fingerprint, SystemProperties.get("ro.system.build.fingerprint"), "partition property");
        equal(fingerprint, Build.getFingerprintedPartitions().get(0).getFingerprint(), "partition API");
        equal("apple_silicon", Build.HARDWARE, "host hardware");
        equal("apple_silicon", SystemProperties.get("ro.board.platform"), "board platform");
        equal("macapk-user", SystemProperties.get("ro.build.flavor"), "build flavor");
        equal("arm64-v8a", Build.CPU_ABI, "native ABI");
        equal("unknown", Build.SERIAL, "serial");
        equal(null, Build.getRadioVersion(), "baseband");
        equal("", SystemProperties.get("ro.boot.verifiedbootstate"), "attestation not supplied");
        equal(Integer.toString(Build.VERSION.SDK_INT), SystemProperties.get("ro.build.version.sdk"), "SDK");
        equal(Integer.parseInt(args[4]), Build.VERSION.SDK_INT, "selected SDK");
        equal(args[5], Build.VERSION.RELEASE, "selected release");
        equal("", SystemProperties.get("macapk.unknown.property"), "unknown property");
        System.out.println(checks + " identity checks passed (" + args[0] + " first)");
    }
}
