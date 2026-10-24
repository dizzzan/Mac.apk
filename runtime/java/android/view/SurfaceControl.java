package android.view;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Parcel;
import android.os.Parcelable;
import java.io.Closeable;
import java.lang.ref.WeakReference;

/**
 * AOSP-faithful (minimal) android.view.SurfaceControl. Real Android uses this as
 * a SurfaceFlinger layer handle plus a batched Transaction for compositor-side
 * geometry/visibility. Mac.apk has no SurfaceFlinger (each Window is a Cocoa
 * NSWindow / Metal layer composited by macOS), so SurfaceControl is a faithful
 * handle. Transactions apply parent, buffer-size and visibility changes before
 * notifying listeners. Surface producers follow the layer's parent chain to
 * the owning SurfaceView, which supplies the host compositor's destination.
 * Other compositor geometry is currently supplied by that view. Mirrors
 * frameworks/base/core/java/android/view/SurfaceControl.java.
 */
public final class SurfaceControl implements Parcelable {
    private String mName;
    private int mWidth;
    private int mHeight;
    private boolean mValid = true;
    private Layer mLayer = new Layer();
    private static final Object LAYER_LOCK = new Object();

    // A Surface retains the buffer queue, independently of the Java control
    // handle. Releasing a control must not invalidate existing producers or
    // detach children; reparent(child, null) explicitly detaches a layer.
    static final class Layer {
        Layer parent;
        WeakReference<View> owner = new WeakReference<>(null);
        boolean visible = true;

        SurfaceView owner(boolean requireVisible) {
            synchronized (LAYER_LOCK) {
                java.util.Set<Layer> visited = java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Layer, Boolean>());
                SurfaceView result = null;
                for (Layer layer = this; layer != null; layer = layer.parent) {
                    if (!visited.add(layer) || (requireVisible && !layer.visible)) return null;
                    View view = layer.owner.get();
                    if (result == null && view instanceof SurfaceView) result = (SurfaceView) view;
                }
                return result;
            }
        }
    }

    Layer macapkLayer() {
        if (!mValid) throw new NullPointerException(
                "null SurfaceControl native object. Are you using a released SurfaceControl?");
        return mLayer;
    }

    SurfaceControl(String name, int width, int height) {
        mName = name;
        mWidth = width;
        mHeight = height;
    }

    public void release() { mValid = false; }
    public boolean isValid() { return mValid; }
    public String getName() { return mName; }
    public int getWidth() { return mWidth; }
    public int getHeight() { return mHeight; }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
    }

    public static final Parcelable.Creator<SurfaceControl> CREATOR =
            new Parcelable.Creator<SurfaceControl>() {
        @Override public SurfaceControl createFromParcel(Parcel in) {
            return new SurfaceControl(in.readString(), in.readInt(), in.readInt());
        }
        @Override public SurfaceControl[] newArray(int size) {
            return new SurfaceControl[size];
        }
    };

    /** AOSP SurfaceControl.readFromParcel(Parcel): re-point THIS handle at the
     *  layer described by the parcel, rather than allocating a new one (that is
     *  CREATOR's job). AOSP throws when the handle is already assigned a native
     *  object; ours has no native object to leak, so it simply overwrites. */
    public void readFromParcel(Parcel in) {
        if (in == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        mName = in.readString();
        mWidth = in.readInt();
        mHeight = in.readInt();
        mLayer = new Layer();
        mValid = true;
    }

    /** AOSP SurfaceControl.TrustedPresentationThresholds -- the "is this layer
     *  really visible to the user" thresholds a trusted-presentation callback is
     *  armed with. Deprecated at API 35 in favour of
     *  android.window.TrustedPresentationThresholds. The constructor's argument
     *  validation is AOSP's own. */
    @Deprecated
    public static final class TrustedPresentationThresholds {
        private final float mMinAlpha;
        private final float mMinFractionRendered;
        private final int mStabilityRequirementMs;

        public TrustedPresentationThresholds(float minAlpha, float minFractionRendered,
                int stabilityRequirementMs) {
            if (!(minAlpha > 0f && minAlpha <= 1f)) {
                throw new IllegalArgumentException("minAlpha must be greater than 0 and "
                        + "less than or equal to 1");
            }
            if (!(minFractionRendered > 0f && minFractionRendered <= 1f)) {
                throw new IllegalArgumentException("minFractionRendered must be greater "
                        + "than 0 and less than or equal to 1");
            }
            if (stabilityRequirementMs < 1) {
                throw new IllegalArgumentException("stabilityRequirementMs must be at "
                        + "least 1");
            }
            mMinAlpha = minAlpha;
            mMinFractionRendered = minFractionRendered;
            mStabilityRequirementMs = stabilityRequirementMs;
        }
        /** @hide */ public float macapkMinAlpha() { return mMinAlpha; }
        /** @hide */ public float macapkMinFractionRendered() { return mMinFractionRendered; }
        /** @hide */ public int macapkStabilityRequirementMs() { return mStabilityRequirementMs; }
    }

    /** AOSP SurfaceControl.TransactionCommittedListener: told once a transaction
     *  has been latched by the compositor. Mac.apk applies transactions
     *  synchronously, so "committed" is true the moment apply() returns -- see
     *  Transaction.apply(). */
    public interface TransactionCommittedListener {
        void onTransactionCommitted();
    }

    /** AOSP SurfaceControl.JankData: per-frame jank classification a
     *  compositor reports back. Mac.apk has no SurfaceFlinger frame timeline, so
     *  no JankData is ever produced (see AttachedSurfaceControl.
     *  registerOnJankDataListener); the type exists because the listener
     *  signature names it. */
    public static class JankData {
        public static final int JANK_NONE = 0x0;
        public static final int JANK_COMPOSER = 0x1;
        public static final int JANK_APPLICATION = 0x2;
        public static final int JANK_OTHER = 0x4;

        private final long mVsyncId;
        private final int mJankType;
        private final long mScheduledAppFrameTimeNanos;
        private final long mActualAppFrameTimeNanos;

        /** @hide -- only the framework produces JankData. */
        JankData(long vsyncId, int jankType, long scheduledAppFrameTimeNanos,
                long actualAppFrameTimeNanos) {
            mVsyncId = vsyncId;
            mJankType = jankType;
            mScheduledAppFrameTimeNanos = scheduledAppFrameTimeNanos;
            mActualAppFrameTimeNanos = actualAppFrameTimeNanos;
        }
        public long getVsyncId() { return mVsyncId; }
        public int getJankType() { return mJankType; }
        public long getScheduledAppFrameTimeNanos() { return mScheduledAppFrameTimeNanos; }
        public long getActualAppFrameTimeNanos() { return mActualAppFrameTimeNanos; }
    }

    /** AOSP SurfaceControl.OnJankDataListener. */
    public interface OnJankDataListener {
        void onJankDataAvailable(java.util.List<JankData> jankData);
    }

    /** AOSP SurfaceControl.OnJankDataListenerRegistration: the handle returned
     *  by AttachedSurfaceControl.registerOnJankDataListener. */
    public static class OnJankDataListenerRegistration {
        /** @hide */ OnJankDataListenerRegistration() {}
        /** AOSP: deliver any buffered jank data now. Nothing is ever buffered
         *  here (no frame timeline exists), so this is the same no-op AOSP
         *  performs for a registration with no pending data. */
        public void flush() {}
        /** AOSP: stop reporting after the given vsync id. */
        public void removeAfter(long afterVsync) {}
    }

    // AOSP-conformance (framework_lint vs android-30 stub jar): AOSP does not
    // mark this final. Final-when-AOSP-is-not is the dangerous direction -- an
    // app compiled against real Android that subclasses this fails to resolve
    // against our jar. See docs/aosp-conformance-audit_2026-08-23.md.
    public static class Builder {
        private String mName = "SurfaceControl";
        private int mWidth;
        private int mHeight;
        private SurfaceControl mParent;
        private View mOwner;
        private boolean mHidden = true;

        public Builder() {}
        public Builder(Object session) {}  // SurfaceSession overload (untyped: no SurfaceSession in tree)

        public Builder setName(String name) { mName = name; return this; }
        public Builder setBufferSize(int width, int height) {
            mWidth = width; mHeight = height; return this;
        }
        public Builder setFormat(int format) { return this; }
        public Builder setProtected(boolean protectedContent) { return this; }
        public Builder setSecure(boolean secure) { return this; }
        public Builder setOpaque(boolean opaque) { return this; }
        public Builder setHidden(boolean hidden) { mHidden = hidden; return this; }
        public Builder setParent(SurfaceControl parent) { mParent = parent; return this; }
        public Builder setMetadata(int key, int data) { return this; }
        public Builder setEffectLayer() { return this; }
        public Builder setColorLayer() { return this; }
        public Builder setBLASTLayer() { return this; }
        public Builder setContainerLayer() { return this; }
        public Builder setFlags(int flags) { return this; }
        public Builder setCallsite(String callsite) { return this; }
        public Builder setLocalOwnerView(View view) { mOwner = view; return this; }

        public SurfaceControl build() {
            SurfaceControl control = new SurfaceControl(mName, mWidth, mHeight);
            control.mLayer.parent = mParent == null ? null : mParent.macapkLayer();
            control.mLayer.owner = new WeakReference<>(mOwner);
            control.mLayer.visible = !mHidden;
            return control;
        }
    }

    /**
     * A batch of compositor changes. Parentage becomes observable at apply(),
     * before committed listeners run, including for already-created Surfaces.
     */
    public static class Transaction implements Closeable, Parcelable {
        public Transaction() {}
        private final java.util.ArrayList<Runnable> mChanges = new java.util.ArrayList<>();

        public void apply() {
            synchronized (LAYER_LOCK) {
                for (Runnable change : mChanges) change.run();
                mChanges.clear();
            }
            macapkFireCommitted();
        }
        public void apply(boolean sync) { apply(); }
        public void applyAsyncUnsafe() { apply(); }

        public Transaction setVisibility(SurfaceControl sc, boolean visible) {
            Layer layer = sc.macapkLayer();
            mChanges.add(() -> layer.visible = visible);
            return this;
        }
        public Transaction show(SurfaceControl sc) { return setVisibility(sc, true); }
        public Transaction hide(SurfaceControl sc) { return setVisibility(sc, false); }
        public Transaction setPosition(SurfaceControl sc, float x, float y) { return this; }
        public Transaction setScale(SurfaceControl sc, float scaleX, float scaleY) { return this; }
        public Transaction setBufferSize(SurfaceControl sc, int w, int h) {
            sc.macapkLayer();
            if (w < 0 || h < 0) throw new IllegalArgumentException("width and height must be positive");
            mChanges.add(() -> { sc.mWidth = w; sc.mHeight = h; });
            return this;
        }
        public Transaction setLayer(SurfaceControl sc, int z) { return this; }
        public Transaction setRelativeLayer(SurfaceControl sc, SurfaceControl relativeTo, int z) {
            return this;
        }
        public Transaction setLayerStack(SurfaceControl sc, int layerStack) { return this; }
        public Transaction setTransparentRegionHint(SurfaceControl sc, Region region) { return this; }
        public Transaction setAlpha(SurfaceControl sc, float alpha) { return this; }
        public Transaction setMatrix(SurfaceControl sc, float dsdx, float dtdx,
                float dtdy, float dsdy) { return this; }
        public Transaction setMatrix(SurfaceControl sc, Matrix matrix, float[] float9) { return this; }
        public Transaction setGeometry(SurfaceControl sc, Rect sourceCrop, Rect destFrame,
                int orientation) { return this; }
        public Transaction setWindowCrop(SurfaceControl sc, Rect crop) { return this; }
        public Transaction setWindowCrop(SurfaceControl sc, int width, int height) { return this; }
        public Transaction setCornerRadius(SurfaceControl sc, float cornerRadius) { return this; }
        public Transaction setBackgroundBlurRadius(SurfaceControl sc, int radius) { return this; }
        public Transaction setColor(SurfaceControl sc, float[] color) { return this; }
        public Transaction setOpaque(SurfaceControl sc, boolean isOpaque) { return this; }
        public Transaction setSecure(SurfaceControl sc, boolean isSecure) { return this; }
        public Transaction reparent(SurfaceControl sc, SurfaceControl newParent) {
            Layer layer = sc.macapkLayer();
            Layer parent = newParent == null ? null : newParent.macapkLayer();
            mChanges.add(() -> layer.parent = parent);
            return this;
        }
        public Transaction setBuffer(SurfaceControl sc, Object buffer) { return this; }
        public Transaction merge(Transaction other) {
            if (other == this) return this;
            mChanges.addAll(other.mChanges);
            other.mChanges.clear();
            if (other.mCommittedListeners != null) {
                if (mCommittedListeners == null) mCommittedListeners = new java.util.ArrayList<>();
                mCommittedListeners.addAll(other.mCommittedListeners);
                other.mCommittedListeners = null;
            }
            return this;
        }

        // ---- AOSP API-36 surface completion (android.view lane) -------------
        // Same contract as every setter above: chain and record, because macOS
        // composites the window itself and there is no SurfaceFlinger
        // transaction to submit. Each one names the AOSP member it mirrors.

        /** AOSP Transaction.setCrop(SurfaceControl, Rect): the modern name for
         *  setWindowCrop, which this class already has. */
        public Transaction setCrop(SurfaceControl sc, Rect crop) { return this; }
        /** AOSP Transaction.setDamageRegion(SurfaceControl, Region). */
        public Transaction setDamageRegion(SurfaceControl sc, Region region) { return this; }
        /** AOSP Transaction.setDataSpace(SurfaceControl, int) -- the
         *  android.hardware.DataSpace value of the layer's buffers. */
        public Transaction setDataSpace(SurfaceControl sc, int dataspace) { return this; }
        /** AOSP Transaction.setBufferTransform(SurfaceControl, int) -- one of
         *  the Surface.ROTATION_* / mirror transforms applied to the buffer. */
        public Transaction setBufferTransform(SurfaceControl sc, int transform) { return this; }
        /** AOSP Transaction.setContentPriority(SurfaceControl, int) (API 36). */
        public Transaction setContentPriority(SurfaceControl sc, int priority) { return this; }
        /** AOSP Transaction.setExtendedRangeBrightness(SurfaceControl, float, float). */
        public Transaction setExtendedRangeBrightness(SurfaceControl sc,
                float currentBufferRatio, float desiredRatio) { return this; }
        /** AOSP Transaction.setDesiredHdrHeadroom(SurfaceControl, float). */
        public Transaction setDesiredHdrHeadroom(SurfaceControl sc, float desiredRatio) {
            return this;
        }
        /** AOSP Transaction.setDesiredPresentTimeNanos(long) (API 36). */
        public Transaction setDesiredPresentTimeNanos(long desiredPresentTimeNanos) {
            return this;
        }
        /** AOSP Transaction.setFrameTimeline(long vsyncId) (API 33). */
        public Transaction setFrameTimeline(long vsyncId) { return this; }

        /** AOSP Transaction.setFrameRate(SurfaceControl, float, int) delegates to
         *  the 3-arg form with CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS, exactly as
         *  Surface.setFrameRate(float,int) does. */
        public Transaction setFrameRate(SurfaceControl sc, float frameRate, int compatibility) {
            return setFrameRate(sc, frameRate, compatibility,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
        }
        /** AOSP Transaction.setFrameRate(SurfaceControl, float, int, int). Frame
         *  rate is a HINT even on a device; a fixed-refresh-rate display accepts
         *  it and does nothing, which is what macOS's CAMetalLayer amounts to
         *  here (see Surface.setFrameRate's own comment). */
        public Transaction setFrameRate(SurfaceControl sc, float frameRate, int compatibility,
                int changeFrameRateStrategy) { return this; }
        /** AOSP Transaction.setFrameRate(SurfaceControl, Surface.FrameRateParams)
         *  (API 36), the range form. */
        public Transaction setFrameRate(SurfaceControl sc, Surface.FrameRateParams params) {
            return this;
        }
        /** AOSP Transaction.clearFrameRate(SurfaceControl): drop this layer's
         *  frame-rate vote entirely. */
        public Transaction clearFrameRate(SurfaceControl sc) { return this; }

        /** AOSP Transaction.addTransactionCommittedListener(Executor,
         *  TransactionCommittedListener). AOSP fires the listener once
         *  SurfaceFlinger has latched the transaction. apply() here is
         *  synchronous and complete on return (there is no compositor to wait
         *  for), so the listener is armed at apply() time and fires then --
         *  never dropped, which would leave a caller waiting forever. */
        public Transaction addTransactionCommittedListener(
                java.util.concurrent.Executor executor, TransactionCommittedListener listener) {
            if (executor != null && listener != null) {
                if (mCommittedListeners == null) {
                    mCommittedListeners = new java.util.ArrayList<Runnable>(1);
                }
                mCommittedListeners.add(() -> executor.execute(listener::onTransactionCommitted));
            }
            return this;
        }
        private java.util.ArrayList<Runnable> mCommittedListeners;
        /** @hide -- run by apply(); see addTransactionCommittedListener. */
        final void macapkFireCommitted() {
            if (mCommittedListeners == null) return;
            java.util.ArrayList<Runnable> pending = mCommittedListeners;
            mCommittedListeners = null;
            for (int i = 0; i < pending.size(); i++) pending.get(i).run();
        }

        /** AOSP Transaction.setTrustedPresentationCallback(SurfaceControl,
         *  TrustedPresentationThresholds, Executor, Consumer&lt;Boolean&gt;),
         *  deprecated at API 35. AOSP calls back with true once the layer has
         *  met the thresholds for the required time. Mac.apk's single window is
         *  presented directly by macOS with nothing composited over it, so a
         *  layer that is shown DOES meet the thresholds -- the callback is
         *  invoked with true, which is the answer a device gives for an
         *  unobscured, fully-opaque, on-screen layer. */
        public Transaction setTrustedPresentationCallback(SurfaceControl sc,
                TrustedPresentationThresholds thresholds,
                java.util.concurrent.Executor executor,
                java.util.function.Consumer<Boolean> listener) {
            if (executor != null && listener != null) {
                executor.execute(() -> listener.accept(Boolean.TRUE));
            }
            return this;
        }
        /** AOSP Transaction.clearTrustedPresentationCallback(SurfaceControl),
         *  deprecated at API 35. */
        public Transaction clearTrustedPresentationCallback(SurfaceControl sc) { return this; }

        @Override public void close() { mChanges.clear(); mCommittedListeners = null; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) {}

        public static final Parcelable.Creator<Transaction> CREATOR =
                new Parcelable.Creator<Transaction>() {
            @Override public Transaction createFromParcel(Parcel in) { return new Transaction(); }
            @Override public Transaction[] newArray(int size) { return new Transaction[size]; }
        };
    }
}
