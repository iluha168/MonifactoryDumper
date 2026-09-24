package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.FramePolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One layer's pictures over a sequence, frame 0 first, and the loop that gets stored of them: {@link FramePolicy}
 * decides how many frames from frame 0, one policy per layer.
 * <p>
 * A picture is known by a key whose equality is the picture's: a layer's {@code StillHash}, or a whole frame's
 * 64-bit hash. The policy is offered each distinct picture's index, so it compares exactly what the keys compare.
 * Pictures are kept once each, and only for frames that can be stored ({@link FramePolicy#MAX_STORED}); a picture
 * first seen past that point is never asked for.
 * <p>
 * Pure; one thread.
 */
final class LayerLoop<K> {
    private final FramePolicy policy = new FramePolicy();
    private final Map<K, Integer> indices = new HashMap<>();
    /** By index, the pictures kept: null for one first seen too late to be stored. */
    private final List<Frame> pictures = new ArrayList<>();
    private final List<K> keys = new ArrayList<>();
    /** The index each frame showed, for the frames that can be stored. */
    private final int[] shown = new int[FramePolicy.MAX_STORED];

    /**
     * Adds the next frame, which showed the picture {@code key}; {@code picture} is asked for only if it has to be
     * kept. Returns true once the loop is decided; offer nothing after that.
     */
    boolean offer(K key, Supplier<Frame> picture) {
        int frame = policy.frames();
        Integer index = indices.get(key);
        if (index == null) {
            index = keys.size();
            indices.put(key, index);
            keys.add(key);
            pictures.add(frame < FramePolicy.MAX_STORED ? picture.get() : null);
        }
        if (frame < FramePolicy.MAX_STORED) shown[frame] = index;
        return policy.offer(index);
    }

    boolean decided() {
        return policy.decided();
    }

    /** Frames offered so far. */
    int frames() {
        return policy.frames();
    }

    /** The strict period in frames, or -1 if it did not close. Only once decided. */
    int period() {
        return policy.period();
    }

    /**
     * The stored loop, one key per frame from frame 0, equal pictures as equal keys. Only once decided. A layer that
     * showed one picture over the stored frames is one key.
     */
    List<K> stored() {
        int n = policy.stored();
        List<K> out = new ArrayList<>(n);
        boolean one = true;
        for (int frame = 0; frame < n; frame++) {
            out.add(keys.get(shown[frame]));
            one &= shown[frame] == shown[0];
        }
        return one ? List.of(out.get(0)) : out;
    }

    /** The picture of {@code key}, one of {@link #stored()}. */
    Frame picture(K key) {
        Integer index = indices.get(key);
        Frame picture = index == null ? null : pictures.get(index);
        if (picture == null) throw new IllegalArgumentException("no stored picture " + key);
        return picture;
    }
}
