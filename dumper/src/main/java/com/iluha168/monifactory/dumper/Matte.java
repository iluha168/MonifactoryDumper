package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.imgencoder.Frame;

import java.nio.IntBuffer;

/**
 * A layer's straight-alpha picture, from one draw of it over a transparent clear or from two, over opaque black and
 * over opaque white (DESIGN 3.2).
 * <p>
 * One draw ({@link #unpremultiply}) leaves premultiplied colour {@code p} and, in the alpha channel, the layer's
 * transmittance {@code t} (see {@link BlendState}), so {@code a = 255 - t} and {@code c = p * 255 / a}. A channel
 * above its alpha can't be premultiplied colour: an additive draw pushed it past what "over" can hold.
 * <p>
 * Two draws ({@link #solve}): over black a layer leaves {@code c*a}, over white {@code c*a + (1-a)}, so per channel
 * {@code a = 255 - (w - b)} and {@code c = b * 255 / a}. That holds for any blend a mod picks as long as the layer
 * blends linearly with what is underneath. The three channels each give an alpha; the picture's is their average,
 * rounded. When they disagree the layer did not simply go "over" what was below, and no single alpha describes it.
 * <p>
 * Either way, past {@link #MAX_ALPHA_SPREAD} the recipe must be drawn whole. The two agree to a level on anything one
 * draw can capture: one draw's colour is exactly the draw over black, and its transmittance what the draw over white
 * adds to that, rounded once instead of twice.
 * <p>
 * Draws come as the GL reads them back: RGBA bytes, read as a little-endian int ({@code 0xAABBGGRR}), bottom row
 * first. They sit in one buffer, usually a mapped pixel buffer, at their own offsets with a shared row stride, and are
 * read in place: the only array made is the picture's own. Each draw is the layer's crop box plus a 1-pixel guard ring.
 * The ring is cut off the picture; it is there to be empty, and anything drawn on it means the layer drew outside its
 * box.
 * <p>
 * Pure, and safe to run on any thread as long as nothing writes the buffer meanwhile.
 */
final class Matte {
    private Matte() {
    }

    /**
     * The most two channels may disagree on alpha, or one channel's premultiplied level may exceed it, for the layer to
     * count as drawn "over" what is below. Rounding alone moves either by a level or two.
     */
    static final int MAX_ALPHA_SPREAD = 2;

    /**
     * The solved picture.
     *
     * @param still       the box, without the ring: straight alpha {@code 0xAARRGGBB}, top row first, and colour 0
     *                    wherever alpha is 0
     * @param escaped     whether anything drew on the guard ring, so outside the box
     * @param alphaSpread inside the box, the most the channels disagreed on one pixel's alpha (two draws), or the most
     *                    a channel's premultiplied level exceeded the pixel's alpha (one draw)
     */
    record Result(Frame still, boolean escaped, int alphaSpread) {
        /** Whether the layer is a plain "over": its channels agree on alpha within {@link #MAX_ALPHA_SPREAD}. */
        boolean over() {
            return alphaSpread <= MAX_ALPHA_SPREAD;
        }
    }

    /**
     * Solves the draw pair at int offsets {@code black} and {@code white} of {@code gl}, each {@code width} by
     * {@code height} pixels ring included, rows {@code stride} ints apart, bottom row first. The picture is
     * {@code width - 2} by {@code height - 2}. Reads with absolute gets only, so several threads can share one buffer.
     */
    static Result solve(IntBuffer gl, int black, int white, int stride, int width, int height) {
        if (width < 3 || height < 3)
            throw new IllegalArgumentException("a " + width + "x" + height + " draw has no inside past its ring");
        int w = width - 2, h = height - 2;
        int[] argb = new int[w * h];
        boolean escaped = false;
        int spread = 0;
        for (int row = 0; row < height; row++) {
            int b0 = black + row * stride, w0 = white + row * stride;
            if (row == 0 || row == height - 1) {
                for (int x = 0; x < width && !escaped; x++) escaped = drew(gl.get(b0 + x), gl.get(w0 + x));
                continue;
            }
            if (!escaped) {
                escaped = drew(gl.get(b0), gl.get(w0))
                        || drew(gl.get(b0 + width - 1), gl.get(w0 + width - 1));
            }
            // GL row `row` from the bottom is picture row h - row from the top, once the ring's row is gone.
            int to = (h - row) * w - 1;
            for (int x = 1; x <= w; x++) {
                int b = gl.get(b0 + x), wh = gl.get(w0 + x);
                int br = b & 0xFF, bg = b >>> 8 & 0xFF, bb = b >>> 16 & 0xFF;
                int ar = alpha(br, wh & 0xFF), ag = alpha(bg, wh >>> 8 & 0xFF), ab = alpha(bb, wh >>> 16 & 0xFF);
                int d = Math.max(ar, Math.max(ag, ab)) - Math.min(ar, Math.min(ag, ab));
                if (d > spread) spread = d;
                int a = (ar + ag + ab + 1) / 3;
                argb[to + x] = a == 0 ? 0 : a << 24 | colour(br, a) << 16 | colour(bg, a) << 8 | colour(bb, a);
            }
        }
        return new Result(new Frame(w, h, argb), escaped, spread);
    }

    /**
     * Unpremultiplies the draw at int offset {@code at} of {@code gl}, {@code width} by {@code height} pixels ring
     * included, rows {@code stride} ints apart, bottom row first: colour premultiplied, alpha the transmittance. The
     * clear it was drawn on is black with transmittance 255. The picture is {@code width - 2} by {@code height - 2}.
     * Reads with absolute gets only, so several threads can share one buffer.
     */
    static Result unpremultiply(IntBuffer gl, int at, int stride, int width, int height) {
        if (width < 3 || height < 3)
            throw new IllegalArgumentException("a " + width + "x" + height + " draw has no inside past its ring");
        int w = width - 2, h = height - 2;
        int[] argb = new int[w * h];
        boolean escaped = false;
        int spread = 0;
        for (int row = 0; row < height; row++) {
            int r0 = at + row * stride;
            if (row == 0 || row == height - 1) {
                for (int x = 0; x < width && !escaped; x++) escaped = gl.get(r0 + x) != CLEAR;
                continue;
            }
            if (!escaped) escaped = gl.get(r0) != CLEAR || gl.get(r0 + width - 1) != CLEAR;
            int to = (h - row) * w - 1;
            for (int x = 1; x <= w; x++) {
                int p = gl.get(r0 + x);
                int a = 255 - (p >>> 24);
                int r = p & 0xFF, g = p >>> 8 & 0xFF, b = p >>> 16 & 0xFF;
                int over = Math.max(r, Math.max(g, b)) - a;
                if (over > spread) spread = over;
                argb[to + x] = a == 0 ? 0 : a << 24 | colour(r, a) << 16 | colour(g, a) << 8 | colour(b, a);
            }
        }
        return new Result(new Frame(w, h, argb), escaped, spread);
    }

    /** The clear {@link #unpremultiply} expects, as read back: black, transmittance 255. */
    private static final int CLEAR = 0xFF000000;

    /** One channel's alpha from its level over black and over white. */
    static int alpha(int black, int white) {
        int a = 255 - (white - black);
        return a < 0 ? 0 : Math.min(a, 255);
    }

    /** {@code round(p * 255 / a)}, clamped to a level: the straight colour behind a premultiplied one. */
    static int colour(int p, int a) {
        return Math.min(255, (p * 510 + a) / (2 * a));
    }

    /** Whether a pixel pair is anything but the clear colours: a draw touched it. Alpha is not looked at. */
    private static boolean drew(int black, int white) {
        return (black & 0xFFFFFF) != 0 || (white & 0xFFFFFF) != 0xFFFFFF;
    }
}
