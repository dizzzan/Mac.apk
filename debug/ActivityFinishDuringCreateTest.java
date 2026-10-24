import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import com.macapk.loader.ActivityHost;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** A redirect/splash that finishes in onCreate must not stop an unstarted activity.
 * Run with MACAPK_LOG_LEVEL=99 MACAPK_REAL_LOOPER=0 MACAPK_WINDOW_ACTIVITY=0
 * to exercise the Java activity host without a native UI/log sink. */
public class ActivityFinishDuringCreateTest {
    static final List<String> events = new ArrayList<>();
    static int active, checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message + ": " + events);
        checks++;
    }
    public static class Redirect extends Activity {
        @Override protected void onCreate(Bundle state) { events.add("create"); finish(); }
        @Override protected void onStart() { events.add("start"); }
        @Override protected void onResume() { events.add("resume"); }
        @Override protected void onPause() { events.add("pause"); }
        @Override protected void onStop() { events.add("stop"); }
        @Override protected void onDestroy() { events.add("destroy"); }
    }
    public static class Normal extends Redirect {
        @Override protected void onCreate(Bundle state) { events.add("create"); }
    }
    public static void main(String[] args) throws Exception {
        Application app = new Application();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) { active++; }
            public void onActivityResumed(Activity a) {}
            public void onActivityPaused(Activity a) { events.add("callbackPause"); }
            public void onActivityStopped(Activity a) { active--; events.add("callbackStop"); }
            public void onActivityDestroyed(Activity a) { events.add("callbackDestroy"); }
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        });
        ActivityHost.init(app, ActivityFinishDuringCreateTest.class.getClassLoader());
        Method launch = ActivityHost.class.getDeclaredMethod("performLaunch", Intent.class);
        launch.setAccessible(true);
        for (int i = 0; i < 5; i++) {
            events.clear();
            launch.invoke(null, new Intent().setComponent(new ComponentName("test.lifecycle", Redirect.class.getName())));
            check(events.equals(List.of("create", "destroy", "callbackDestroy")), "unstarted teardown");
            check(active == 0, "redirect must not decrement active count");
        }
        events.clear();
        launch.invoke(null, new Intent().setComponent(new ComponentName("test.lifecycle", Normal.class.getName())));
        check(active == 1, "normal activity started");
        ActivityHost.peek().finish();
        check(active == 0, "normal activity stopped once");
        check(events.equals(List.of("create", "start", "resume", "pause", "callbackPause",
                "stop", "callbackStop", "destroy", "callbackDestroy")), "normal teardown preserved");

        launch.invoke(null, new Intent().setComponent(new ComponentName("test.lifecycle", Normal.class.getName())));
        Activity underlying = ActivityHost.peek();
        for (int i = 0; i < 5; i++) {
            events.clear();
            launch.invoke(null, new Intent().setComponent(new ComponentName("test.lifecycle", Redirect.class.getName())));
            check(ActivityHost.peek() == underlying, "redirect returns to existing activity");
            check(active == 1, "redirect preserves foreground activity count");
            check(!events.contains("stop") && !events.contains("callbackStop"), "no unmatched stop");
        }
        underlying.finish();
        check(active == 0, "foreground count balances after redirects");
        System.out.println(checks + " finish-during-create lifecycle checks passed");
    }
}
