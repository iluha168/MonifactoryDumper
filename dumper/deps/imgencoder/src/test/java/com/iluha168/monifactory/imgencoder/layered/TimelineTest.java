package com.iluha168.monifactory.imgencoder.layered;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimelineTest {
    @Test
    void mergesRunsWithoutRotating() {
        Timeline loop = Timeline.of(5, 5, 6, 7, 7, 7, 5);
        assertEquals(new Timeline(new int[]{5, 6, 7, 5}, new int[]{2, 1, 3, 1}), loop);
        assertEquals(7, loop.length(), "the loop is as long as the frames it came from");
        assertTrue(loop.animated());
    }

    @Test
    void oneStillIsTheStaticLoop() {
        assertEquals(new Timeline(new int[]{9}, new int[]{1}), Timeline.of(9, 9, 9, 9));
        assertEquals(new Timeline(new int[]{9}, new int[]{1}), Timeline.of(9));
        assertFalse(Timeline.of(9, 9).animated());
        assertEquals(1, Timeline.of(9, 9).length());
    }

    @Test
    void looksUpTicksAcrossLoopBoundaries() {
        Timeline loop = new Timeline(new int[]{1, 2, 3}, new int[]{2, 1, 3});
        int[] expected = {1, 1, 2, 3, 3, 3};
        for (int t = 0; t < 18; t++)
            assertEquals(expected[t % 6], loop.stillAt(t), "tick " + t);
        assertEquals(3, loop.stillAt(-1));
        assertEquals(1, loop.stillAt(6_000_000_000L));
        assertEquals(4, Timeline.of(4).stillAt(12345));
    }

    @Test
    void perFrameIdsComeBackFromTheLoop() {
        int[] frames = {3, 3, 8, 8, 8, 3, 1, 1, 3, 3};
        Timeline loop = Timeline.of(frames);
        for (int t = 0; t < frames.length; t++)
            assertEquals(frames[t], loop.stillAt(t));
    }

    @Test
    void refusesWhatTheFormatDoesNotAllow() {
        assertThrows(IllegalArgumentException.class, () -> new Timeline(new int[0], new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(new int[]{1, 2}, new int[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(new int[]{1, 2}, new int[]{1, 0}));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(new int[]{-1}, new int[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(new int[]{1, 1}, new int[]{1, 1}));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(new int[]{1}, new int[]{2}));
        assertThrows(IllegalArgumentException.class, Timeline::of);
        // The ends may match: merging them would rotate the loop.
        assertDoesNotThrow(() -> new Timeline(new int[]{1, 2, 1}, new int[]{1, 1, 1}));
    }

    @Test
    void copiesItsArrays() {
        int[] stills = {1, 2}, ticks = {1, 1};
        Timeline loop = new Timeline(stills, ticks);
        stills[0] = 7;
        ticks[0] = 9;
        assertEquals(1, loop.still(0));
        assertEquals(1, loop.ticks(0));
    }
}
