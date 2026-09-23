package com.iluha168.monifactory.imgencoder.layered;

import java.util.Arrays;

/**
 * One layer's loop: still ids in order, each shown for a whole number of ticks, then back to the first. These are a
 * layer's {@code f} and {@code d} in {@code recipes.json}.
 * <p>
 * A timeline is always in the one form the format allows, so two equal loops are equal objects: consecutive entries
 * never repeat a still, and a loop of one still lasts one tick. The first and last entries may be the same still.
 * Merging them would mean rotating the loop, and the consumer starts every layer at tick 0 on the first entry, so a
 * rotation would shift the layer's phase against the others.
 */
public final class Timeline {
    private final int[] stills;
    private final int[] ticks;
    private final long length;

    /** Takes a loop as stored. Throws if it is not in the format's form. The arrays are copied. */
    public Timeline(int[] stills, int[] ticks) {
        if (stills.length == 0)
            throw new IllegalArgumentException("a loop needs at least one still");
        if (stills.length != ticks.length)
            throw new IllegalArgumentException(stills.length + " stills but " + ticks.length + " durations");
        long length = 0;
        for (int i = 0; i < stills.length; i++) {
            if (stills[i] < 0)
                throw new IllegalArgumentException("still id " + stills[i] + " at entry " + i);
            if (ticks[i] < 1)
                throw new IllegalArgumentException("entry " + i + " lasts " + ticks[i] + " ticks");
            if (i > 0 && stills[i] == stills[i - 1])
                throw new IllegalArgumentException("entries " + (i - 1) + " and " + i + " are both still "
                        + stills[i] + "; they should be one entry");
            length += ticks[i];
        }
        if (stills.length == 1 && ticks[0] != 1)
            throw new IllegalArgumentException("a static layer lasts 1 tick, not " + ticks[0]);
        this.stills = stills.clone();
        this.ticks = ticks.clone();
        this.length = length;
    }

    /**
     * The loop a layer showed: {@code perFrame[k]} is the still it drew at tick {@code k}. Runs of one still become
     * one entry; a layer that showed one still throughout becomes the static one-tick loop.
     */
    public static Timeline of(int... perFrame) {
        if (perFrame.length == 0)
            throw new IllegalArgumentException("a loop needs at least one frame");
        int[] stills = new int[perFrame.length];
        int[] ticks = new int[perFrame.length];
        int n = 0;
        for (int still : perFrame) {
            if (n > 0 && stills[n - 1] == still) {
                ticks[n - 1]++;
            } else {
                stills[n] = still;
                ticks[n++] = 1;
            }
        }
        if (n == 1) ticks[0] = 1;
        return new Timeline(Arrays.copyOf(stills, n), Arrays.copyOf(ticks, n));
    }

    /** Entries in the loop. */
    public int size() {
        return stills.length;
    }

    public int still(int entry) {
        return stills[entry];
    }

    public int ticks(int entry) {
        return ticks[entry];
    }

    /** The loop's length in ticks: the sum of its durations. */
    public long length() {
        return length;
    }

    /** More than one still. Only a static loop has one entry, so this is also "changes over time". */
    public boolean animated() {
        return stills.length > 1;
    }

    /** The still shown at {@code tick}, counting from the first entry at tick 0. */
    public int stillAt(long tick) {
        long t = Math.floorMod(tick, length);
        for (int i = 0; ; i++) {
            t -= ticks[i];
            if (t < 0) return stills[i];
        }
    }

    /** Appends {@code "f":[...],"d":[...]}. */
    void appendJson(StringBuilder json) {
        json.append("\"f\":");
        appendArray(json, stills);
        json.append(",\"d\":");
        appendArray(json, ticks);
    }

    private static void appendArray(StringBuilder json, int[] values) {
        json.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) json.append(',');
            json.append(values[i]);
        }
        json.append(']');
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Timeline other && Arrays.equals(stills, other.stills) && Arrays.equals(ticks, other.ticks);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(stills) + Arrays.hashCode(ticks);
    }

    @Override
    public String toString() {
        return "f" + Arrays.toString(stills) + " d" + Arrays.toString(ticks);
    }
}
