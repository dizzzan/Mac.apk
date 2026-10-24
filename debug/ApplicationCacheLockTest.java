import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ApplicationCacheLockTest {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        Application app = new Application() {
            @Override public File getFilesDir() { return new File(root, "files"); }
        };
        app.macapkSetDataDir(root);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Object[] services = new Object[8];
        SharedPreferences[] prefs = new SharedPreferences[8];
        CountDownLatch done = new CountDownLatch(8);
        // Model a synchronized Application.onCreate waiting for initialization
        // workers. Both cache misses and concurrent hits must complete here.
        synchronized (app) {
            for (int i = 0; i < 8; ++i) {
                final int index = i;
                Thread t = new Thread(() -> {
                    try {
                        prefs[index] = app.getSharedPreferences("cache-lock", Context.MODE_PRIVATE);
                        services[index] = app.getSystemService(Context.ACTIVITY_SERVICE);
                    } catch (Throwable failure) { error.compareAndSet(null, failure); }
                    finally { done.countDown(); }
                });
                t.setDaemon(true);
                t.start();
            }
            if (!done.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("Context caches blocked on the Application monitor");
            }
        }
        if (error.get() != null) throw new AssertionError(error.get());
        for (int i = 0; i < 8; ++i) {
            if (prefs[i] == null || prefs[i] != prefs[0] || services[i] == null || services[i] != services[0])
                throw new AssertionError("Concurrent cache calls must return the same instances");
        }
        System.out.println("Preferences and services complete under the Application lock and preserve cache identity");
    }
}
