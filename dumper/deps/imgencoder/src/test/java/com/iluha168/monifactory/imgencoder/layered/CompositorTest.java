package com.iluha168.monifactory.imgencoder.layered;

import com.iluha168.monifactory.imgencoder.Frame;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompositorTest {
    private static Frame flat(int width, int height, int argb) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, argb);
        return new Frame(width, height, pixels);
    }

    private static int pixel(Frame frame, int x, int y) {
        return frame.argb()[y * frame.width() + x];
    }

    @Test
    void opaqueOverOpaqueReplaces() {
        Frame out = Compositor.composite(4, 3, List.of(
                new Compositor.Placed(0, 0, flat(4, 3, 0xFF102030)),
                new Compositor.Placed(1, 1, flat(2, 2, 0xFFA0B0C0))));
        for (int y = 0; y < 3; y++)
            for (int x = 0; x < 4; x++) {
                boolean covered = x >= 1 && x <= 2 && y >= 1;
                assertEquals(covered ? 0xFFA0B0C0 : 0xFF102030, pixel(out, x, y), x + "," + y);
            }
    }

    @Test
    void halfAlphaBlendsAndRoundsToNearest() {
        // as = 128: c = (cs*128 + cd*127 + 127) / 255.
        // red 200 over 100: (25600 + 12700) / 255 = 150.196, so 150.
        // green 1 over 0: 128 / 255 = 0.502, so 1.
        // blue 0 over 255: 32385 / 255 = 127 exactly.
        Frame out = Compositor.composite(1, 1, List.of(
                new Compositor.Placed(0, 0, flat(1, 1, 0xFF6400FF)),
                new Compositor.Placed(0, 0, flat(1, 1, 0x80C80100))));
        assertEquals(0xFF96017F, pixel(out, 0, 0));
    }

    @Test
    void alphaIsLayerZeros() {
        int[] card = {0xFF000000, 0x80204060, 0x00FFFFFF, 0x80204060};
        Frame out = Compositor.composite(3, 2, List.of(
                new Compositor.Placed(0, 0, new Frame(2, 2, card)),
                new Compositor.Placed(0, 0, flat(3, 1, 0xFF0000FF))));
        // Opaque layer 1 over an opaque, a translucent and a transparent card pixel, and outside the card's box.
        assertEquals(0xFF0000FF, pixel(out, 0, 0));
        assertEquals(0x800000FF, pixel(out, 1, 0), "colour from layer 1, alpha from layer 0");
        assertEquals(0, pixel(out, 2, 0), "outside layer 0's box: transparent, and cleared");
        // Nothing over the second row: layer 0 comes through as it is, a translucent pixel not darkened.
        assertEquals(0, pixel(out, 0, 1), "alpha 0: colour cleared");
        assertEquals(0x80204060, pixel(out, 1, 1));
        assertEquals(0, pixel(out, 2, 1));
    }

    @Test
    void transparentPixelsLeaveTheCanvasAlone() {
        Frame out = Compositor.composite(2, 1, List.of(
                new Compositor.Placed(0, 0, flat(2, 1, 0xFF123456)),
                new Compositor.Placed(0, 0, new Frame(2, 1, new int[]{0x00FFFFFF, 0xFFFFFFFF}))));
        assertArrayEquals(new int[]{0xFF123456, 0xFFFFFFFF}, out.argb());
    }

    @Test
    void refusesPicturesOffTheCanvas() {
        assertThrows(IllegalArgumentException.class, () -> Compositor.composite(4, 4, List.of(
                new Compositor.Placed(3, 0, flat(2, 2, 0xFF000000)))));
        assertThrows(IllegalArgumentException.class, () -> Compositor.composite(4, 4, List.of()));
    }

    @Test
    void imageDrawsEachLayerAtItsTick() {
        // A 3x1 card and a 1x1 layer at x=1 that shows still 1 for two ticks, then still 2 for one.
        Map<Integer, Frame> stills = Map.of(
                0, flat(3, 1, 0xFF000000),
                1, flat(1, 1, 0xFFFF0000),
                2, flat(1, 1, 0xFF00FF00));
        LayeredImage image = new LayeredImage(3, 1, List.of(
                new Layer(0, 0, 3, 1, Timeline.of(0)),
                new Layer(1, 0, 1, 1, Timeline.of(1, 1, 2))));
        assertTrue(image.animated());
        int[] middle = {0xFFFF0000, 0xFFFF0000, 0xFF00FF00};
        for (int t = 0; t < 7; t++)
            assertArrayEquals(new int[]{0xFF000000, middle[t % 3], 0xFF000000}, image.frameAt(t, stills::get).argb(),
                    "tick " + t);

        assertThrows(IllegalStateException.class, () -> image.frameAt(0, id -> flat(2, 2, 0)),
                "a still that is not its layer's size");
    }
}
