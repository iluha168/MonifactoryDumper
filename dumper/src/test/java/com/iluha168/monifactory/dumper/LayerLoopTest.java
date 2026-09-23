package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.FramePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

class LayerLoopTest {
    /** A 1x1 picture per key, so a stored picture says which key it was made for. */
    private static Frame picture(String key) {
        return new Frame(1, 1, new int[]{key.hashCode()});
    }

    /** Offers {@code keyAt(0)}, {@code keyAt(1)}, ... until decided, and records which pictures were asked for. */
    private static LayerLoop<String> run(IntFunction<String> keyAt, List<String> asked) {
        LayerLoop<String> loop = new LayerLoop<>();
        for (int k = 0; ; k++) {
            assertTrue(k < FramePolicy.CAP, "never decided");
            String key = keyAt.apply(k);
            if (loop.offer(key, () -> {
                asked.add(key);
                return picture(key);
            })) return loop;
        }
    }

    @Test
    void aLoopIsItsPeriodFromFrameZero() {
        // The GT arrow's shape: 40 frames, each step held 4. The loop starts at frame 0 wherever the steps stand.
        IntFunction<String> arrow = k -> "step" + ((k + 6) % 40) / 4;
        List<String> asked = new ArrayList<>();
        LayerLoop<String> loop = run(arrow, asked);
        assertEquals(40, loop.period());
        List<String> stored = loop.stored();
        assertEquals(40, stored.size());
        for (int k = 0; k < 40; k++) assertEquals(arrow.apply(k), stored.get(k));
        assertEquals(10, asked.size(), "each distinct picture is asked for once");
        for (String key : stored) assertEquals(key.hashCode(), loop.picture(key).argb()[0]);
    }

    @Test
    void aLayerThatNeverChangesIsOneStill() {
        LayerLoop<String> loop = run(k -> "same", new ArrayList<>());
        assertEquals(FramePolicy.CAP, loop.frames());
        assertEquals(List.of("same"), loop.stored());
    }

    @Test
    void anOpenLoopIsTrimmed() {
        IntFunction<String> neverRepeats = k -> "frame" + k;
        List<String> asked = new ArrayList<>();
        LayerLoop<String> loop = run(neverRepeats, asked);
        assertEquals(-1, loop.period());
        List<String> stored = loop.stored();
        assertEquals(FramePolicy.TRIM, stored.size());
        for (int k = 0; k < FramePolicy.TRIM; k++) assertEquals("frame" + k, stored.get(k));
        // Pictures are kept for frames that could be stored, and never asked for past them.
        assertEquals(FramePolicy.MAX_STORED, asked.size());
        assertThrows(IllegalArgumentException.class, () -> loop.picture("frame" + FramePolicy.MAX_STORED));
    }

    @Test
    void aTrimmedLoopThatShowsOnePictureIsOneStill() {
        // Still for longer than the trim, then moving without ever closing: what is stored is one picture.
        LayerLoop<String> loop = run(k -> k < 100 ? "still" : "frame" + k, new ArrayList<>());
        assertEquals(-1, loop.period());
        assertEquals(List.of("still"), loop.stored());
    }

    @Test
    void refusesToStoreBeforeTheDecision() {
        LayerLoop<String> loop = new LayerLoop<>();
        loop.offer("a", () -> picture("a"));
        assertFalse(loop.decided());
        assertThrows(IllegalStateException.class, loop::stored);
    }
}
