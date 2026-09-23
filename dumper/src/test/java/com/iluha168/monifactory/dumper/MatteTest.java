package com.iluha168.monifactory.dumper;

import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MatteTest {
    /** Opaque RGB as the GL reads it back: RGBA bytes as a little-endian int. */
    private static int gl(int r, int g, int b) {
        return 0xFF000000 | b << 16 | g << 8 | r;
    }

    private static final int BLACK = gl(0, 0, 0), WHITE = gl(255, 255, 255);
    /** What lies around the draws in the buffer. Were it read, it would show up as a ring hit or a pixel. */
    private static final int GARBAGE = 0x5A3C1E77;

    /**
     * A draw pair as {@link TileRenderer} leaves it: {@code width} by {@code height} ring included, black and white
     * side by side at an offset into a wider buffer, bottom row first. Set pixels with top-down coordinates.
     */
    private static final class Pair {
        final int width, height, stride, black, white;
        final int[] pixels;

        Pair(int width, int height) {
            this.width = width;
            this.height = height;
            this.stride = 2 * width + 3;
            this.black = 5;
            this.white = black + width + 1;
            this.pixels = new int[black + height * stride + 7];
            Arrays.fill(pixels, GARBAGE);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) set(x, y, BLACK, WHITE);
        }

        Pair set(int x, int y, int overBlack, int overWhite) {
            int row = height - 1 - y;
            pixels[black + row * stride + x] = overBlack;
            pixels[white + row * stride + x] = overWhite;
            return this;
        }

        Matte.Result solve() {
            return Matte.solve(IntBuffer.wrap(pixels), black, white, stride, width, height);
        }
    }

    private static int only(Matte.Result result) {
        assertEquals(1, result.still().width());
        assertEquals(1, result.still().height());
        return result.still().argb()[0];
    }

    @Test
    void opaqueComesOutAsDrawn() {
        Matte.Result result = new Pair(3, 3).set(1, 1, gl(200, 100, 50), gl(200, 100, 50)).solve();
        assertEquals(0xFFC86432, only(result));
        assertEquals(0, result.alphaSpread());
        assertTrue(result.over());
        assertFalse(result.escaped());
    }

    @Test
    void halfAlphaSolvesToTheLevelNearestTheColour() {
        // (200, 100, 50) at alpha 128, as an 8-bit target blends it:
        // over black c*128/255: 100.39, 50.20, 25.10 -> 100, 50, 25;
        // over white that plus 127: 227, 177, 152.
        // a = 255 - (w - b) = 128 on every channel; c = round(b * 255 / 128): 199.2, 99.6, 49.8 -> 199, 100, 50.
        Matte.Result result = new Pair(3, 3).set(1, 1, gl(100, 50, 25), gl(227, 177, 152)).solve();
        assertEquals(0x80C76432, only(result));
        assertEquals(0, result.alphaSpread());
    }

    @Test
    void untouchedIsTransparentBlack() {
        Matte.Result result = new Pair(3, 3).solve();
        assertEquals(0, only(result));
        assertFalse(result.escaped());
    }

    @Test
    void channelsThatDisagreeOnAlphaAreNotOver() {
        // a per channel: 255 - (255 - 10) = 10, 255 - (200 - 20) = 75, 255 - (100 - 30) = 185. Spread 175.
        // a = (10 + 75 + 185 + 1) / 3 = 90; c = round(b * 255 / 90): 28.33, 56.67, 85 -> 28, 57, 85.
        Matte.Result result = new Pair(3, 3).set(1, 1, gl(10, 20, 30), gl(255, 200, 100)).solve();
        assertEquals(175, result.alphaSpread());
        assertFalse(result.over());
        assertEquals(90 << 24 | 28 << 16 | 57 << 8 | 85, only(result));
    }

    @Test
    void overTakesASpreadOfTwoAndNotThree() {
        // Alphas 127, 128, 129.
        assertTrue(new Pair(3, 3).set(1, 1, BLACK, gl(128, 127, 126)).solve().over());
        // Alphas 127, 128, 130.
        Matte.Result three = new Pair(3, 3).set(1, 1, BLACK, gl(128, 127, 125)).solve();
        assertEquals(3, three.alphaSpread());
        assertFalse(three.over());
    }

    @Test
    void alphaAndColourClamp() {
        // Lighter over black than over white: a = 255 - (100 - 200) = 355, clamped to 255.
        assertEquals(0xFFC8C8C8, only(new Pair(3, 3).set(1, 1, gl(200, 200, 200), gl(100, 100, 100)).solve()));
        // Alphas 255, 0, 0 average 85, and red 250 * 255 / 85 = 750 clamps to 255.
        Matte.Result result = new Pair(3, 3).set(1, 1, gl(250, 0, 0), gl(250, 255, 255)).solve();
        assertEquals(0x55FF0000, only(result));
    }

    @Test
    void rowsComeOutTopFirstAsArgb() {
        // A 2x2 inside: top-left red, top-right green, bottom-left blue, bottom-right grey.
        Pair pair = new Pair(4, 4)
                .set(1, 1, gl(255, 0, 0), gl(255, 0, 0))
                .set(2, 1, gl(0, 255, 0), gl(0, 255, 0))
                .set(1, 2, gl(0, 0, 255), gl(0, 0, 255))
                .set(2, 2, gl(1, 2, 3), gl(1, 2, 3));
        Matte.Result result = pair.solve();
        assertArrayEquals(new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF010203}, result.still().argb());
    }

    @Test
    void theRingIsCroppedOff() {
        // 6x5 with the ring is a 4x3 picture, whatever is on the ring.
        Pair pair = new Pair(6, 5);
        for (int y = 0; y < 5; y++)
            for (int x = 0; x < 6; x++) {
                boolean ring = x == 0 || y == 0 || x == 5 || y == 4;
                int colour = ring ? gl(9, 9, 9) : gl(x, y, 0);
                pair.set(x, y, colour, colour);
            }
        Matte.Result result = pair.solve();
        assertEquals(4, result.still().width());
        assertEquals(3, result.still().height());
        for (int y = 0; y < 3; y++)
            for (int x = 0; x < 4; x++)
                assertEquals(0xFF000000 | (x + 1) << 16 | (y + 1) << 8, result.still().argb()[y * 4 + x], x + "," + y);
        assertTrue(result.escaped());
    }

    @Test
    void anythingOnTheRingIsAnEscape() {
        int[][] ring = {{0, 0}, {2, 0}, {4, 0}, {0, 2}, {4, 2}, {0, 3}, {4, 3}, {1, 3}};
        for (int[] at : ring) {
            // Seen over black only, and over white only: a faint layer can leave one of them untouched.
            assertTrue(new Pair(5, 4).set(at[0], at[1], gl(0, 1, 0), WHITE).solve().escaped(), Arrays.toString(at));
            assertTrue(new Pair(5, 4).set(at[0], at[1], BLACK, gl(255, 255, 254)).solve().escaped(),
                    Arrays.toString(at));
        }
        // The clear colours' alpha is not looked at.
        assertFalse(new Pair(5, 4).set(0, 0, 0, 0x00FFFFFF).solve().escaped());
        // The inside may hold anything.
        assertFalse(new Pair(5, 4).set(2, 2, gl(7, 7, 7), gl(7, 7, 7)).solve().escaped());
    }

    @Test
    void aDrawWithNoInsideIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Pair(2, 3).solve());
        assertThrows(IllegalArgumentException.class, () -> new Pair(3, 2).solve());
        assertThrows(IllegalArgumentException.class, () -> new Single(2, 3).solve());
        assertThrows(IllegalArgumentException.class, () -> new Single(3, 2).solve());
    }

    // One draw over a transparent clear.

    /** Premultiplied RGB and transmittance as the GL reads them back. */
    private static int once(int r, int g, int b, int t) {
        return t << 24 | b << 16 | g << 8 | r;
    }

    private static final int CLEAR = once(0, 0, 0, 255);

    /** One draw as {@link TileRenderer} leaves it, at an offset into a wider buffer, bottom row first. */
    private static final class Single {
        final int width, height, stride, at;
        final int[] pixels;

        Single(int width, int height) {
            this.width = width;
            this.height = height;
            this.stride = width + 4;
            this.at = 3;
            this.pixels = new int[at + height * stride + 5];
            Arrays.fill(pixels, GARBAGE);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) set(x, y, CLEAR);
        }

        Single set(int x, int y, int pixel) {
            pixels[at + (height - 1 - y) * stride + x] = pixel;
            return this;
        }

        Matte.Result solve() {
            return Matte.unpremultiply(IntBuffer.wrap(pixels), at, stride, width, height);
        }
    }

    @Test
    void oneOpaqueDrawComesOutAsDrawn() {
        Matte.Result result = new Single(3, 3).set(1, 1, once(200, 100, 50, 0)).solve();
        assertEquals(0xFFC86432, only(result));
        assertEquals(0, result.alphaSpread());
        assertFalse(result.escaped());
    }

    @Test
    void oneDrawAtHalfAlphaSolvesAsTheTwoDrawsDo() {
        // (200, 100, 50) at alpha 128: premultiplied 100, 50, 25, transmittance 127. The same picture as
        // halfAlphaSolvesToTheLevelNearestTheColour.
        Matte.Result result = new Single(3, 3).set(1, 1, once(100, 50, 25, 127)).solve();
        assertEquals(0x80C76432, only(result));
        assertEquals(0, result.alphaSpread());
    }

    @Test
    void oneDrawUntouchedIsTransparentBlack() {
        Matte.Result result = new Single(3, 3).solve();
        assertEquals(0, only(result));
        assertFalse(result.escaped());
    }

    @Test
    void aChannelAboveItsAlphaIsNotOver() {
        // An additive draw on a translucent pixel: alpha 100, red 200. Over by 100; red clamps.
        Matte.Result result = new Single(3, 3).set(1, 1, once(200, 10, 20, 155)).solve();
        assertEquals(100, result.alphaSpread());
        assertFalse(result.over());
        assertEquals(100 << 24 | 0xFF << 16 | 26 << 8 | 51, only(result));
        // Colour where nothing covers is over by that much too.
        assertEquals(7, new Single(3, 3).set(1, 1, once(0, 7, 0, 255)).solve().alphaSpread());
        // Two levels of rounding pass, three don't.
        assertTrue(new Single(3, 3).set(1, 1, once(102, 100, 0, 155)).solve().over());
        assertFalse(new Single(3, 3).set(1, 1, once(103, 100, 0, 155)).solve().over());
    }

    @Test
    void oneDrawRowsComeOutTopFirstAndTheRingIsCroppedOff() {
        Single single = new Single(5, 4);
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 5; x++) {
                boolean ring = x == 0 || y == 0 || x == 4 || y == 3;
                single.set(x, y, ring ? CLEAR : once(x, y, 0, 0));
            }
        Matte.Result result = single.solve();
        assertEquals(3, result.still().width());
        assertEquals(2, result.still().height());
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 3; x++)
                assertEquals(0xFF000000 | (x + 1) << 16 | (y + 1) << 8, result.still().argb()[y * 3 + x], x + "," + y);
        assertFalse(result.escaped());
    }

    @Test
    void anythingOnTheRingOfOneDrawIsAnEscape() {
        int[][] ring = {{0, 0}, {2, 0}, {4, 0}, {0, 2}, {4, 2}, {0, 3}, {4, 3}, {1, 3}};
        for (int[] at : ring) {
            // Colour alone, and coverage alone: a draw that only darkens what is below leaves colour 0.
            assertTrue(new Single(5, 4).set(at[0], at[1], once(0, 1, 0, 255)).solve().escaped(), Arrays.toString(at));
            assertTrue(new Single(5, 4).set(at[0], at[1], once(0, 0, 0, 254)).solve().escaped(), Arrays.toString(at));
        }
        assertFalse(new Single(5, 4).set(2, 2, once(7, 7, 7, 0)).solve().escaped());
    }

    /**
     * Layers of up to five draws on one pixel, each standard "over", blending off (which replaces the pixel whatever
     * the fragment's alpha), or the additive glint on an opaque pixel, drawn the way an 8-bit target blends them: over
     * black, over white and over grey for real, and once in the capture's terms (see {@link BlendState}). Composited
     * back over black, white and grey, one draw's picture is as good as two draws'.
     */
    @Test
    void oneDrawReproducesTheLayerOverAnyBackgroundAsTheTwoDrawsDo() {
        Random random = new Random(42);
        int worstOne = 0, worstTwo = 0;
        for (int trial = 0; trial < 100_000; trial++) {
            int[] black = {0, 0, 0}, white = {255, 255, 255}, grey = {128, 128, 128}, p = {0, 0, 0};
            int t = 255;
            boolean opaque = false;
            for (int draws = 1 + random.nextInt(5); draws > 0; draws--) {
                int kind = random.nextInt(opaque ? 3 : 2);
                int a = random.nextInt(4) == 0 ? 255 : random.nextInt(256);
                int[] c = {random.nextInt(256), random.nextInt(256), random.nextInt(256)};
                for (int ch = 0; ch < 3; ch++) {
                    switch (kind) {
                        case 0 -> {
                            black[ch] = over(c[ch], a, black[ch]);
                            white[ch] = over(c[ch], a, white[ch]);
                            grey[ch] = over(c[ch], a, grey[ch]);
                            p[ch] = over(c[ch], a, p[ch]);
                        }
                        case 1 -> black[ch] = white[ch] = grey[ch] = p[ch] = c[ch];
                        default -> {
                            int glint = Math.round(c[ch] * c[ch] / 255f) / 4;
                            black[ch] = Math.min(255, black[ch] + glint);
                            white[ch] = Math.min(255, white[ch] + glint);
                            grey[ch] = Math.min(255, grey[ch] + glint);
                            p[ch] = Math.min(255, p[ch] + glint);
                        }
                    }
                }
                switch (kind) {
                    case 0 -> t = Math.round(t * (1 - a / 255f));
                    case 1 -> {
                        t = 0;
                        opaque = true;
                    }
                    default -> {
                    }
                }
            }
            Matte.Result one = new Single(3, 3).set(1, 1, once(p[0], p[1], p[2], t)).solve();
            Matte.Result two = new Pair(3, 3).set(1, 1, gl(black[0], black[1], black[2]),
                    gl(white[0], white[1], white[2])).solve();
            assertTrue(one.over(), "trial " + trial);
            int[][] backgrounds = {{0, 0, 0}, {255, 255, 255}, {128, 128, 128}};
            int[][] real = {black, white, grey};
            for (int k = 0; k < 3; k++) {
                for (int ch = 0; ch < 3; ch++) {
                    worstOne = Math.max(worstOne, Math.abs(composite(only(one), ch, backgrounds[k][ch]) - real[k][ch]));
                    worstTwo = Math.max(worstTwo, Math.abs(composite(only(two), ch, backgrounds[k][ch]) - real[k][ch]));
                }
            }
        }
        assertTrue(worstOne <= Math.max(1, worstTwo), "one draw is off by " + worstOne + ", two by " + worstTwo);
    }

    /** An 8-bit target's standard "over": {@code round(c*a + d*(1-a))}. */
    private static int over(int c, int a, int d) {
        return Math.round(c * a / 255f + d * (1 - a / 255f));
    }

    /** Channel {@code ch} (0 red) of a straight-alpha {@code argb} over {@code background}, as the consumer draws it. */
    private static int composite(int argb, int ch, int background) {
        int a = argb >>> 24, c = argb >>> (16 - 8 * ch) & 0xFF;
        return Math.round(c * a / 255f + background * (1 - a / 255f));
    }
}
