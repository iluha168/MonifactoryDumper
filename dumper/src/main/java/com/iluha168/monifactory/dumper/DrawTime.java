package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.function.Supplier;

/**
 * The time a layer is drawn at, or its widgets are built at: the frozen clock, and the player's tick counted from it.
 * <p>
 * The clock alone is not enough. LDLib refreshes the content its widgets cache ({@code updateScreen()}) when
 * {@code player.tickCount} moved since the widget's last draw, and the player ticks in real time. With the tick left
 * running, a cycling slot or tank refreshed whenever a real tick happened to pass during a draw, sometimes in the
 * middle of a probe drawn a billion milliseconds away. Standing the tick at {@code millis / FRAME_MILLIS} makes the
 * refresh a function of the frozen time, the same one {@code renderRecipe} gets from a widget list built fresh for
 * its draw.
 * <p>
 * Not nestable: the end of a span releases the clock and puts back the tick it found, so a span inside another one
 * ends the outer span's freeze early. Render thread only.
 */
final class DrawTime {
    private DrawTime() {
    }

    /** Runs {@code body} at {@code millis}. */
    static void run(Minecraft minecraft, long millis, Runnable body) {
        get(minecraft, millis, () -> {
            body.run();
            return null;
        });
    }

    /** Runs {@code body} at {@code millis} and returns what it returns. */
    static <T> T get(Minecraft minecraft, long millis, Supplier<T> body) {
        // Before DataPlane made its player there is no tick to stand, and no LDLib UI could draw anyway.
        LocalPlayer player = minecraft.player;
        int tick = player == null ? 0 : player.tickCount;
        FakeTime.freeze(millis);
        try {
            if (player != null) player.tickCount = (int) (millis / FakeTime.FRAME_MILLIS);
            return body.get();
        } finally {
            if (player != null) player.tickCount = tick;
            FakeTime.release();
        }
    }
}
