package com.iluha168.monifactory.dumper;

import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;
import java.util.Arrays;

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
    }
}
