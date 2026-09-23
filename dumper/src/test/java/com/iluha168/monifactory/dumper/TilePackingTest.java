package com.iluha168.monifactory.dumper;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TilePackingTest {
    /** Every place inside its pass's used rectangle, which is inside its target, and no two places overlapping. */
    private static void assertSound(int[] widths, int[] heights, int maxSide, TilePacking.Layout layout) {
        assertEquals(widths.length, layout.places().size());
        for (TilePacking.Pass pass : layout.passes()) {
            assertTrue(pass.targetWidth() <= maxSide && pass.targetHeight() <= maxSide, pass.toString());
            assertEquals(TilePacking.ceilPow2(pass.width()), pass.targetWidth());
            assertEquals(TilePacking.ceilPow2(pass.height()), pass.targetHeight());
        }
        for (int i = 0; i < widths.length; i++) {
            TilePacking.Place a = layout.places().get(i);
            TilePacking.Pass pass = layout.passes().get(a.pass());
            assertTrue(a.x() >= 0 && a.y() >= 0 && a.x() + widths[i] <= pass.width()
                    && a.y() + heights[i] <= pass.height(), "tile " + i + " at " + a + " leaves " + pass);
            for (int j = 0; j < i; j++) {
                TilePacking.Place b = layout.places().get(j);
                if (a.pass() != b.pass()) continue;
                boolean apart = a.x() + widths[i] <= b.x() || b.x() + widths[j] <= a.x()
                        || a.y() + heights[i] <= b.y() || b.y() + heights[j] <= a.y();
                assertTrue(apart, "tiles " + j + " and " + i + " overlap");
            }
        }
    }

    @Test
    void oneTileGetsTheSmallestPowerOfTwoTarget() {
        TilePacking.Layout layout = TilePacking.pack(new int[]{690}, new int[]{462}, 4096);
        assertEquals(1, layout.passes().size());
        assertEquals(new TilePacking.Pass(690, 462, 1024, 512), layout.passes().get(0));
        assertEquals(new TilePacking.Place(0, 0, 0), layout.places().get(0));
    }

    @Test
    void ofEqualTargetsTheSquarestIsTaken() {
        // Four 8x8: a row of 32x8, a column of 8x32, or 16x16, all 256 pixels.
        int[] sides = {8, 8, 8, 8};
        TilePacking.Layout layout = TilePacking.pack(sides, sides, 64);
        assertEquals(new TilePacking.Pass(16, 16, 16, 16), layout.passes().get(0));
        assertSound(sides, sides, 64, layout);
    }

    @Test
    void whatDoesNotFitGoesToTheNextPass() {
        // 10x10 tiles in a 16x16 target: one per pass.
        int[] sides = {10, 10, 10, 10, 10};
        TilePacking.Layout layout = TilePacking.pack(sides, sides, 16);
        assertEquals(5, layout.passes().size());
        assertSound(sides, sides, 16, layout);
    }

    @Test
    void aTileLargerThanATargetIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TilePacking.pack(new int[]{4097}, new int[]{1}, 4096));
        assertThrows(IllegalArgumentException.class, () -> TilePacking.pack(new int[]{1}, new int[]{0}, 4096));
    }

    @Test
    void placesComeBackInTheOrderGiven() {
        // Sorted tallest first inside; the layout is still indexed like the input.
        int[] widths = {4, 4, 4}, heights = {2, 8, 4};
        TilePacking.Layout layout = TilePacking.pack(widths, heights, 64);
        assertEquals(0, layout.places().get(1).x());
        assertSound(widths, heights, 64, layout);
    }

    @Test
    void randomRecipesPackSoundly() {
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            int n = 1 + random.nextInt(150);
            int[] widths = new int[n], heights = new int[n];
            for (int i = 0; i < n; i++) {
                // Mostly slots and labels, sometimes a card-sized layer, as a recipe's tile pairs are.
                boolean card = random.nextInt(20) == 0;
                widths[i] = 2 * (card ? 300 + random.nextInt(800) : 10 + random.nextInt(200));
                heights[i] = card ? 200 + random.nextInt(600) : 10 + random.nextInt(60);
            }
            TilePacking.Layout layout = TilePacking.pack(widths, heights, 4096);
            assertSound(widths, heights, 4096, layout);
            // Nothing wasted: every pass holds at least one tile.
            boolean[] used = new boolean[layout.passes().size()];
            for (TilePacking.Place place : layout.places()) used[place.pass()] = true;
            for (boolean u : used) assertTrue(u);
        }
    }
}
