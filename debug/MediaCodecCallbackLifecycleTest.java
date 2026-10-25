import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Android callback threading and buffer invalidation; no native decoder needed. */
public class MediaCodecCallbackLifecycleTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), "callback timed out");
    }
    static abstract class Callback extends MediaCodec.Callback {
        public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) {
            c.releaseOutputBuffer(i, false);
        }
        public void onOutputFormatChanged(MediaCodec c, MediaFormat f) { }
        public void onError(MediaCodec c, MediaCodec.CodecException e) { throw e; }
    }
    interface Action { void run() throws Exception; }
    static void onHandler(Handler handler, Action action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        handler.post(() -> {
            try { action.run(); } catch (Throwable t) { failure.set(t); }
            finally { done.countDown(); }
        });
        await(done);
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    static void affinity(Handler handler, boolean explicit) throws Exception {
        AtomicReference<MediaCodec> ref = new AtomicReference<>();
        if (explicit) ref.set(MediaCodec.createByCodecName("callback-contract-test"));
        else onHandler(handler, () -> ref.set(MediaCodec.createByCodecName("callback-contract-test")));
        MediaCodec codec = ref.get();
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Thread> actual = new AtomicReference<>();
        Callback cb = new Callback() {
            public void onInputBufferAvailable(MediaCodec c, int i) {
                actual.set(Thread.currentThread());
                c.release(); // Must be safe from inside the callback.
                received.countDown();
            }
        };
        // Registration happens on a different thread from construction. The
        // no-handler overload must retain the codec's creation Looper.
        if (explicit) codec.setCallback(cb, handler); else codec.setCallback(cb);
        codec.start();
        try {
            await(received);
            check(actual.get() == handler.getLooper().getThread(),
                  "callback used " + actual.get() + ", expected " + handler.getLooper().getThread());
        } finally { codec.release(); }
    }
    static void queuedCancellation(Handler handler, String operation) throws Exception {
        MediaCodec codec = MediaCodec.createByCodecName("callback-contract-test");
        AtomicInteger callbacks = new AtomicInteger();
        codec.setCallback(new Callback() {
            public void onInputBufferAvailable(MediaCodec c, int i) { callbacks.incrementAndGet(); }
        }, handler);
        CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1);
        CountDownLatch appMessage = new CountDownLatch(1);
        handler.post(() -> {
            entered.countDown();
            try { await(unblock); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        await(entered);
        try {
            codec.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (handler.getLooper().getQueue().isIdle() && System.nanoTime() < deadline)
                Thread.sleep(1);
            check(!handler.getLooper().getQueue().isIdle(), "codec did not enqueue events");
            handler.post(appMessage::countDown);
            switch (operation) {
                case "flush": codec.flush(); break;
                case "reset": codec.reset(); break;
                case "stop": codec.stop(); break;
                case "release": codec.release(); break;
                default: throw new AssertionError(operation);
            }
        } finally { unblock.countDown(); }
        // The app's already-queued work must survive codec cancellation.
        await(appMessage);
        onHandler(handler, () -> check(callbacks.get() == 0, operation + " delivered stale buffers"));
        codec.release();
    }
    static void restartFromCallback(Handler handler, boolean flush) throws Exception {
        MediaCodec codec = MediaCodec.createByCodecName("callback-contract-test");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger cycles = new AtomicInteger();
        codec.setCallback(new Callback() {
            public void onInputBufferAvailable(MediaCodec c, int i) {
                try {
                    check(Thread.currentThread() == handler.getLooper().getThread(), "wrong restart thread");
                    check(c.getInputBuffer(i) != null, "invalid input after restart");
                    if (cycles.incrementAndGet() == 50) {
                        c.release(); done.countDown(); return;
                    }
                    if (flush) c.flush(); else c.stop();
                    c.start();
                } catch (Throwable t) { failure.set(t); c.release(); done.countDown(); }
            }
        }, handler);
        codec.start();
        try {
            await(done);
            onHandler(handler, () -> check(cycles.get() == 50, "callbacks survived final release"));
            if (failure.get() != null) throw new AssertionError(failure.get());
        } finally { codec.release(); }
    }
    public static void main(String[] args) throws Exception {
        // Pin main before constructing a background HandlerThread.
        Looper.getMainLooper();
        HandlerThread thread = new HandlerThread("codec-client");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        try {
            affinity(handler, true);
            affinity(handler, false);
            for (String op : new String[]{"flush", "stop", "reset", "release"})
                queuedCancellation(handler, op);
            restartFromCallback(handler, false);
            restartFromCallback(handler, true);
            System.out.println("PASS: explicit/default callback threads, stale event cancellation, 100 reentrant restarts");
        } finally { thread.quit(); thread.join(5000); }
    }
}
