package com.iluha168.monifactory.dumper;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.runtime.EmiReloadManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraftforge.client.loading.ClientModLoader;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * The overlay the game draws every frame from the moment the boot reload starts, and the only thing it draws. Its
 * {@link #render} is the renderer's whole run, as a step machine on the real render thread, inside the real frame.
 * <p>
 * Every step that waits returns from the frame instead of blocking it. The boot reload in particular hands its apply
 * stages to this thread one after another, and a frame that blocks there deadlocks the chain. Returning also lets
 * the game loop run the tasks EMI's reload queues for this thread.
 */
final class DumperOverlay extends Overlay {
    private static final long EMI_TIMEOUT = TimeUnit.MINUTES.toMillis(15);
    /**
     * The server datapack reload takes seconds. One boot has been seen to stop in it for good, its workers gone idle
     * after the tags loaded, and a batch job that waits forever is worse than one that fails.
     */
    private static final long SERVER_RELOAD_TIMEOUT = TimeUnit.MINUTES.toMillis(10);

    private enum Step { BOOT, SERVER_RELOAD, JOIN, EMI, PACK, RENDER, EXIT, DONE }

    private final Minecraft minecraft;
    private final ReloadInstance bootReload;
    private final Dumper.Job job;

    private Step step = Step.BOOT;
    private CompletableFuture<DataPlane.Server> serverReload;
    /** The server side, until EMI has read what it synced; see {@link ServerLeftovers}. */
    private DataPlane.Server server;
    private long serverReloadSince, emiSince;
    private Corpus corpus;
    /** Asked once a frame until FancyMenu has read the version, see {@link Pack}. */
    private Pack.Resolver packResolver;
    /** Every registry's tags, taken off the server side before it goes, for the artifact; see {@link Tags}. */
    private Tags tags;
    /** The render step's work, one frame's budget per call; true once it is done. */
    private Work work;

    interface Work {
        boolean advance() throws Exception;
    }

    DumperOverlay(Minecraft minecraft, ReloadInstance bootReload, Dumper.Job job) {
        this.minecraft = minecraft;
        this.bootReload = bootReload;
        this.job = job;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Headless, framebuffer 0 is never complete, so the game's blit to it fails every frame, and the default GL
        // debug callback logs every failure. Something in the boot turns debug output back on, hence every frame.
        GL11.glDisable(GL43.GL_DEBUG_OUTPUT);
        try {
            step = advance();
        } catch (Throwable t) {
            step = Step.DONE;
            Dumper.fail(t);
        }
    }

    private Step advance() throws Exception {
        switch (step) {
            case BOOT -> {
                if (!bootReload.isDone()) return Step.BOOT;
                bootReload.checkExceptions();
                // The end of the boot, which the vanilla overlay would have run from its last frame. Its title screen
                // and load-time telemetry are left out.
                if (ClientModLoader.completeModLoading()) {
                    if (!ModLoader.isLoadingStateValid()) {
                        throw new IllegalStateException("mod loading failed; see the log above");
                    }
                    // Only warnings, which Forge shows a player on a screen of their own. They are in the log too.
                    minecraft.setScreen(null);
                }
                LOG.info("[dumper] boot reload done; starting the server datapack reload");
                serverReload = DataPlane.startServerReload(FMLPaths.GAMEDIR.get());
                serverReloadSince = System.currentTimeMillis();
                return Step.SERVER_RELOAD;
            }
            case SERVER_RELOAD -> {
                if (!serverReload.isDone()) {
                    if (System.currentTimeMillis() - serverReloadSince > SERVER_RELOAD_TIMEOUT) {
                        throw new IllegalStateException("the server datapack reload did not finish in "
                                + SERVER_RELOAD_TIMEOUT + " ms");
                    }
                    return Step.SERVER_RELOAD;
                }
                server = serverReload.join();
                serverReload = null;
                if (job.mode().describesPack()) tags = Tags.of(server.full());
                DataPlane.joinAndSync(minecraft, server);
                emiSince = System.currentTimeMillis();
                LOG.info("[dumper] recipes and tags synced; waiting for EMI's reload");
                return Step.EMI;
            }
            case EMI -> {
                if (EmiReloadManager.getStatus() == -1) {
                    throw new IllegalStateException("EMI's reload failed; see the log above");
                }
                if (!EmiReloadManager.isLoaded()) {
                    if (System.currentTimeMillis() - emiSince > EMI_TIMEOUT) {
                        throw new IllegalStateException("EMI did not finish reloading in " + EMI_TIMEOUT + " ms, at step "
                                + EmiReloadManager.reloadStep);
                    }
                    return Step.EMI;
                }
                LOG.info("[dumper] EMI loaded in {} ms", System.currentTimeMillis() - emiSince);
                // Nothing reads the server side from here on. A run that renders holds the rest of the heap for the
                // length of the batch; the data mode writes its file and exits.
                if (job.mode().renders()) ServerLeftovers.release(server);
                server = null;
                if (job.mode().renders()) ServerLeftovers.collect();
                corpus = Corpus.of(EmiApi.getRecipeManager());
                if (!job.mode().describesPack()) {
                    work = switch (job.mode()) {
                        case SAMPLE -> Sample.choose(minecraft, corpus, job.output(), job.seed(), job.count())::advance;
                        case CENSUS -> Census.choose(minecraft, corpus, job.output(), job.seed(), job.count())::advance;
                        case SEQ -> Seq.choose(minecraft, corpus, job.output(), job.seed(), job.count())::advance;
                        default -> throw new IllegalStateException("no work for " + job.mode());
                    };
                    ClientTicks.freeze(minecraft);
                    return Step.RENDER;
                }
                // Before the batch rather than when meta.json is written, so a version that disagrees with the manifest
                // fails the build in minutes, not at the end of it.
                packResolver = new Pack.Resolver();
                return Step.PACK;
            }
            case PACK -> {
                Pack pack = packResolver.poll();
                if (pack == null) return Step.PACK;
                packResolver = null;
                work = switch (job.mode()) {
                    case DUMP -> Batch.start(minecraft, corpus, pack, tags, job.output(), job.count())::advance;
                    case DATA -> {
                        DataDump.write(minecraft, corpus, pack, tags, job.output(), job.count());
                        yield () -> true;
                    }
                    default -> throw new IllegalStateException("no work for " + job.mode());
                };
                corpus = null;
                tags = null;
                if (job.mode().renders()) ClientTicks.freeze(minecraft);
                return Step.RENDER;
            }
            case RENDER -> {
                return work.advance() ? Step.EXIT : Step.RENDER;
            }
            case EXIT -> {
                // Ends the game loop after this frame. Main.main then runs Minecraft.destroy(), which exits with 0.
                minecraft.stop();
                return Step.DONE;
            }
            default -> {
                return Step.DONE;
            }
        }
    }
}
