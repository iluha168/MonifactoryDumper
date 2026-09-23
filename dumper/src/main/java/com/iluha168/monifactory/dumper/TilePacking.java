package com.iluha168.monifactory.dumper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Where {@link TileRenderer} puts each tile: rectangles packed into passes, each pass one power-of-two render target no
 * larger than {@code maxSide} on a side. Shelf packing: the tallest first, left to right in rows as tall as the row's
 * first one. The first pass takes as many as fit, the next pass the rest, and so on.
 * <p>
 * A pass then gets the shelf width that gives the smallest power-of-two target, the squarest of equals. The target's
 * size is what a pass pays for: the draw was about ten times slower in the prototype when bound to a target far
 * larger than it used, and the readback covers only {@link Pass#width} by {@link Pass#height}. Pure.
 */
final class TilePacking {
    private TilePacking() {
    }

    /** Where one rectangle goes: its pass, and its corner in that pass's target. */
    record Place(int pass, int x, int y) {
    }

    /**
     * One pass: the rectangle the tiles use from the target's origin, and the power-of-two target that holds it.
     */
    record Pass(int width, int height, int targetWidth, int targetHeight) {
    }

    /** The passes, and where each rectangle goes, in the order they were given. */
    record Layout(List<Pass> passes, List<Place> places) {
    }

    /**
     * Packs rectangles {@code widths[i]} by {@code heights[i]}. Each has to fit a {@code maxSide} square on its own.
     * The {@code y} of a place counts up from the target's origin in whatever direction the caller reads rows.
     */
    static Layout pack(int[] widths, int[] heights, int maxSide) {
        int n = widths.length;
        if (heights.length != n) throw new IllegalArgumentException("widths and heights differ in length");
        for (int i = 0; i < n; i++) {
            if (widths[i] < 1 || heights[i] < 1 || widths[i] > maxSide || heights[i] > maxSide)
                throw new IllegalArgumentException("a " + widths[i] + "x" + heights[i] + " tile does not fit a "
                        + maxSide + "x" + maxSide + " target");
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.<Integer>comparingInt(i -> -heights[i]).thenComparingInt(i -> -widths[i])
                .thenComparingInt(i -> i));

        List<Pass> passes = new ArrayList<>();
        Place[] places = new Place[n];
        int start = 0;
        while (start < n) {
            // How many of the rest the widest shelves hold; that decides the pass, a narrower width only reshapes it.
            int end = start + fits(order, start, n, widths, heights, maxSide, maxSide);
            int widest = 0;
            for (int k = start; k < end; k++) widest = Math.max(widest, widths[order[k]]);
            // The least area, and of equal areas the squarest.
            int bestWidth = maxSide, bestSide = Integer.MAX_VALUE;
            long bestArea = Long.MAX_VALUE;
            for (int shelf = ceilPow2(widest); shelf <= maxSide; shelf <<= 1) {
                if (fits(order, start, end, widths, heights, shelf, maxSide) < end - start) continue;
                int[] used = shelve(order, start, end, widths, heights, shelf, null);
                long area = (long) ceilPow2(used[0]) * ceilPow2(used[1]);
                int side = Math.max(ceilPow2(used[0]), ceilPow2(used[1]));
                if (area < bestArea || area == bestArea && side < bestSide) {
                    bestArea = area;
                    bestSide = side;
                    bestWidth = shelf;
                }
            }
            int pass = passes.size();
            Place[] here = new Place[n];
            int[] used = shelve(order, start, end, widths, heights, bestWidth, here);
            for (int k = start; k < end; k++) {
                Place p = here[order[k]];
                places[order[k]] = new Place(pass, p.x(), p.y());
            }
            passes.add(new Pass(used[0], used[1], ceilPow2(used[0]), ceilPow2(used[1])));
            start = end;
        }
        return new Layout(List.copyOf(passes), List.of(places));
    }

    /** How many of {@code order[from..to)} shelves {@code shelf} wide hold before they pass {@code maxHeight}. */
    private static int fits(Integer[] order, int from, int to, int[] widths, int[] heights, int shelf, int maxHeight) {
        int x = 0, y = 0, rowHeight = 0;
        for (int k = from; k < to; k++) {
            int i = order[k];
            if (x > 0 && x + widths[i] > shelf) {
                y += rowHeight;
                x = 0;
                rowHeight = 0;
            }
            if (widths[i] > shelf || y + heights[i] > maxHeight) return k - from;
            if (x == 0) rowHeight = heights[i];
            x += widths[i];
        }
        return to - from;
    }

    /**
     * Shelves {@code order[from..to)} {@code shelf} wide, writing each place into {@code places} by index if it is not
     * null, and returns the used width and height.
     */
    private static int[] shelve(Integer[] order, int from, int to, int[] widths, int[] heights, int shelf,
                                Place[] places) {
        int x = 0, y = 0, rowHeight = 0, usedWidth = 0;
        for (int k = from; k < to; k++) {
            int i = order[k];
            if (x > 0 && x + widths[i] > shelf) {
                y += rowHeight;
                x = 0;
                rowHeight = 0;
            }
            if (x == 0) rowHeight = heights[i];
            if (places != null) places[i] = new Place(0, x, y);
            x += widths[i];
            usedWidth = Math.max(usedWidth, x);
        }
        return new int[]{usedWidth, y + rowHeight};
    }

    /** The smallest power of two at least {@code n}, for {@code n >= 1}. */
    static int ceilPow2(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }
}
