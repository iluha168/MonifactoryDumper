package com.iluha168.monifactory.imgencoder;

/**
 * Which frames of an animated recipe get stored, decided from the frame hashes alone.
 * <p>
 * Feed it the hash of frame 0, 1, 2 and so on, one frame being 50 ms of the fake clock and one atlas tick. Once
 * {@link #offer} returns true, the recipe's loop is frames {@code 0 .. stored()-1}: its true period if it closed, or
 * the first {@link #TRIM} frames if it did not close within {@link #CAP}.
 * <p>
 * The period is the strict one: the smallest {@code p} with {@code hash[k] == hash[k+p]} for every {@code k} drawn.
 * The weaker "first frame equal to frame 0" rule stores loops one frame short wherever an animation plateaus, so it
 * is not used anywhere.
 * <p>
 * Drawing all {@link #CAP} frames for every recipe would give the reference answer, and costs 400 draws a recipe.
 * This stops early, but not at the first {@code n >= 2p}: a progress arrow can hold still for longer than it takes to
 * see "two periods" of nothing, and over three recorded boots' sequences that rule disagrees with the full one on 286
 * of 2,300, nearly all a false {@code p = 1} or a short period on a plateau. So a period is accepted
 * early only once the sequence has changed at least once, and has run two periods past that first change, and at least
 * {@link #MIN_FRAMES} frames have been drawn. At {@link #CAP} the full rule answers, guard or not. On those three
 * boots of 656 recipes, from six start phases each, that gives the full rule's stored frames on every sequence of the
 * global-agent boot, the one that clocks the way this build does.
 */
public final class FramePolicy {
    /** How long a recipe may take to close. A period is at most half of it, so two full periods are seen. */
    public static final int CAP = 400;
    /** What is stored when it does not close: the ldlib progress arrow's period, 2,000 ms, so GT arrows still loop. */
    public static final int TRIM = 40;
    /** No early decision before this many frames: two arrow periods. */
    public static final int MIN_FRAMES = 80;
    /** Frames past this index are never stored, so callers need not keep their pixels. */
    public static final int MAX_STORED = CAP / 2;

    private final long[] hashes = new long[CAP];
    /** candidate[p]: hash[k] == hash[k+p] for every pair drawn so far. */
    private final boolean[] candidate = new boolean[CAP];
    private int frames;
    /** The first frame that differs from frame 0, or {@link #frames} while none has. */
    private int firstChange;
    private int period = -1;
    private boolean decided;

    public FramePolicy() {
        java.util.Arrays.fill(candidate, true);
    }

    /**
     * Adds the next frame's hash. Returns true once the stored frames are decided; no more frames are wanted then.
     */
    public boolean offer(long hash) {
        if (decided) throw new IllegalStateException("already decided after " + frames + " frames");
        int i = frames++;
        hashes[i] = hash;
        if (firstChange == i && hash == hashes[0]) firstChange = frames;
        for (int p = 1; p <= i; p++)
            if (candidate[p] && hashes[i] != hashes[i - p]) candidate[p] = false;

        int p = smallestPeriod();
        if (frames == CAP) {
            period = p;
            decided = true;
        } else if (p > 0 && firstChange < frames && frames - firstChange >= 2 * p && frames >= MIN_FRAMES) {
            period = p;
            decided = true;
        }
        return decided;
    }

    private int smallestPeriod() {
        for (int p = 1; p <= frames / 2; p++)
            if (candidate[p]) return p;
        return -1;
    }

    public boolean decided() {
        return decided;
    }

    /** Frames offered so far: after the decision, what it cost. */
    public int frames() {
        return frames;
    }

    /** The strict period in frames, or -1 if it did not close within {@link #CAP}. Only once decided. */
    public int period() {
        if (!decided) throw new IllegalStateException("not decided yet");
        return period;
    }

    /** How many frames from frame 0 to store: the period, or {@link #TRIM} if there is none. */
    public int stored() {
        return period() > 0 ? period : TRIM;
    }

    /**
     * The reference rule over a whole sequence: the smallest {@code p <= n/2} with {@code hashes[k] == hashes[k+p]} for
     * every {@code k} in {@code [0, n-p)}, or -1. This is what {@link FramePolicy} has to agree with.
     */
    public static int strictPeriod(long[] hashes, int n) {
        candidates:
        for (int p = 1; p <= n / 2; p++) {
            for (int k = 0; k + p < n; k++)
                if (hashes[k] != hashes[k + p]) continue candidates;
            return p;
        }
        return -1;
    }
}
