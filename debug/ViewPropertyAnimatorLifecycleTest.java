import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Exercise the public fluent API with deterministic queued starts and frames. */
public final class ViewPropertyAnimatorLifecycleTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static final class TestView extends View {
        final List<Runnable> pending = new ArrayList<>();
        TestView() { super(null); }
        @Override public void postOnAnimation(Runnable r) { pending.add(r); }
        @Override public boolean removeCallbacks(Runnable r) { return pending.remove(r); }
        void frame() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            for (Runnable r : due) r.run();
            Choreographer.macapkDriveMainFrame();
        }
    }
    public static void main(String[] args) {
        // Standalone Java tests have no NativeLog JNI; registration happens first.
        try { Looper.prepareMainLooper(); } catch (UnsatisfiedLinkError ignored) {}
        TestView view = new TestView();
        view.setAlpha(0f);
        List<String> events = new ArrayList<>();
        view.animate().alpha(1f).translationX(20f).setDuration(0).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationStart(Animator a) { events.add("start"); }
            @Override public void onAnimationEnd(Animator a) { events.add("end"); }
        });
        check(view.pending.size() == 1, "property calls enqueue one automatic start");
        check(view.getAlpha() == 0f, "fluent call waits for next frame");
        view.frame();
        check(view.getAlpha() == 1f && view.getTranslationX() == 20f, "automatic fade completes");
        check(events.equals(List.of("start", "end")), "automatic start delivers lifecycle");
        check(view.animate() == view.animate(), "view retains its property animator");

        events.clear();
        view.animate().alpha(0.5f).start();
        check(view.pending.isEmpty(), "explicit start removes queued start");
        view.frame(); view.frame();
        check(events.equals(List.of("start", "end")), "explicit start does not run twice");
        check(view.getAlpha() == 0.5f && view.getTranslationX() == 20f, "reuse consumes only new properties");

        view.animate().alpha(0f);
        view.animate().cancel();
        view.frame();
        check(view.getAlpha() == 0.5f && view.pending.isEmpty(), "cancel drops pending animation");

        final int[] actions = {0};
        view.animate().alpha(1f).setDuration(1000).withEndAction(() -> actions[0]++).start();
        view.frame();
        float beforeCancel = view.getAlpha();
        view.animate().cancel();
        check(view.getAlpha() == beforeCancel, "cancel retains current property value");
        check(actions[0] == 0, "cancel does not invoke end action");
        view.animate().alpha(0f).setDuration(0);
        view.frame();
        check(view.getAlpha() == 0f && actions[0] == 0, "end action does not leak into later run");

        view.animate().alpha(1f).translationX(30f).setDuration(1000).start();
        view.frame();
        view.animate().alpha(0.25f).setDuration(0);
        view.frame();
        check(view.getAlpha() == 0.25f, "replacement property wins over running animation");
        view.frame();
        check(view.getAlpha() == 0.25f, "older run no longer changes replaced property");
        view.animate().cancel();
        System.out.println("PASS ViewPropertyAnimatorLifecycleTest: " + checks + " checks");
    }
}
