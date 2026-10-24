// android.animation.AnimatorSet -- composes multiple Animator instances to
// play in sequence, together, or in an arbitrary dependency order. Material
// widgets / MotionLayout / androidx transitions use this for state changes.
//
// Tier 3 M17: real dependency-graph sequencing. The previous stub added every
// child to one list and start() started them ALL AT ONCE, so playSequentially
// behaved identically to playTogether (and Builder.with/before/after ignored
// ordering). Now playSequentially/play().before()/after() build a predecessor
// graph and start() runs each child only once all its predecessors have ENDED
// (chained via the M7 Choreographer-driven ValueAnimator's onAnimationEnd).
//
// Dependency sequencing is gated on MACAPK_REAL_LOOPER. Flag-OFF children
// still start together and snap to end; both paths track the set lifecycle.
package android.animation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AnimatorSet extends Animator {
    private final ArrayList<Animator> nodes = new ArrayList<>();
    // node -> the set of animators that must END before this node may start.
    private final Map<Animator, Set<Animator>> deps = new HashMap<>();
    private boolean durationSet = false;

    private Animator ensureNode(Animator a) {
        if (a != null && !deps.containsKey(a)) {
            nodes.add(a);
            deps.put(a, new LinkedHashSet<>());
            if (durationSet) a.setDuration(getDuration());
        }
        return a;
    }

    public void playTogether(Animator... items) {
        if (items != null) for (Animator a : items) ensureNode(a);   // no deps -> all start together
    }
    public void playTogether(Collection<Animator> items) {
        if (items != null) for (Animator a : items) ensureNode(a);
    }
    public void playSequentially(Animator... items) {
        if (items != null) playSequentially(Arrays.asList(items));
    }
    public void playSequentially(java.util.List<Animator> items) {
        if (items == null) return;
        Animator prev = null;
        for (Animator a : items) {
            ensureNode(a);
            if (prev != null && a != null) deps.get(a).add(prev);   // a waits for prev to end
            prev = a;
        }
    }
    public Builder play(Animator anim) { ensureNode(anim); return new Builder(anim); }
    public ArrayList<Animator> getChildAnimations() { return nodes; }

    @Override public AnimatorSet setDuration(long duration) {
        super.setDuration(duration);
        if (duration >= 0) {
            durationSet = true;
            // AOSP: AnimatorSet.setDuration overrides every child's duration.
            for (Animator a : nodes) if (a != null) a.setDuration(duration);
        }
        return this;
    }
    @Override public void setStartDelay(long delay) { super.setStartDelay(delay); }

    /** AOSP AnimatorSet.getTotalDuration() (android11-release):
     *      updateAnimatorsDuration();
     *      createDependencyGraph();
     *      return mTotalDuration;
     *  updateAnimatorsDuration() re-applies a set-level duration override to
     *  every child and sets the root delay node's length to the SET's own
     *  startDelay (mDelayAnim.setDuration(mStartDelay)). The graph gives every
     *  parentless node the root as parent; updatePlayTime then computes each
     *  child's start as its LATEST parent's end ("if (parent.mEndTime >=
     *  child.mStartTime) child.mStartTime = parent.mEndTime") and its end as
     *  start + its own getTotalDuration(), with DURATION_INFINITE poisoning
     *  the node and every successor. mTotalDuration is the LAST event time,
     *  and sortAnimationEvents deliberately sorts DURATION_INFINITE events
     *  last -- i.e. the max end over the DAG, INFINITE winning.
     *
     *  This is the ONE public answer that distinguishes playTogether from
     *  playSequentially (three 100ms children: 100 vs 300); the inherited
     *  base impl answered 0 for both. */
    @Override public long getTotalDuration() {
        if (durationSet) {
            for (Animator a : nodes) if (a != null) a.setDuration(getDuration());
        }
        long total = getStartDelay();
        Map<Animator, Long> memo = new HashMap<>();
        for (Animator n : nodes) {
            long e = macapkNodeEnd(n, memo, new LinkedHashSet<>());
            if (e == DURATION_INFINITE) return DURATION_INFINITE;
            if (e > total) total = e;
        }
        return total;
    }
    /** End time of `n` in the dependency graph: max(set startDelay, latest
     *  predecessor end) + n.getTotalDuration(). A dependency cycle answers
     *  DURATION_INFINITE, matching updatePlayTime's cycle branch (every node
     *  of a cycle gets mStartTime = DURATION_INFINITE). */
    private long macapkNodeEnd(Animator n, Map<Animator, Long> memo, Set<Animator> visiting) {
        Long m = memo.get(n);
        if (m != null) return m;
        if (!visiting.add(n)) return DURATION_INFINITE;
        long start = getStartDelay();
        for (Animator p : deps.get(n)) {
            if (!deps.containsKey(p)) continue;   // predecessor not in this set
            long pe = macapkNodeEnd(p, memo, visiting);
            if (pe == DURATION_INFINITE) { memo.put(n, DURATION_INFINITE); return DURATION_INFINITE; }
            if (pe > start) start = pe;
        }
        visiting.remove(n);
        long own = n.getTotalDuration();
        long e = (own == DURATION_INFINITE) ? DURATION_INFINITE : start + own;
        memo.put(n, e);
        return e;
    }

    // AOSP AnimatorSet.cancel() acts only on a started set; endAnimation()
    // clears its started state before notifying end listeners. Keep the graph
    // and listener in a per-run object so completion observers can restart the
    // set without old callbacks advancing or completing the new run.
    private Run activeRun;

    private final class Run extends AnimatorListenerAdapter {
        final List<Animator> children = new ArrayList<>(nodes);
        final Map<Animator, Integer> unmet = new HashMap<>();
        final Map<Animator, List<Animator>> successors = new HashMap<>();
        final Set<Animator> playing = new LinkedHashSet<>();
        int remaining = children.size();
        boolean terminating;

        Run() {
            for (Animator child : children) {
                unmet.put(child, 0);
                successors.put(child, new ArrayList<>());
            }
            if (android.os.Looper.MACAPK_REAL_LOOPER) {
                for (Animator child : children) for (Animator predecessor : deps.get(child)) {
                    if (!unmet.containsKey(predecessor)) continue;
                    unmet.put(child, unmet.get(child) + 1);
                    successors.get(predecessor).add(child);
                }
            }
        }

        boolean isActive() { return activeRun == this && !terminating; }

        void startChild(Animator child) {
            if (!isActive() || !playing.add(child)) return;
            child.addListener(this);
            child.start();
        }

        @Override public void onAnimationEnd(Animator child) {
            child.removeListener(this);
            if (!isActive() || !playing.remove(child)) return;
            if (--remaining == 0) {
                finish();
                return;
            }
            for (Animator successor : successors.get(child)) {
                int count = unmet.get(successor) - 1;
                unmet.put(successor, count);
                if (count == 0) startChild(successor);
            }
        }

        void finish() {
            if (activeRun != this) return;
            terminating = true;
            for (Animator child : children) child.removeListener(this);
            playing.clear();
            activeRun = null;
            paused = false;
            notifyEndListeners();
        }
    }

    @Override public boolean isStarted() { return activeRun != null; }
    @Override public boolean isRunning() { return activeRun != null && !activeRun.terminating; }

    // Wall clock of the last start(), for getCurrentPlayTime(). -1 means "never
    // started", which AOSP reports as a play time of 0.
    private long mStartTimeMillis = -1;

    /**
     * AOSP AnimatorSet.getCurrentPlayTime() (AnimatorSet.java:1122-1137): 0 when
     * the set has not started or is still inside its start delay, otherwise the
     * elapsed play time measured from the first frame, with the start delay
     * subtracted and the global animator duration scale divided out.
     *
     * AOSP measures from mLastFrameTime, i.e. the last frame the set actually
     * ticked; we measure from the wall clock at start(), because our set drives
     * its children rather than ticking a frame of its own. For a running set the
     * two agree to within one frame; for a set whose children have all ended,
     * AOSP freezes at the last frame while this keeps counting -- so read this
     * as a play time, not as a completion test (use isRunning() for that).
     */
    public long getCurrentPlayTime() {
        if (mStartTimeMillis < 0) {
            // Not yet started or during start delay.
            return 0;
        }
        long elapsed = android.os.SystemClock.uptimeMillis() - mStartTimeMillis - getStartDelay();
        return elapsed < 0 ? 0 : elapsed;
    }

    /**
     * AOSP AnimatorSet.setupStartValues()/setupEndValues() (AnimatorSet.java:
     * 643-663) fan the call out to every child node. AOSP skips its internal
     * mRootNode (a zero-length delay animator that does not exist here), so
     * every entry in `nodes` is a real child.
     */
    @Override public void setupStartValues() {
        for (Animator a : new ArrayList<>(nodes)) {
            if (a != null) {
                try { a.setupStartValues(); } catch (Throwable ignored) {}
            }
        }
    }

    @Override public void setupEndValues() {
        for (Animator a : new ArrayList<>(nodes)) {
            if (a != null) {
                try { a.setupEndValues(); } catch (Throwable ignored) {}
            }
        }
    }

    @Override public void start() {
        if (activeRun != null) return;
        mStartTimeMillis = android.os.SystemClock.uptimeMillis();
        paused = false;
        if (durationSet) for (Animator child : nodes) child.setDuration(getDuration());
        Run run = new Run();
        activeRun = run;
        // Snapshot roots before any child can end synchronously and change
        // unmet counts. Otherwise a successor may be started a second time.
        List<Animator> roots = new ArrayList<>();
        for (Animator child : run.children) if (run.unmet.get(child) == 0) roots.add(child);
        notifyStartListeners();
        if (!run.isActive()) return;
        if (run.children.isEmpty()) {
            run.finish();
            return;
        }
        for (Animator child : roots) run.startChild(child);
    }

    @Override public void cancel() {
        Run run = activeRun;
        if (run == null || run.terminating) return;
        // Cancel only playing children. Detach the sequencing listener before
        // cancelling them so their end callbacks cannot start successors. The
        // guard also makes cancellation from an observer safe to re-enter.
        run.terminating = true;
        for (Animator child : run.children) child.removeListener(run);
        notifyCancelListeners();
        for (Animator child : new ArrayList<>(run.playing)) {
            try { child.cancel(); } catch (Throwable ignored) {}
        }
        run.finish();
    }

    @Override public void end() {
        Run run = activeRun;
        if (run == null || run.terminating) return;
        run.terminating = true;
        for (Animator child : run.children) child.removeListener(run);
        for (Animator child : run.children) {
            try { child.end(); } catch (Throwable ignored) {}
        }
        run.finish();
    }

    /** AOSP AnimatorSet.Builder: play(a).with(b)/before(b)/after(b) wires the
     *  dependency graph relative to the anchor `a` passed to play(). */
    // AOSP AnimatorSet.Builder (core/java/android/animation/AnimatorSet.java,
    // android16-release) is NOT final. Ours was, so an app subclassing it failed
    // at resolution -- and `-Xverify:none` (jvm_loader.cpp:163) does NOT suppress
    // a final-superclass violation, so it is a hard failure, not a silent one.
    // Deferred from the v1.0.1040 final-modifier sweep because this file was
    // owned by another lane at the time; closed here.
    public class Builder {
        private final Animator anchor;
        Builder(Animator a) { anchor = a; }
        public Builder with(Animator anim) {
            // b plays together with the anchor: same predecessors.
            if (anim != null && anchor != null) {
                ensureNode(anim);
                deps.get(anim).addAll(deps.get(anchor));
            }
            return this;
        }
        public Builder before(Animator anim) {
            // anchor plays before b: b waits for the anchor.
            if (anim != null && anchor != null) { ensureNode(anim); deps.get(anim).add(anchor); }
            return this;
        }
        public Builder after(Animator anim) {
            // anchor plays after b: the anchor waits for b.
            if (anim != null && anchor != null) { ensureNode(anim); deps.get(anchor).add(anim); }
            return this;
        }
        public Builder after(long delay) {
            if (anchor != null) anchor.setStartDelay(anchor.getStartDelay() + delay);
            return this;
        }
    }

    public void setCurrentPlayTime(long playTime) { }

    public void reverse() { }
}


