import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.macapk.loader.MacApkWindowManager;
import com.macapk.loader.WindowOverlayStack;

/** A loading window must be removable by the usual getParent() attachment guard. */
public class WindowOverlayParentTest {
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    static class Root extends FrameLayout {
        ViewParent parentOnAttach, parentOnDetach;
        int attaches, detaches;
        Root(Activity activity) { super(activity); }
        @Override public void onAttachedToWindow() {
            super.onAttachedToWindow();
            parentOnAttach = getParent();
            attaches++;
        }
        @Override public void onDetachedFromWindow() {
            parentOnDetach = getParent();
            detaches++;
            super.onDetachedFromWindow();
        }
    }
    public static void main(String[] args) {
        Activity activity = new Activity();
        WindowManager wm = new MacApkWindowManager();
        Root loader = new Root(activity);
        View child = new View(activity);
        loader.addView(child);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        check(loader.getParent() == null && !loader.isAttachedToWindow(), "initially detached");
        wm.addView(loader, params);
        ViewParent parent = loader.getParent();
        check(parent != null && !(parent instanceof View), "window root needs a non-View parent");
        check(loader.parentOnAttach == parent && loader.attaches == 1, "parent assigned before attach callback");
        check(loader.isAttachedToWindow() && child.isAttachedToWindow() && loader.isShown(), "tree attached and visible");
        check(child.getParent() == loader && child.getRootView() == loader && parent.getParent() == null,
                "root parent terminates hierarchy without wrapping the view tree");
        check(loader.getLayoutParams() == params, "window keeps layout params");
        android.view.ViewRootImpl scheduler = android.view.ViewRootImpl.getInstance();
        scheduler.consumeTraversalScheduled();
        scheduler.consumeLayoutRequested();
        parent.invalidateChild(loader, null);
        check(scheduler.consumeTraversalScheduled() && !scheduler.consumeLayoutRequested(),
                "root invalidation schedules drawing");
        parent.requestLayout();
        check(scheduler.consumeTraversalScheduled() && scheduler.consumeLayoutRequested(),
                "root layout request schedules layout and drawing");
        WindowOverlayStack.snapshot().get(0).bounds.set(0, 0, 100, 100);
        check(WindowOverlayStack.deliverTouch(MotionEvent.ACTION_DOWN, 50, 50), "loading window consumes touch");
        WindowOverlayStack.deliverTouch(MotionEvent.ACTION_UP, 50, 50);
        // Same guard used by apps before dismissing a temporary loading window.
        if (loader.getParent() != null) wm.removeView(loader);
        check(WindowOverlayStack.isEmpty(), "guarded remove must not leak a modal window");
        check(loader.getParent() == null && !loader.isAttachedToWindow() && !child.isAttachedToWindow(),
                "remove detaches tree and clears root parent");
        check(loader.parentOnDetach == parent && loader.detaches == 1, "detach callback precedes parent removal");
        check(!loader.isShown() && child.getParent() == loader, "detached subtree structure preserved");
        WindowOverlayStack.macapkGestureDown();
        check(!WindowOverlayStack.deliverTouch(MotionEvent.ACTION_DOWN, 50, 50), "dismissed window no longer blocks swipes");
        wm.removeViewImmediate(loader);
        check(loader.detaches == 1, "repeated remove does not repeat detach");
        wm.addView(loader, params);
        check(loader.getParent() != null && loader.getParent() != parent && loader.attaches == 2, "reuse creates new window parent");
        Root second = new Root(activity);
        wm.addView(second, params);
        check(second.getParent() != loader.getParent() && WindowOverlayStack.size() == 2, "windows have independent parents");
        wm.removeViewImmediate(loader);
        check(second.getParent() != null && second.isAttachedToWindow() && WindowOverlayStack.size() == 1, "remove affects only its own window");
        WindowOverlayStack.closeAll(activity);
        check(WindowOverlayStack.isEmpty() && second.getParent() == null && second.detaches == 1, "activity cleanup clears root parent");
        boolean rejected = false;
        try { wm.addView(child, params); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected && child.getParent() == loader && WindowOverlayStack.isEmpty(),
                "adding a parented view must not steal it from its container");
        System.out.println(checks + " window parent/lifecycle/touch checks passed");
    }
}
