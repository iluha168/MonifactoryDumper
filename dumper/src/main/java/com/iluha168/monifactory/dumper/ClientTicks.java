package com.iluha168.monifactory.dumper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * Stops the client's game ticks for the rest of the run, by making its {@link Timer}'s tick last
 * {@link Float#MAX_VALUE} milliseconds. The game loop still runs every frame (the overlay, the task queue, the frame
 * limiter); only {@code Minecraft.tick} never comes again.
 * <p>
 * The fake clock already holds what a draw reads through {@code Util.getMillis}, {@code System.currentTimeMillis}, the
 * level's game and day time and the atlas tick. What it does not hold, and what stops moving here:
 * <ul>
 *   <li>{@code partialTick}, which the frame hands every widget;</li>
 *   <li>the client level's game time, where something reads the field rather than {@code getGameTime()};</li>
 *   <li>GregTech's {@code GTValues.CLIENT_TIME}, counted up in its client tick event;</li>
 *   <li>the shaders' {@code GameTime} uniform, which comes from the level's game time and the partial tick.</li>
 * </ul>
 * The client tick was also 7% of the render thread's samples, and freezing it took about 2% off the batch (measured
 * on a {@code sample=50} batch of 2,380 recipes, whose pictures agree with a run that ticked). It happens once the
 * batch starts: the boot, the reloads and the pack's version lookup keep their ticks.
 * <p>
 * {@code msPerTick} is final. It is an instance field of an ordinary class, so reflection may still set it, and
 * {@code advanceTime} reads it every frame.
 */
final class ClientTicks {
    /** {@code Minecraft.timer} and {@code Timer.msPerTick}. */
    private static final String SRG_MINECRAFT_TIMER = "f_90991_";
    private static final String SRG_TIMER_MS_PER_TICK = "f_92521_";

    private ClientTicks() {
    }

    /** Freezes the ticks, or logs why it could not; the render does not depend on it. */
    static void freeze(Minecraft minecraft) {
        try {
            Timer timer = (Timer) ObfuscationReflectionHelper.findField(Minecraft.class, SRG_MINECRAFT_TIMER)
                    .get(minecraft);
            Field msPerTick = ObfuscationReflectionHelper.findField(Timer.class, SRG_TIMER_MS_PER_TICK);
            float was = msPerTick.getFloat(timer);
            msPerTick.setFloat(timer, Float.MAX_VALUE);
            LOG.info("[dumper] client ticks frozen: {} ms per tick -> {}", was, msPerTick.getFloat(timer));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("[dumper] client ticks keep running, the timer could not be reached: {}", e.toString());
        }
    }
}
