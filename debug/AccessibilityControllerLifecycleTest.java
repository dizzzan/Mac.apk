import android.os.Looper;
import android.view.AccessibilityInteractionController;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import java.lang.reflect.Field;

public final class AccessibilityControllerLifecycleTest {
    private static int checks;
    private static void check(boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("check " + checks);
    }
    private static final class Root extends android.widget.FrameLayout {
        int touches, action, source;
        Thread deliveryThread;
        Root() { super(null); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            touches++;
            action = event.getAction();
            source = event.getSource();
            deliveryThread = Thread.currentThread();
            return true;
        }
    }
    private static final class Result {
        int calls, interactionId;
        boolean succeeded;
        Thread deliveryThread;
        final IAccessibilityInteractionConnectionCallback callback =
                (IAccessibilityInteractionConnectionCallback) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{IAccessibilityInteractionConnectionCallback.class},
                        (proxy, method, args) -> {
                            if (!method.getName().equals("setPerformAccessibilityActionResult")) {
                                throw new AssertionError(method);
                            }
                            calls++;
                            deliveryThread = Thread.currentThread();
                            succeeded = (Boolean) args[0];
                            interactionId = (Integer) args[1];
                            return null;
                        });
    }
    private static void action(AccessibilityInteractionController controller, long id,
            int action, boolean expected) {
        Result result = new Result();
        controller.performAccessibilityActionClientThread(id, action, null, 42,
                result.callback, 0, android.os.Process.myPid(), Thread.currentThread().getId());
        check(result.calls == 1 && result.interactionId == 42 && result.succeeded == expected);
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        ViewRootImpl owner = ViewRootImpl.getInstance();
        Field field = ViewRootImpl.class.getDeclaredField("mAccessibilityInteractionController");
        field.setAccessible(true);
        check(field.getType() == AccessibilityInteractionController.class);
        check(field.get(owner) == null);
        try {
            owner.getAccessibilityInteractionController();
            throw new AssertionError("controller without a root");
        } catch (IllegalStateException expected) { checks++; }
        Root first = new Root();
        owner.setView(first);
        check(field.get(owner) == null);
        AccessibilityInteractionController controller = owner.getAccessibilityInteractionController();
        check(field.get(owner) == controller);
        check(owner.getAccessibilityInteractionController() == controller);
        controller.notifyOutsideTouchClientThread();
        check(first.touches == 0); // Detached roots cannot receive input.
        first.macapkDispatchAttachedToWindow();
        controller.notifyOutsideTouchClientThread();
        check(first.touches == 1);
        check(first.action == MotionEvent.ACTION_OUTSIDE);
        check(first.source == InputDevice.SOURCE_TOUCHSCREEN);
        check(first.deliveryThread == Thread.currentThread());
        first.setVisibility(View.GONE);
        controller.notifyOutsideTouchClientThread();
        check(first.touches == 1);
        Root second = new Root();
        second.macapkDispatchAttachedToWindow();
        owner.setView(second);
        check(owner.getAccessibilityInteractionController() == controller);
        controller.notifyOutsideTouchClientThread();
        check(second.touches == 1 && first.touches == 1);
        View child = new View(null);
        View other = new View(null);
        child.setId(12);
        other.setId(12);
        int childId = child.getAccessibilityViewId();
        check(childId == child.getAccessibilityViewId());
        check(childId != other.getAccessibilityViewId());
        second.addView(child);
        int[] clicks = {0};
        child.setOnClickListener(v -> clicks[0]++);
        long childNode = AccessibilityNodeInfo.makeNodeId(childId, AccessibilityNodeProvider.HOST_VIEW_ID);
        action(controller, childNode, AccessibilityNodeInfo.ACTION_CLICK, true);
        check(clicks[0] == 1);
        action(controller, childNode, Integer.MAX_VALUE, false);
        child.setEnabled(false);
        action(controller, childNode, AccessibilityNodeInfo.ACTION_CLICK, false);
        child.setEnabled(true);
        child.setVisibility(View.GONE);
        action(controller, childNode, AccessibilityNodeInfo.ACTION_CLICK, false);
        child.setVisibility(View.VISIBLE);
        action(controller, AccessibilityNodeInfo.makeNodeId(childId, 9),
                AccessibilityNodeInfo.ACTION_CLICK, false);
        int[] virtualCalls = {0};
        child.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
                return new AccessibilityNodeProvider() {
                    @Override public boolean performAction(int virtualId, int action, android.os.Bundle args) {
                        check(virtualId == 9 && action == AccessibilityNodeInfo.ACTION_CLICK);
                        virtualCalls[0]++;
                        return true;
                    }
                };
            }
        });
        action(controller, AccessibilityNodeInfo.makeNodeId(childId, 9),
                AccessibilityNodeInfo.ACTION_CLICK, true);
        check(virtualCalls[0] == 1);
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.addChild(child);
        check(AccessibilityNodeInfo.getAccessibilityViewId(node.getChildId(0)) == childId);
        action(controller, AccessibilityNodeInfo.makeNodeId(other.getAccessibilityViewId(), -1),
                AccessibilityNodeInfo.ACTION_CLICK, false); // Outside this window's tree.
        second.setOnClickListener(v -> clicks[0]++);
        action(controller, AccessibilityNodeInfo.ROOT_NODE_ID, AccessibilityNodeInfo.ACTION_CLICK, true);
        check(clicks[0] == 2);
        Result queued = new Result();
        Thread worker = new Thread(() -> controller.performAccessibilityActionClientThread(
                AccessibilityNodeInfo.ROOT_NODE_ID, AccessibilityNodeInfo.ACTION_CLICK, null,
                73, queued.callback, 0, android.os.Process.myPid(), Thread.currentThread().getId()));
        worker.start();
        worker.join();
        check(queued.calls == 0);
        android.os.Handler.drainGlobal();
        check(queued.calls == 1 && queued.succeeded && queued.interactionId == 73);
        check(queued.deliveryThread == Thread.currentThread() && clicks[0] == 3);
        owner.setView(null);
        action(controller, childNode, AccessibilityNodeInfo.ACTION_CLICK, false);
        controller.notifyOutsideTouchClientThread();
        check(second.touches == 1);
        check(!new android.view.accessibility.AccessibilityManager().isEnabled());
        AccessibilityInteractionController.class.getDeclaredMethod(
                "performAccessibilityActionUiThread", android.os.Message.class);
        checks++;
        System.out.println("PASS: " + checks + " accessibility-controller lifecycle checks");
    }
}
