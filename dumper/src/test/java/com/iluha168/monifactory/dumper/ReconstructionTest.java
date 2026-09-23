package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.imgencoder.Frame;
import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionTest {
    /** 0xAARRGGBB as the GL reads it back, 0xAABBGGRR. */
    private static int abgr(int argb) {
        return argb & 0xFF00FF00 | (argb & 0xFF) << 16 | (argb >>> 16) & 0xFF;
    }

    /** {@code frame} in the real render's readback convention: bottom row first, 0xAABBGGRR. */
    private static int[] real(Frame frame) {
        int w = frame.width(), h = frame.height();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) out[(h - 1 - y) * w + x] = abgr(frame.argb()[y * w + x]);
        return out;
    }

    private static Frame frame(int width, int height, int... argb) {
        return new Frame(width, height, argb);
    }

    @Test
    void theSamePictureIsExact() {
        Frame f = frame(2, 2, 0xFF102030, 0x80405060, 0xFFFFFFFF, 0x01000000);
        assertEquals(new Reconstruction.Difference(0, 0), Reconstruction.compare(real(f), f));
    }

    @Test
    void oneLevelIsWithinTolerance() {
        Frame f = frame(2, 1, 0xFF102030, 0xFF405060);
        Frame off = frame(2, 1, 0xFF112030, 0xFE40505F);
        Reconstruction.Difference d = Reconstruction.compare(real(f), off);
        assertEquals(1, d.worst());
        assertEquals(0, d.pixels());
        assertTrue(d.faithful());
    }

    @Test
    void twoLevelsInAnyChannelFail() {
        Frame f = frame(4, 1, 0xFF102030, 0xFF102030, 0xFF102030, 0xFF102030);
        int[] real = real(f);
        Frame off = frame(4, 1, 0xFF122030, 0xFF102230, 0xFF102032, 0xFD102030);
        Reconstruction.Difference d = Reconstruction.compare(real, off);
        assertEquals(2, d.worst());
        assertEquals(4, d.pixels());
        assertFalse(d.faithful());
    }

    @Test
    void whereTheRealRenderIsTransparentNothingCounts() {
        // Colour under alpha 0 is meaningless: the target keeps what the draw left, the composite sets 0.
        int[] real = {abgr(0x00FF00FF), abgr(0xFF000000)};
        Frame composite = frame(2, 1, 0x80123456, 0xFF000000);
        assertEquals(new Reconstruction.Difference(0, 0), Reconstruction.compare(real, composite));
        // But a composite transparent where the real render is not is a difference.
        Reconstruction.Difference d = Reconstruction.compare(real, frame(2, 1, 0, 0));
        assertEquals(255, d.worst());
        assertEquals(1, d.pixels());
    }

    @Test
    void theRealRenderIsReadBottomRowFirst() {
        // Top row red, bottom row blue, upright; the readback holds the bottom row first.
        Frame upright = frame(1, 2, 0xFFFF0000, 0xFF0000FF);
        int[] real = {0xFFFF0000, 0xFF0000FF};
        assertEquals(new Reconstruction.Difference(0, 0), Reconstruction.compare(real, upright));
        assertEquals(2, Reconstruction.compare(real, frame(1, 2, 0xFF0000FF, 0xFFFF0000)).pixels());
    }

    @Test
    void aLongerReadbackIsReadOnlyAsFarAsTheComposite() {
        Frame f = frame(1, 1, 0xFF010203);
        assertEquals(0, Reconstruction.compare(new int[]{abgr(0xFF010203), 0xFFFFFFFF}, f).pixels());
        assertThrows(IllegalArgumentException.class, () -> Reconstruction.compare(new int[0], f));
    }

    @Test
    void aReadbackTurnsIntoAnUprightFrame() {
        // 2x2 at offset 3 with rows 5 apart, bottom row first.
        int[] gl = new int[3 + 2 * 5];
        java.util.Arrays.fill(gl, 0x12345678);
        gl[3] = abgr(0xFF0000FF);
        gl[4] = abgr(0xFF00FF00);
        gl[8] = abgr(0xFFFF0000);
        gl[9] = abgr(0x80010203);
        Frame frame = Reconstruction.frame(IntBuffer.wrap(gl), 3, 5, 2, 2);
        assertArrayEquals(new int[]{0xFFFF0000, 0x80010203, 0xFF0000FF, 0xFF00FF00}, frame.argb());
    }
}
