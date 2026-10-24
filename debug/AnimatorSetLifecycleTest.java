import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import java.util.ArrayList;
import java.util.List;

/** Deterministic lifecycle checks; no Choreographer or native window required. */
public final class AnimatorSetLifecycleTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static final class Child extends Animator {
        int starts, cancels, ends;
        boolean running;
        final boolean synchronous;
        Child() { this(false); }
        Child(boolean synchronous) { this.synchronous = synchronous; }
        @Override public void start() {
            running = true;
            starts++;
            notifyStartListeners();
            if (synchronous) end();
        }
        @Override public void cancel() {
            if (!running) return;
            cancels++;
            running = false;
            notifyCancelListeners();
            notifyEndListeners();
        }
        @Override public void end() {
            ends++;
            running = false;
            notifyEndListeners();
        }
        @Override public boolean isStarted() { return running; }
        @Override public boolean isRunning() { return running; }
    }
    private static List<String> events(AnimatorSet set) {
        List<String> events = new ArrayList<>();
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationStart(Animator a) { events.add("start"); }
            @Override public void onAnimationCancel(Animator a) { events.add("cancel"); }
            @Override public void onAnimationEnd(Animator a) { events.add("end"); }
        });
        return events;
    }
    public static void main(String[] args) {
        check(android.os.Looper.MACAPK_REAL_LOOPER, "run with MACAPK_REAL_LOOPER=1");
        AnimatorSet idle = new AnimatorSet();
        List<String> idleEvents = events(idle);
        idle.cancel();
        check(idleEvents.isEmpty(), "cancel before start must be silent");

        Child first = new Child(), second = new Child();
        AnimatorSet set = new AnimatorSet();
        set.playSequentially(first, second);
        List<String> history = events(set);
        set.start();
        check(set.isStarted() && set.isRunning(), "started set reports running");
        check(first.starts == 1 && second.starts == 0, "sequence starts first only");
        set.start();
        check(first.starts == 1, "start during same run is ignored");
        set.cancel();
        check(history.equals(List.of("start", "cancel", "end")), "cancel emits one end");
        check(first.cancels == 1 && second.starts == 0, "cancel must not start successors");
        check(!set.isStarted() && !set.isRunning(), "cancel clears started state");
        set.cancel();
        check(history.size() == 3, "repeated cancel is silent");
        check(first.getListeners().isEmpty(), "cancel detaches internal listener");

        history.clear();
        set.start();
        first.end();
        check(second.starts == 1 && set.isStarted(), "next child starts on predecessor end");
        final int[] endCallbacks = {0};
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                endCallbacks[0]++;
                // Reproduces observers that cancel their animation when notified of completion.
                if (endCallbacks[0] < 20) a.cancel();
            }
        });
        second.end();
        check(endCallbacks[0] == 1, "cancel from completion cannot repeat completion");
        check(history.equals(List.of("start", "end")), "natural completion fires once");
        check(!set.isStarted(), "natural completion clears started state");

        Child a = new Child(true), b = new Child(true), c = new Child(true);
        AnimatorSet synchronous = new AnimatorSet();
        synchronous.playSequentially(a, b, c);
        List<String> syncEvents = events(synchronous);
        synchronous.start();
        check(a.starts == 1 && b.starts == 1 && c.starts == 1, "synchronous chain runs once");
        check(syncEvents.equals(List.of("start", "end")), "synchronous completion fires once");

        Child stopped = new Child();
        AnimatorSet cancelOnStart = new AnimatorSet();
        cancelOnStart.play(stopped);
        List<String> startEvents = events(cancelOnStart);
        cancelOnStart.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationStart(Animator a) { a.cancel(); }
            @Override public void onAnimationCancel(Animator a) { a.cancel(); }
        });
        cancelOnStart.start();
        check(stopped.starts == 0, "cancel from start prevents child start");
        check(startEvents.equals(List.of("start", "cancel", "end")), "reentrant cancel emits once");

        Child pending = new Child(), active = new Child();
        AnimatorSet ended = new AnimatorSet();
        ended.playSequentially(active, pending);
        List<String> endEvents = events(ended);
        ended.start();
        ended.end();
        check(active.ends == 1 && pending.ends == 1, "end applies every terminal value");
        check(endEvents.equals(List.of("start", "end")), "explicit end emits once");
        ended.cancel();
        check(endEvents.size() == 2, "cancel after explicit end is silent");

        Child repeat = new Child();
        AnimatorSet restart = new AnimatorSet();
        restart.play(repeat);
        final int[] runs = {0};
        restart.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { if (++runs[0] == 1) a.start(); }
        });
        restart.start();
        repeat.end();
        check(repeat.starts == 2 && restart.isStarted(), "restart from end survives old cleanup");
        repeat.end();
        check(runs[0] == 2 && !restart.isStarted(), "restarted run finishes independently");
        System.out.println("PASS AnimatorSetLifecycleTest: " + checks + " checks");
    }
}
