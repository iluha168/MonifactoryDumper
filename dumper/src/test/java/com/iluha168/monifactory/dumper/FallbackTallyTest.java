package com.iluha168.monifactory.dumper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FallbackTallyTest {
    @Test
    void nothingFellBack() {
        FallbackTally tally = new FallbackTally();
        assertEquals(0, tally.total());
        assertEquals("", tally.byReason());
        assertEquals("", tally.byCategory(20));
    }

    @Test
    void reasonsAreCountedMostFirstThenByName() {
        FallbackTally tally = new FallbackTally();
        tally.add("gtceu:assembler", "differs");
        tally.add("minecraft:crafting", "threw");
        tally.add("gtceu:mixer", "differs");
        tally.add("gtceu:mixer", "escapes");
        assertEquals(4, tally.total());
        assertEquals("\n  2\tdiffers\n  1\tescapes\n  1\tthrew", tally.byReason());
    }

    @Test
    void categoriesCarryTheirReasonsAndTheRestIsCounted() {
        FallbackTally tally = new FallbackTally();
        tally.add("b", "differs");
        tally.add("b", "threw");
        tally.add("b", "differs");
        tally.add("a", "escapes");
        tally.add("c", "escapes");
        tally.add("d", "escapes");
        assertEquals("\n  3\tb\t{differs=2, threw=1}\n  1\ta\t{escapes=1}\n  (2 more categories)", tally.byCategory(2));
        assertEquals("\n  3\tb\t{differs=2, threw=1}\n  1\ta\t{escapes=1}\n  1\tc\t{escapes=1}\n  1\td\t{escapes=1}",
                tally.byCategory(4));
    }
}
