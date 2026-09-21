package com.iluha168.monifactory.dumper;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * The renderer. It rides the real client boot and takes the frame over before the title screen exists.
 * <p>
 * Mods are constructed inside the {@link Minecraft} constructor, which goes on to start the initial resource reload
 * and set a vanilla {@link LoadingOverlay} for it. Nothing drains the game thread's task queue until that constructor
 * has returned, so a task queued here is the first thing the game loop runs, and by then the overlay is set. The task
 * puts {@link DumperOverlay} in its place.
 */
@Mod(Dumper.MOD_ID)
public final class Dumper {
    public static final String MOD_ID = "monifactorydumper";
    static final Logger LOG = LogUtils.getLogger();

    /** Where the render goes. The build passes it; the mod has no other configuration. */
    static final String OUTPUT_PROPERTY = "monifactory.dumper.output";

    /**
     * SRG names, the runtime names of Minecraft members this mod reaches by reflection. Each one is tied to this
     * Minecraft version, so there are as few as can be.
     */
    static final String SRG_LOADING_OVERLAY_RELOAD = "f_96164_";
    static final String SRG_CLIENT_PACKET_LISTENER_REGISTRY_ACCESS = "f_104903_";

    public Dumper() {
        String output = System.getProperty(OUTPUT_PROPERTY);
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("-D" + OUTPUT_PROPERTY + " is not set; the renderer has nowhere to write");
        }
        Minecraft minecraft = Minecraft.getInstance();
        // tell() always queues, even when called on the game thread, so this cannot run inside the constructor.
        minecraft.tell(() -> takeOver(minecraft, Path.of(output)));
    }

    private static void takeOver(Minecraft minecraft, Path output) {
        if (!(minecraft.getOverlay() instanceof LoadingOverlay vanilla)) {
            fail(new IllegalStateException("expected the boot's LoadingOverlay, found " + minecraft.getOverlay()));
            return;
        }
        ReloadInstance reload;
        try {
            Field field = LoadingOverlay.class.getDeclaredField(SRG_LOADING_OVERLAY_RELOAD);
            field.setAccessible(true);
            reload = (ReloadInstance) field.get(vanilla);
        } catch (ReflectiveOperationException e) {
            fail(e);
            return;
        }
        LOG.info("[dumper] replacing the vanilla loading overlay; frames pass through until the boot reload is done");
        minecraft.setOverlay(new DumperOverlay(minecraft, reload, output));
    }

    /**
     * Ends the process with a failure status. Never through Minecraft's crash handling: NotEnoughCrashes turns a
     * crash into a return to the title screen, and for a batch job that is a hang instead of a failure.
     */
    static void fail(Throwable cause) {
        LOG.error("[dumper] the render failed", cause);
        System.exit(1);
    }
}
