package com.iluha168.monifactory.imgencoder;

import org.junit.jupiter.api.Test;

import java.util.function.IntToLongFunction;

import static org.junit.jupiter.api.Assertions.*;

class FramePolicyTest {
    private static FramePolicy run(IntToLongFunction hashOf) {
        FramePolicy policy = new FramePolicy();
        for (int k = 0; !policy.offer(hashOf.applyAsLong(k)); k++) {
            assertTrue(k < FramePolicy.CAP, "never decided");
        }
        return policy;
    }

    private static long[] sequence(IntToLongFunction hashOf, int n) {
        long[] h = new long[n];
        for (int k = 0; k < n; k++) h[k] = hashOf.applyAsLong(k);
        return h;
    }

    /** The ldlib arrow: 40 frames, each step held for 4, so frame 0 recurs early on a plateau. */
    private static final IntToLongFunction ARROW = k -> (k % 40) / 4;

    @Test
    void arrowClosesAtItsTruePeriod() {
        FramePolicy policy = run(ARROW);
        assertEquals(40, policy.period());
        assertEquals(40, policy.stored());
        assertEquals(FramePolicy.MIN_FRAMES + 4, policy.frames(), "two periods past the first change");
    }

    @Test
    void aPlateauIsNotAStill() {
        // Unchanging for 150 frames, then a 30-frame loop: n >= 2p holds with p = 1 long before anything moves.
        IntToLongFunction late = k -> k < 150 ? 0 : 1 + (k - 150) % 30;
        FramePolicy policy = run(late);
        assertEquals(FramePolicy.strictPeriod(sequence(late, FramePolicy.CAP), FramePolicy.CAP), policy.period());
        assertEquals(-1, policy.period());
        assertEquals(FramePolicy.TRIM, policy.stored());
    }

    @Test
    void neverChangingIsAOneFrameLoop() {
        FramePolicy policy = run(k -> 7);
        assertEquals(FramePolicy.CAP, policy.frames());
        assertEquals(1, policy.period());
        assertEquals(1, policy.stored());
    }

    @Test
    void twoMechanismsCloseAtTheirCommonPeriod() {
        // Arrow at 40 and a blinker at 14: the loop is 280 frames, beyond what 400 frames can confirm.
        IntToLongFunction both = k -> ARROW.applyAsLong(k) * 100 + (k % 14 < 7 ? 1 : 0);
        FramePolicy policy = run(both);
        assertEquals(FramePolicy.CAP, policy.frames());
        assertEquals(-1, policy.period());
        assertEquals(FramePolicy.TRIM, policy.stored());

        IntToLongFunction slow = k -> ARROW.applyAsLong(k) * 100 + (k % 25 < 12 ? 1 : 0); // 200 frames
        FramePolicy closing = run(slow);
        assertEquals(200, closing.period());
    }

    @Test
    void neverAcceptsAPeriodTheFullRuleRejects() {
        var random = new java.util.SplittableRandom(1);
        for (int trial = 0; trial < 2000; trial++) {
            int period = 1 + random.nextInt(60), hold = 1 + random.nextInt(6), start = random.nextInt(100);
            long glitch = random.nextInt(8) == 0 ? 1 + random.nextInt(399) : -1;
            IntToLongFunction h = k -> k == glitch ? -5 : ((k + start) % period) / hold;
            FramePolicy policy = run(h);
            long[] full = sequence(h, FramePolicy.CAP);
            int reference = FramePolicy.strictPeriod(full, FramePolicy.CAP);
            if (policy.period() != reference) {
                // An early stop can only be wrong when something happens after it; the glitch is that something.
                assertTrue(glitch >= policy.frames(), "trial " + trial + ": " + policy.period() + " vs " + reference);
            }
        }
    }

    @Test
    void refusesFramesAfterTheDecision() {
        FramePolicy policy = run(ARROW);
        assertThrows(IllegalStateException.class, () -> policy.offer(0));
        assertThrows(IllegalStateException.class, () -> new FramePolicy().period());
    }
}
