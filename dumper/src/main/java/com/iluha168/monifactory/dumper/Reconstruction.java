package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.imgencoder.Frame;

import java.nio.IntBuffer;

/**
 * The check that decides whether a recipe's layers can stand for it: their composite against the
 * real render, {@code EmiRenderHelper.renderRecipe} at the same time and atlas state.
 * <p>
 * Only pixels the real render covers count. Where its alpha is 0 the colour is meaningless in both, and the composite
 * sets it to 0 while the render target keeps whatever the draw left. Everywhere else every channel, alpha included,
 * may differ by {@link #TOLERANCE}: the alpha solve rounds to 8 bits, and so does each composited layer, which puts a
 * faithful reconstruction up to one level off on some pixels. Pure.
 */
final class Reconstruction {
    private Reconstruction() {
    }

    /** How far one channel of a faithful reconstruction may be from the real render. */
    static final int TOLERANCE = 1;

    /**
     * How a composite compares with the real render.
     *
     * @param worst  the largest difference of one channel of one pixel, over the pixels the real render covers
     * @param pixels how many of those pixels differ by more than {@link #TOLERANCE} in some channel
     */
    record Difference(int worst, int pixels) {
        /** Whether the composite reproduces the real render: no pixel is out of tolerance. */
        boolean faithful() {
            return pixels == 0;
        }
    }

    /**
     * Compares {@code composite}, upright {@code 0xAARRGGBB}, with the real render in {@link RecipeRenderer}'s readback
     * convention: {@code 0xAABBGGRR}, bottom row first, the first {@code composite.width() * composite.height()} ints
     * of {@code real}.
     */
    static Difference compare(int[] real, Frame composite) {
        int width = composite.width(), height = composite.height();
        if (real.length < width * height)
            throw new IllegalArgumentException(real.length + " pixels for a " + width + "x" + height + " render");
        int[] argb = composite.argb();
        int worst = 0, pixels = 0;
        for (int y = 0; y < height; y++) {
            int from = (height - 1 - y) * width, to = y * width;
            for (int x = 0; x < width; x++) {
                int r = real[from + x];
                if (r >>> 24 == 0) continue;
                int c = argb[to + x];
                int d = Math.max(
                        Math.max(Math.abs((r & 0xFF) - (c >>> 16 & 0xFF)), Math.abs((r >>> 8 & 0xFF) - (c >>> 8 & 0xFF))),
                        Math.max(Math.abs((r >>> 16 & 0xFF) - (c & 0xFF)), Math.abs((r >>> 24) - (c >>> 24))));
                if (d > worst) worst = d;
                if (d > TOLERANCE) pixels++;
            }
        }
        return new Difference(worst, pixels);
    }

    /**
     * A GL readback as a {@link Frame}: {@code width} by {@code height} pixels of {@code 0xAABBGGRR} at int offset
     * {@code offset} of {@code gl}, rows {@code stride} ints apart, bottom row first, turned upright into
     * {@code 0xAARRGGBB}. Reads with absolute gets only.
     */
    static Frame frame(IntBuffer gl, int offset, int stride, int width, int height) {
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            int from = offset + (height - 1 - y) * stride, to = y * width;
            for (int x = 0; x < width; x++) {
                int p = gl.get(from + x);
                argb[to + x] = p & 0xFF00FF00 | (p & 0xFF) << 16 | (p >>> 16) & 0xFF;
            }
        }
        return new Frame(width, height, argb);
    }
}
