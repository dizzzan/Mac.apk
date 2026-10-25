package android.view.accessibility;

import android.os.IInterface;
import android.os.RemoteException;
import java.util.List;

/** Local callback contract from AOSP's corresponding AIDL interface.
 *  Mac.apk has no cross-process accessibility connection transport. */
public interface IAccessibilityInteractionConnectionCallback extends IInterface {
    void setFindAccessibilityNodeInfoResult(AccessibilityNodeInfo info, int interactionId)
            throws RemoteException;
    void setFindAccessibilityNodeInfosResult(List<AccessibilityNodeInfo> infos, int interactionId)
            throws RemoteException;
    void setPrefetchAccessibilityNodeInfoResult(List<AccessibilityNodeInfo> infos, int interactionId)
            throws RemoteException;
    void setPerformAccessibilityActionResult(boolean succeeded, int interactionId)
            throws RemoteException;
    void sendTakeScreenshotOfWindowError(int errorCode, int interactionId) throws RemoteException;
    void sendAttachOverlayResult(int result, int interactionId) throws RemoteException;
}
