package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
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

    /** Where the render goes. The build passes it. */
    static final String OUTPUT_PROPERTY = "monifactory.dumper.output";
    /** What the run makes: see {@link Mode}. Default {@code sample}. */
    static final String MODE_PROPERTY = "monifactory.dumper.mode";
    /**
     * How many recipes the sample or census draws. Default 1 for a sample and 4,000 for a census. The build renders
     * the whole corpus unless this is set, and then only its first that many recipes. For seq it is per category,
     * default 6.
     */
    static final String COUNT_PROPERTY = "monifactory.dumper.count";
    /** The sample's or census's seed. Default 0. */
    static final String SEED_PROPERTY = "monifactory.dumper.seed";

    enum Mode {
        /**
         * The build: {@code recipes.json} for the whole corpus and its pictures, their stills in {@code stills.pak},
         * see {@link Batch}.
         */
        DUMP,
        /**
         * The build's {@code recipes.json}, {@code categories.tsv} and {@code meta.json} without any images, see
         * {@link DataDump}. Takes {@code count}, {@code every} and {@code sample} the way the build does.
         */
        DATA,
        /** A seeded sample rendered to PNGs with a manifest, see {@link Sample}. */
        SAMPLE,
        /** The animation census over a sample, see {@link Census}. */
        CENSUS,
        /** Raw frame-hash sequences of a sample, for checking the detection rules offline, see {@link Seq}. */
        SEQ;

        /** Whether the run writes meta.json, and so needs to know the pack's version and mode. */
        boolean describesPack() {
            return this == DUMP || this == DATA;
        }

        static Mode of(String name) {
            try {
                return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("-D" + MODE_PROPERTY + "=" + name + " is none of "
                        + java.util.Arrays.toString(values()), e);
            }
        }
    }

    /** What one run was asked for. */
    record Job(Mode mode, Path output, long seed, int count) {
    }

    /**
     * SRG names, the runtime names of Minecraft members this mod reaches by reflection. Each one is tied to this
     * Minecraft version, so there are as few as can be. They are looked up through Forge's
     * {@link ObfuscationReflectionHelper}, which is the name itself in production and its mojmap name on the dev road,
     * where the dev-versus-production pixel diff runs this same mod.
     */
    static final String SRG_LOADING_OVERLAY_RELOAD = "f_96164_";
    static final String SRG_CLIENT_PACKET_LISTENER_REGISTRY_ACCESS = "f_104903_";

    public Dumper() {
        String output = System.getProperty(OUTPUT_PROPERTY);
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("-D" + OUTPUT_PROPERTY + " is not set; the renderer has nowhere to write");
        }
        Mode mode = Mode.of(System.getProperty(MODE_PROPERTY, "sample"));
        int count = Integer.getInteger(COUNT_PROPERTY, switch (mode) {
            case DUMP, DATA -> Integer.MAX_VALUE;
            case SAMPLE -> 1;
            case CENSUS -> 4000;
            case SEQ -> Seq.PER_CATEGORY;
        });
        long seed = Long.getLong(SEED_PROPERTY, 0L);
        if (count < 1) {
            throw new IllegalStateException("-D" + COUNT_PROPERTY + " must be at least 1, is " + count);
        }
        // Animated sprites stay on the frame they loaded with. Otherwise the game loop advances them once per tick
        // for however long the boot happened to take, and the same recipe renders differently from boot to boot.
        FakeTime.holdAtlas(true);
        Minecraft minecraft = Minecraft.getInstance();
        // tell() always queues, even when called on the game thread, so this cannot run inside the constructor.
        Job job = new Job(mode, Path.of(output), seed, count);
        minecraft.tell(() -> takeOver(minecraft, job));
    }

    private static void takeOver(Minecraft minecraft, Job job) {
        if (!(minecraft.getOverlay() instanceof LoadingOverlay vanilla)) {
            fail(new IllegalStateException("expected the boot's LoadingOverlay, found " + minecraft.getOverlay()));
            return;
        }
        ReloadInstance reload;
        try {
            Field field = ObfuscationReflectionHelper.findField(LoadingOverlay.class, SRG_LOADING_OVERLAY_RELOAD);
            reload = (ReloadInstance) field.get(vanilla);
        } catch (ReflectiveOperationException | ObfuscationReflectionHelper.UnableToFindFieldException e) {
            fail(e);
            return;
        }
        LOG.info("[dumper] replacing the vanilla loading overlay for {}; frames pass through until the boot reload is"
                + " done", job);
        minecraft.setOverlay(new DumperOverlay(minecraft, reload, job));
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
