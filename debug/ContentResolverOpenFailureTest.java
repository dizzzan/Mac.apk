import android.content.ContentProvider;
import android.content.ContentProviderRegistry;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import java.io.FileNotFoundException;

/** Missing providers must trigger media clients' URL fallback, unlike a null provider result. */
public class ContentResolverOpenFailureTest {
    static int checks;
    interface Open { Object run() throws Exception; }
    static void missing(Open open) throws Exception {
        try {
            open.run();
            throw new AssertionError("missing provider silently accepted");
        } catch (FileNotFoundException expected) {
            checks++;
        }
    }
    public static void main(String[] args) throws Exception {
        ContentResolver resolver = new ContentResolver();
        Uri network = Uri.parse("https://media.invalid/movie.mp4");
        Uri unknown = Uri.parse("content://missing-test-provider/movie");
        missing(() -> resolver.openAssetFileDescriptor(network, "r"));
        missing(() -> resolver.openAssetFileDescriptor(unknown, "r", null));
        missing(() -> resolver.openFileDescriptor(network, "r"));
        missing(() -> resolver.openTypedAssetFileDescriptor(unknown, "video/*", null));
        missing(() -> resolver.openTypedAssetFile(network, "video/*", null, null));

        ContentProviderRegistry.register("null-test-provider", new ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) { return null; }
        });
        Uri supplied = Uri.parse("content://null-test-provider/movie");
        if (resolver.openAssetFileDescriptor(supplied, "r") != null
                || resolver.openTypedAssetFileDescriptor(supplied, "video/*", null) != null
                || resolver.openFileDescriptor(supplied, "r") != null)
            throw new AssertionError("provider null result must be preserved");
        checks += 3;
        System.out.println(checks + " content resolver open-failure checks passed");
    }
}
