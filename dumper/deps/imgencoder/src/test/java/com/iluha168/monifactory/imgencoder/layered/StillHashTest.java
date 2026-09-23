package com.iluha168.monifactory.imgencoder.layered;

import com.iluha168.monifactory.imgencoder.Frame;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class StillHashTest {
    @Test
    void equalContentHashesEqual() {
        Frame a = noise(37, 21, 1);
        Frame b = new Frame(37, 21, a.argb().clone());
        assertEquals(StillHash.of(a), StillHash.of(b));
        assertEquals(StillHash.of(a).hashCode(), StillHash.of(b).hashCode());
    }

    @Test
    void sizeIsPartOfTheHash() {
        int[] pixels = noise(6, 1, 2).argb();
        assertNotEquals(StillHash.of(new Frame(2, 3, pixels)), StillHash.of(new Frame(3, 2, pixels)));
        assertNotEquals(StillHash.of(new Frame(1, 6, pixels)), StillHash.of(new Frame(6, 1, pixels)));
    }

    @Test
    void everyPixelAndBitCounts() {
        // Odd pixel counts too, where the last pixel is hashed on its own.
        for (Frame still : new Frame[]{noise(37, 21, 3), noise(8, 8, 4), noise(1, 1, 5)}) {
            StillHash original = StillHash.of(still);
            Set<StillHash> seen = new HashSet<>(Set.of(original));
            int[] argb = still.argb();
            for (int p = 0; p < argb.length; p += Math.max(1, argb.length / 50)) {
                for (int bit = 0; bit < 32; bit++) {
                    int[] changed = argb.clone();
                    changed[p] ^= 1 << bit;
                    assertTrue(seen.add(StillHash.of(new Frame(still.width(), still.height(), changed))),
                            "pixel " + p + " bit " + bit);
                }
            }
        }
    }

    @Test
    void halvesAreIndependent() {
        // A change that one mix could cancel out must still show in the other: swap two adjacent pixels, and flip the
        // same bit in two pixels.
        Frame still = noise(10, 10, 6);
        int[] swapped = still.argb().clone();
        int t = swapped[4];
        swapped[4] = swapped[5];
        swapped[5] = t;
        int[] twice = still.argb().clone();
        twice[0] ^= 0x80000000;
        twice[1] ^= 0x80000000;
        StillHash original = StillHash.of(still);
        for (int[] changed : new int[][]{swapped, twice}) {
            StillHash hash = StillHash.of(new Frame(10, 10, changed));
            assertNotEquals(original.high(), hash.high());
            assertNotEquals(original.low(), hash.low());
        }
    }

    private static Frame noise(int width, int height, long seed) {
        var random = new SplittableRandom(seed);
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++)
            argb[i] = random.nextInt();
        return new Frame(width, height, argb);
    }
}
