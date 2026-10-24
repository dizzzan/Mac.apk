package android.view;

import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import com.android.internal.os.SomeArgs;

/** Window-scoped accessibility interaction dispatcher.
 *
 * AOSP android16-release keeps this type available even when accessibility is
 * disabled; ViewRootImpl instantiates it lazily. This implements its lifecycle
 * and action/outside-touch delivery. Remote node queries still require the absent
 * accessibility connection/client transport and are not advertised here.
 */
public final class AccessibilityInteractionController {
    private final ViewRootImpl mViewRootImpl;
    private final PrivateHandler mHandler;
    private final long mMyLooperThreadId;
    private final int mMyProcessId;

    public AccessibilityInteractionController(ViewRootImpl viewRootImpl) {
        mViewRootImpl = java.util.Objects.requireNonNull(viewRootImpl);
        // Mac.apk's single ViewRootImpl is driven by the main UI looper.
        Looper looper = Looper.getMainLooper();
        mHandler = new PrivateHandler(looper);
        mMyLooperThreadId = looper.getThread().getId();
        mMyProcessId = Process.myPid();
    }

    public void notifyOutsideTouchClientThread() {
        if (Looper.myLooper() == mHandler.getLooper()) {
            notifyOutsideTouchUiThread();
        } else {
            mHandler.post(this::notifyOutsideTouchUiThread);
        }
    }

    public void performAccessibilityActionClientThread(long accessibilityNodeId, int action,
            Bundle arguments, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags,
            int interrogatingPid, long interrogatingTid) {
        Message message = mHandler.obtainMessage(1);
        message.arg1 = flags;
        message.arg2 = AccessibilityNodeInfo.getAccessibilityViewId(accessibilityNodeId);
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = AccessibilityNodeInfo.getVirtualDescendantId(accessibilityNodeId);
        args.argi2 = action;
        args.argi3 = interactionId;
        args.arg1 = callback;
        args.arg2 = arguments;
        message.obj = args;
        // There is no remote InteractionClient transport in this runtime.
        // Same-thread callers can receive their result directly; worker calls
        // are queued on the root's UI handler.
        if (Looper.myLooper() == mHandler.getLooper()) {
            mHandler.dispatchMessage(message);
        } else {
            mHandler.sendMessage(message);
        }
    }

    private void performAccessibilityActionUiThread(Message message) {
        SomeArgs args = (SomeArgs) message.obj;
        int virtualId = args.argi1, action = args.argi2, interactionId = args.argi3;
        IAccessibilityInteractionConnectionCallback callback =
                (IAccessibilityInteractionConnectionCallback) args.arg1;
        Bundle arguments = (Bundle) args.arg2;
        args.recycle();
        boolean succeeded = false;
        try {
            View root = mViewRootImpl.getView();
            if (root == null || !root.isAttachedToWindow()) return;
            View target = message.arg2 == AccessibilityNodeInfo.ROOT_ITEM_ID
                    ? root : findView(root, message.arg2);
            if (target == null || target.getWindowVisibility() != View.VISIBLE
                    || !target.isShown()) return;
            AccessibilityNodeProvider provider = target.getAccessibilityNodeProvider();
            if (provider != null) {
                succeeded = provider.performAction(virtualId, action, arguments);
            } else if (virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                succeeded = target.performAccessibilityAction(action, arguments);
            }
        } finally {
            try {
                callback.setPerformAccessibilityActionResult(succeeded, interactionId);
            } catch (RemoteException ignored) {
                // The caller may have disconnected while the action was queued.
            }
        }
    }

    private View findView(View view, int accessibilityId) {
        if (view.getAccessibilityViewId() == accessibilityId) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findView(group.getChildAt(i), accessibilityId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void notifyOutsideTouchUiThread() {
        View root = mViewRootImpl.getView();
        if (root == null || !root.isAttachedToWindow()
                || root.getWindowVisibility() != View.VISIBLE || !root.isShown()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, 0, 0, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            root.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private final class PrivateHandler extends Handler {
        PrivateHandler(Looper looper) { super(looper); }
        @Override public void handleMessage(Message message) {
            if (message.what != 1) throw new IllegalArgumentException("Unknown message type: " + message.what);
            performAccessibilityActionUiThread(message);
        }
    }
}
