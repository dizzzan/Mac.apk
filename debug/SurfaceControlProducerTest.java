package android.view;

/** Build SurfaceControlTestHost.c as a shared library with the JDK's include
 * and include/darwin (or include/linux) paths. Pass its absolute path to main.
 * Exercises the player pattern: create producer, attach later, then reuse it
 * for another view. No native compositor is needed to inspect frame routing. */
public class SurfaceControlProducerTest {
    static int checks;
    static void check(boolean condition) {
        if (!condition) throw new AssertionError("check " + (checks + 1));
        checks++;
    }
    static class Sink extends SurfaceView {
        int frames, pixel;
        Sink() { super(new android.app.Application()); }
        @Override boolean macapkPresentThroughSurfaceLayer(int[] pixels, int w, int h) {
            frames++;
            pixel = pixels[0];
            return true;
        }
    }
    static void frame(Surface surface, int color) {
        check(surface.macapkPostNativeFrame(new int[]{color}, 1, 1));
    }
    public static void main(String[] args) {
        System.load(java.nio.file.Path.of(args[0]).toAbsolutePath().toString());
        Sink first = new Sink(), second = new Sink();
        SurfaceControl child = new SurfaceControl.Builder().setName("video").build();
        Surface producer = new Surface(child);
        frame(producer, 1); // unattached must not fall through to a host window
        check(producer.macapkGetOwnerSurfaceView() == null);
        SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        int[] commits = {0};
        transaction.reparent(child, first.getSurfaceControl()).show(child)
                .setBufferSize(child, 100, 200)
                .addTransactionCommittedListener(Runnable::run, () -> {
                    check(producer.macapkGetOwnerSurfaceView() == first);
                    commits[0]++;
                });
        check(producer.macapkGetOwnerSurfaceView() == null && child.getWidth() == 0);
        transaction.apply();
        check(commits[0] == 1 && child.getWidth() == 100 && child.getHeight() == 200);
        frame(producer, 2);
        check(first.frames == 1 && first.pixel == 2 && second.frames == 0);
        transaction.reparent(child, second.getSurfaceControl());
        frame(producer, 3);
        check(first.frames == 2 && second.frames == 0);
        transaction.apply();
        frame(producer, 4);
        check(second.frames == 1 && second.pixel == 4 && commits[0] == 1);
        transaction.hide(child).apply();
        frame(producer, 5);
        check(second.frames == 1);
        transaction.show(child).reparent(child, null).apply();
        frame(producer, 6);
        check(second.frames == 1 && producer.macapkGetOwnerSurfaceView() == null);

        SurfaceControl.Transaction other = new SurfaceControl.Transaction();
        other.reparent(child, first.getSurfaceControl())
                .addTransactionCommittedListener(Runnable::run, () -> commits[0]++);
        transaction.merge(other);
        other.apply();
        check(producer.macapkGetOwnerSurfaceView() == null && commits[0] == 1);
        transaction.applyAsyncUnsafe();
        frame(producer, 7);
        check(first.pixel == 7 && commits[0] == 2);
        transaction.reparent(child, second.getSurfaceControl());
        transaction.close();
        transaction.apply();
        check(producer.macapkGetOwnerSurfaceView() == first);

        Surface another = new Surface(child);
        producer.release();
        check(!producer.macapkPostNativeFrame(new int[]{8}, 1, 1));
        child.release();
        first.getSurfaceControl().release();
        frame(another, 9); // existing queues outlive released control handles
        check(first.pixel == 9);
        try { new Surface(child); throw new AssertionError(); }
        catch (NullPointerException expected) { checks++; }
        try { new Surface((SurfaceControl) null); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }

        SurfaceControl direct = new SurfaceControl.Builder().setLocalOwnerView(second)
                .setHidden(false).build();
        producer.copyFrom(direct); // rebinding also restores a released producer
        frame(producer, 10);
        check(second.pixel == 10);
        SurfaceControl nested = new SurfaceControl.Builder().setParent(direct)
                .setHidden(false).build();
        frame(new Surface(nested), 11);
        check(second.pixel == 11);
        new SurfaceControl.Transaction().hide(direct).apply();
        frame(new Surface(nested), 12);
        check(second.pixel == 11);
        System.out.println(checks + " SurfaceControl producer checks passed");
    }
}
