package com.iluha168.monifactory.dumper;

import org.junit.jupiter.api.Test;

import static com.iluha168.monifactory.dumper.BlendState.*;
import static org.junit.jupiter.api.Assertions.*;

class BlendStateTest {
    private static final int MAX = 0x8008, REVERSE_SUBTRACT = 0x800B;

    private static BlendState on(int src, int dst) {
        return BlendState.of(true, src, dst, FUNC_ADD, ALL_CHANNELS, false);
    }

    private static final BlendState OFF = BlendState.of(false, SRC_ALPHA, ONE_MINUS_SRC_ALPHA, FUNC_ADD,
            ALL_CHANNELS, false);

    @Test
    void overAndBlendingOffAreOneDraw() {
        assertTrue(on(SRC_ALPHA, ONE_MINUS_SRC_ALPHA).oneDraw(), "standard over");
        assertTrue(on(ONE, ONE_MINUS_SRC_ALPHA).oneDraw(), "premultiplied over");
        assertTrue(OFF.oneDraw());
    }

    @Test
    void additiveOnTopOfWhatIsThereIsOneDraw() {
        // The glint, and plain additive: colour is added, and what lies below shows through as much as before.
        assertTrue(on(SRC_COLOR, ONE).oneDraw());
        assertTrue(on(ONE, ONE).oneDraw());
        assertTrue(on(SRC_ALPHA, ONE).oneDraw());
    }

    @Test
    void perChannelOrDestinationFactorsAreNot() {
        assertFalse(on(ONE, ONE_MINUS_SRC_COLOR).oneDraw(), "each channel lets through its own amount");
        assertFalse(on(ZERO, SRC_COLOR).oneDraw(), "a multiply");
        assertFalse(on(DST_COLOR, ZERO).oneDraw(), "a multiply by the destination");
        assertFalse(on(ONE_MINUS_DST_COLOR, ONE_MINUS_SRC_COLOR).oneDraw(), "an inversion");
        assertFalse(on(DST_ALPHA, ONE_MINUS_SRC_ALPHA).oneDraw(), "reads the destination's alpha");
        assertFalse(on(SRC_ALPHA, ONE_MINUS_DST_ALPHA).oneDraw());
        assertFalse(on(SRC_ALPHA_SATURATE, ONE).oneDraw());
    }

    @Test
    void equationsMasksAndLogicOpsAreNot() {
        assertFalse(BlendState.of(true, ONE, ONE, MAX, ALL_CHANNELS, false).oneDraw());
        assertFalse(BlendState.of(true, ONE, ONE, REVERSE_SUBTRACT, ALL_CHANNELS, false).oneDraw());
        assertFalse(BlendState.of(true, SRC_ALPHA, ONE_MINUS_SRC_ALPHA, FUNC_ADD, 0xE, false).oneDraw());
        assertFalse(BlendState.of(false, ONE, ZERO, FUNC_ADD, 0x1, false).oneDraw());
        assertFalse(BlendState.of(false, ONE, ZERO, FUNC_ADD, ALL_CHANNELS, true).oneDraw());
    }

    @Test
    void blendingOffIgnoresTheFactorsAndTheEquation() {
        // The capture draws it with blending on and the equation add, so what was left set does not matter.
        BlendState off = BlendState.of(false, DST_COLOR, SRC_COLOR, MAX, ALL_CHANNELS, false);
        assertEquals(OFF, off);
        assertTrue(off.oneDraw());
    }

    @Test
    void theCaptureReplacesWhereBlendingIsOffAndKeepsTheColourFactorsWhereOn() {
        assertEquals(ONE, OFF.captureSrc());
        assertEquals(ZERO, OFF.captureDst());
        BlendState glint = on(SRC_COLOR, ONE);
        assertEquals(SRC_COLOR, glint.captureSrc());
        assertEquals(ONE, glint.captureDst());
    }

    @Test
    void namesForTheLog() {
        assertEquals("SRC_ALPHA,ONE_MINUS_SRC_ALPHA", on(SRC_ALPHA, ONE_MINUS_SRC_ALPHA).toString());
        assertEquals("off", OFF.toString());
        assertEquals("ONE,ONE equation MAX", BlendState.of(true, ONE, ONE, MAX, ALL_CHANNELS, false).toString());
        assertEquals("off mask ---A logic op", BlendState.of(false, ONE, ZERO, FUNC_ADD, 1, true).toString());
        assertEquals("0x1234,ONE", on(0x1234, ONE).toString());
    }
}
