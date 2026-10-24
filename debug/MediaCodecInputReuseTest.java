import android.media.MediaCodec;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.nio.InvalidMarkException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Buffer ownership/position contract; no native decoder needed. */
public class MediaCodecInputReuseTest {
    static void checkEmpty(ByteBuffer b) {
        if (b.position() != 0 || b.limit() != b.capacity())
            throw new AssertionError("reused input wasn't cleared: " + b);
        try { b.reset(); throw new AssertionError("stale mark"); }
        catch (InvalidMarkException expected) { }
    }
    static void fill(ByteBuffer b) {
        b.limit(32); b.putInt(0x12345678); b.mark();
    }
    public static void main(String[] args) throws Exception {
        MediaCodec codec = MediaCodec.createByCodecName("buffer-contract-test");
        codec.start();
        ByteBuffer[] cached = codec.getInputBuffers();
        for (int n = 0; n < 32; ++n) {
            int i = codec.dequeueInputBuffer(0);
            if (i < 0) throw new AssertionError("missing input");
            checkEmpty(cached[i]);
            fill(cached[i]);
            codec.queueInputBuffer(i, 0, 4, n, 0);
        }
        for (int n = 0; n < 32; ++n) {
            int i = codec.dequeueInputBuffer(0);
            if (i < 0) throw new AssertionError("missing input");
            // Each getInputBuffer returns a cleared view, including a second
            // retrieval during the same ownership period.
            ByteBuffer b = codec.getInputBuffer(i);
            checkEmpty(b); fill(b);
            checkEmpty(codec.getInputBuffer(i)); fill(b);
            codec.queueInputBuffer(i, 0, 4, n, 0);
        }
        codec.stop();
        CountDownLatch done = new CountDownLatch(32);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        codec.setCallback(new MediaCodec.Callback() {
            public void onInputBufferAvailable(MediaCodec c, int i) {
                try {
                    ByteBuffer b = c.getInputBuffer(i);
                    checkEmpty(b); fill(b);
                    c.queueInputBuffer(i, 0, 4, 0, 0);
                } catch (Throwable t) { failure.compareAndSet(null, t); }
                finally { done.countDown(); }
            }
            public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) {
                c.releaseOutputBuffer(i, false);
            }
            public void onOutputFormatChanged(MediaCodec c, MediaFormat f) { }
            public void onError(MediaCodec c, MediaCodec.CodecException e) { failure.set(e); }
        });
        codec.start();
        boolean completed = done.await(5, TimeUnit.SECONDS);
        codec.stop(); codec.release();
        if (!completed) throw new AssertionError("async callbacks stalled");
        if (failure.get() != null) throw new AssertionError("async reuse", failure.get());
        System.out.println("PASS: cached, per-index and asynchronous input reuse clear position/limit/mark");
    }
}
