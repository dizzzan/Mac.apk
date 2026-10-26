import android.app.Activity;
import android.app.ActivityOptions;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.transition.*;
import android.view.View;
import android.view.Window;
import java.util.ArrayList;
import java.util.List;

/** Apps defer playback until the shared-element enter transition completes. */
public class ActivitySceneTransitionTest {
    static int checks;
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    static Activity activity() {
        Activity a = new Activity();
        a.getWindow().requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        a.getWindow().getDecorView();
        return a;
    }
    static Bundle options(Activity a) {
        return ActivityOptions.makeSceneTransitionAnimation(a, new View(a), "video").toBundle();
    }
    static Transition transition(List<String> events, String name) {
        return new ChangeBounds().addListener(new TransitionListenerAdapter() {
            public void onTransitionStart(Transition t) { events.add(name + ":start"); }
            public void onTransitionEnd(Transition t) { events.add(name + ":end"); t.removeListener(this); }
        });
    }
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        List<String> events = new ArrayList<>();
        Activity a = activity();
        Bundle launch = options(a);
        check(launch.getInt("android:activity.animType") == 5, "scene marker survives bundle");
        check(launch.getStringArrayList("android:activity.sharedElementNames").equals(List.of("video")), "shared names survive bundle");
        ActivityOptions copy = ActivityOptions.makeBasic();
        copy.update(ActivityOptions.makeSceneTransitionAnimation(a, new View(a), "copy"));
        check(copy.toBundle().getInt("android:activity.animType") == 5, "update retains scene options");
        Window w = a.getWindow();
        Transition child = transition(events, "child");
        TransitionSet shared = new TransitionSet().addTransition(child);
        shared.addListener(new TransitionListenerAdapter() {
            public void onTransitionStart(Transition t) { events.add("set:start"); }
            public void onTransitionEnd(Transition t) { events.add("set:end"); }
        });
        w.setSharedElementEnterTransition(shared);
        check(w.getSharedElementEnterTransition() == shared, "window retains configured transition");
        check(w.getSharedElementReturnTransition() == shared, "return defaults to enter");
        w.setSharedElementReturnTransition(null);
        check(w.getSharedElementReturnTransition() == null, "explicit null return preserved");
        a.macapkSetEnterActivityOptions(launch);
        a.postponeEnterTransition();
        a.macapkEnterTransitionReady();
        Handler.drainGlobal();
        check(events.isEmpty(), "postponement honored");
        a.startPostponedEnterTransition();
        check(events.isEmpty(), "completion is queued");
        Handler.drainGlobal();
        check(events.equals(List.of("set:start", "child:start", "child:end", "set:end")), "parent and child complete in order");
        a.macapkEnterTransitionReady(); a.startPostponedEnterTransition(); Handler.drainGlobal();
        check(events.size() == 4, "resume does not repeat enter completion");

        events.clear();
        Activity ordinary = activity();
        ordinary.getWindow().setSharedElementEnterTransition(transition(events, "ordinary"));
        ordinary.macapkSetEnterActivityOptions(ActivityOptions.makeBasic().toBundle());
        ordinary.macapkEnterTransitionReady(); Handler.drainGlobal();
        check(events.isEmpty(), "ordinary launches do not run scene callbacks");
        Activity disabled = activity();
        disabled.getWindow().setSharedElementEnterTransition(null);
        disabled.macapkSetEnterActivityOptions(launch);
        disabled.macapkEnterTransitionReady(); Handler.drainGlobal();
        check(events.isEmpty(), "disabled transition stays disabled");

        Activity early = activity();
        early.getWindow().setSharedElementEnterTransition(transition(events, "early"));
        early.macapkSetEnterActivityOptions(launch);
        early.postponeEnterTransition(); early.startPostponedEnterTransition(); Handler.drainGlobal();
        check(events.isEmpty(), "unpostponing before launch ready does not run callbacks");
        early.macapkEnterTransitionReady(); Handler.drainGlobal();
        check(events.equals(List.of("early:start", "early:end")), "early unpostpone completes at ready");
        events.clear();
        Activity finished = new Activity() { @Override public boolean isFinishing() { return true; } };
        finished.getWindow().requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        finished.getWindow().setSharedElementEnterTransition(transition(events, "finished"));
        finished.macapkSetEnterActivityOptions(launch);
        finished.macapkEnterTransitionReady(); Handler.drainGlobal();
        check(events.isEmpty(), "finishing destination cannot start playback");
        System.out.println(checks + " activity scene-transition checks passed");
    }
}
