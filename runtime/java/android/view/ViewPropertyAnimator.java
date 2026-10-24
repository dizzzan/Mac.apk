// View.animate() reuses this builder. Property requests form a batch that
// starts on the next animation frame, or immediately when start() is called.
// Each batch owns its properties and one-shot actions; replacing a property
// leaves unrelated properties running. ValueAnimator supplies timed frames
// with MACAPK_REAL_LOOPER and immediate completion in the legacy mode.
package android.view;

import java.util.ArrayList;

public class ViewPropertyAnimator {
    private final View target;
    private long durationMs = 300L;          // AOSP ViewPropertyAnimator default
    private long startDelayMs = 0L;
    // AOSP declares this android.animation.TimeInterpolator (NOT the narrower
    // android.view.animation.Interpolator every app compiles against via
    // View.animate().setInterpolator(...)); javac resolves call sites to the
    // TimeInterpolator descriptor regardless of the static type of the
    // argument passed, so a narrower parameter type here is a NoSuchMethodError
    // at every call site, not just a type restriction.
    private android.animation.TimeInterpolator interpolator;
    private Runnable startAction, endAction;
    private android.animation.Animator.AnimatorListener listener;
    private android.animation.ValueAnimator.AnimatorUpdateListener updateListener;
    private final java.util.Map<android.animation.ValueAnimator, ArrayList<Prop>> running = new java.util.LinkedHashMap<>();
    private final Runnable animationStarter = this::start;

    // kind: 0 x, 1 y, 2 translationX, 3 translationY, 4 scaleX, 5 scaleY, 6 alpha,
    // 7 rotation, 8 rotationX, 9 rotationY, 10 translationZ, 11 z
    private static final class Prop {
        final int kind; final float value; final boolean by;
        Prop(int k, float v, boolean b) { kind = k; value = v; by = b; }
    }
    private final ArrayList<Prop> props = new ArrayList<>();

    public ViewPropertyAnimator() { this.target = null; }
    public ViewPropertyAnimator(View target) { this.target = target; }

    private ViewPropertyAnimator add(int kind, float v, boolean by) {
        // AOSP animatePropertyBy cancels only this property in older runs and
        // posts one starter after the fluent chain. start() is optional.
        for (android.animation.ValueAnimator old : new ArrayList<>(running.keySet())) {
            ArrayList<Prop> oldProps = running.get(old);
            if (oldProps != null) {
                oldProps.removeIf(p -> p.kind == kind);
                if (oldProps.isEmpty()) old.cancel();
            }
        }
        props.removeIf(p -> p.kind == kind);
        props.add(new Prop(kind, v, by));
        if (target != null) {
            target.removeCallbacks(animationStarter);
            target.postOnAnimation(animationStarter);
        }
        return this;
    }
    public ViewPropertyAnimator x(float v)               { return add(0, v, false); }
    public ViewPropertyAnimator y(float v)               { return add(1, v, false); }
    public ViewPropertyAnimator xBy(float v)             { return add(0, v, true); }
    public ViewPropertyAnimator yBy(float v)             { return add(1, v, true); }
    public ViewPropertyAnimator translationX(float v)    { return add(2, v, false); }
    public ViewPropertyAnimator translationY(float v)    { return add(3, v, false); }
    public ViewPropertyAnimator translationXBy(float v)  { return add(2, v, true); }
    public ViewPropertyAnimator translationYBy(float v)  { return add(3, v, true); }
    public ViewPropertyAnimator scaleX(float v)          { return add(4, v, false); }
    public ViewPropertyAnimator scaleY(float v)          { return add(5, v, false); }
    public ViewPropertyAnimator scaleXBy(float v)        { return add(4, v, true); }
    public ViewPropertyAnimator scaleYBy(float v)        { return add(5, v, true); }
    public ViewPropertyAnimator alpha(float v)           { return add(6, v, false); }
    public ViewPropertyAnimator alphaBy(float v)         { return add(6, v, true); }
    public ViewPropertyAnimator rotation(float v)        { return add(7, v, false); }
    public ViewPropertyAnimator rotationBy(float v)      { return add(7, v, true); }
    public ViewPropertyAnimator rotationX(float v)       { return add(8, v, false); }
    public ViewPropertyAnimator rotationXBy(float v)     { return add(8, v, true); }
    public ViewPropertyAnimator rotationY(float v)       { return add(9, v, false); }
    public ViewPropertyAnimator rotationYBy(float v)     { return add(9, v, true); }
    public ViewPropertyAnimator translationZ(float v)    { return add(10, v, false); }
    public ViewPropertyAnimator translationZBy(float v)  { return add(10, v, true); }
    public ViewPropertyAnimator z(float v)               { return add(11, v, false); }
    public ViewPropertyAnimator zBy(float v)              { return add(11, v, true); }
    public ViewPropertyAnimator setDuration(long ms)     { durationMs = Math.max(0, ms); return this; }
    public ViewPropertyAnimator setStartDelay(long ms)   { startDelayMs = Math.max(0, ms); return this; }
    public ViewPropertyAnimator setInterpolator(android.animation.TimeInterpolator i) { interpolator = i; return this; }
    public android.animation.TimeInterpolator getInterpolator() { return interpolator; }
    public ViewPropertyAnimator setListener(android.animation.Animator.AnimatorListener l) { listener = l; return this; }
    public ViewPropertyAnimator setUpdateListener(android.animation.ValueAnimator.AnimatorUpdateListener l) { updateListener = l; return this; }
    public ViewPropertyAnimator withStartAction(Runnable r) { startAction = r; return this; }
    public ViewPropertyAnimator withEndAction(Runnable r)   { endAction = r; return this; }
    // AOSP: sets LAYER_TYPE_HARDWARE for the animation's duration and restores
    // it afterward, so translucency/rotation composite cleanly through a GL
    // layer. Mac.apk already composites every View through Metal/ANGLE
    // regardless of layer type, so there is no rendering-cost model here for
    // the hint to change -- an honest no-op keeps the fluent chain working
    // without inventing behavior Mac.apk can't back up.
    public ViewPropertyAnimator withLayer() { return this; }

    private float getProp(int kind) {
        switch (kind) {
            case 0: return target.getX();
            case 1: return target.getY();
            case 2: return target.getTranslationX();
            case 3: return target.getTranslationY();
            case 4: return target.getScaleX();
            case 5: return target.getScaleY();
            case 6: return target.getAlpha();
            case 7: return target.getRotation();
            case 8: return target.getRotationX();
            case 9: return target.getRotationY();
            case 10: return target.getTranslationZ();
            case 11: return target.getZ();
            default: return 0f;
        }
    }
    private void setProp(int kind, float v) {
        switch (kind) {
            case 0: target.setX(v); break;
            case 1: target.setY(v); break;
            case 2: target.setTranslationX(v); break;
            case 3: target.setTranslationY(v); break;
            case 4: target.setScaleX(v); break;
            case 5: target.setScaleY(v); break;
            case 6: target.setAlpha(v); break;
            case 7: target.setRotation(v); break;
            case 8: target.setRotationX(v); break;
            case 9: target.setRotationY(v); break;
            case 10: target.setTranslationZ(v); break;
            case 11: target.setZ(v); break;
            default: break;
        }
    }

    public void start() {
        if (target != null) target.removeCallbacks(animationStarter);
        final ArrayList<Prop> batch = new ArrayList<>(props);
        props.clear();
        final Runnable onStart = startAction, onEnd = endAction;
        startAction = null;
        endAction = null;
        if (batch.isEmpty() && onStart == null && onEnd == null) return;
        // Each run owns its properties and one-shot actions; future fluent
        // calls cannot mutate a running animation's targets or replay actions.
        final java.util.Map<Prop, float[]> values = new java.util.IdentityHashMap<>();
        for (Prop p : batch) {
            float from = target != null ? getProp(p.kind) : 0f;
            values.put(p, new float[]{from, p.by ? from + p.value : p.value});
        }
        android.animation.ValueAnimator animation = android.animation.ValueAnimator.ofFloat(0f, 1f);
        animation.setDuration(durationMs);
        animation.setStartDelay(startDelayMs);
        if (interpolator != null) animation.setInterpolator(interpolator);
        running.put(animation, batch);
        animation.addUpdateListener(a -> {
            float fraction = a.getAnimatedFraction();
            if (target != null) for (Prop p : batch) {
                float[] range = values.get(p);
                setProp(p.kind, range[0] + (range[1] - range[0]) * fraction);
            }
            if (updateListener != null) updateListener.onAnimationUpdate(a);
        });
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationStart(android.animation.Animator a) {
                if (listener != null) listener.onAnimationStart(a);
                if (onStart != null) onStart.run();
            }
            @Override public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
                if (listener != null) listener.onAnimationCancel(a);
            }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                running.remove(animation);
                // ValueAnimator already applied the last frame. Cancellation
                // must retain the current value and suppress withEndAction.
                if (listener != null) listener.onAnimationEnd(a);
                if (!cancelled && onEnd != null) onEnd.run();
            }
        });
        animation.start();
    }

    public void cancel() {
        if (target != null) target.removeCallbacks(animationStarter);
        props.clear();
        startAction = null;
        endAction = null;
        for (android.animation.ValueAnimator animation : new ArrayList<>(running.keySet())) animation.cancel();
    }
    public long getDuration() { return durationMs; }
    public long getStartDelay() { return startDelayMs; }
}
