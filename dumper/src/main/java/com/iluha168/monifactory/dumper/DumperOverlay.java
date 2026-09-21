package com.iluha168.monifactory.dumper;

import com.mojang.blaze3d.platform.NativeImage;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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
     * Where the fake clock stands for a single still frame. Any fixed value will do; this one is the prototype's, so
     * its renders compare.
     */
    private static final long STILL_FRAME_MILLIS = 2_000_000L;

    private enum Step { BOOT, SERVER_RELOAD, JOIN, EMI, RENDER, EXIT, DONE }

    private final Minecraft minecraft;
    private final ReloadInstance bootReload;
    private final Path output;

    private Step step = Step.BOOT;
    private CompletableFuture<DataPlane.Server> serverReload;
    private long emiSince;

    DumperOverlay(Minecraft minecraft, ReloadInstance bootReload, Path output) {
        this.minecraft = minecraft;
        this.bootReload = bootReload;
        this.output = output;
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
                return Step.SERVER_RELOAD;
            }
            case SERVER_RELOAD -> {
                if (!serverReload.isDone()) return Step.SERVER_RELOAD;
                DataPlane.joinAndSync(minecraft, serverReload.join());
                serverReload = null;
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
                return Step.RENDER;
            }
            case RENDER -> {
                renderFirstRecipe();
                return Step.EXIT;
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

    private void renderFirstRecipe() throws Exception {
        var recipes = EmiApi.getRecipeManager();
        // TooManyRecipeViewers lists some categories twice with the same recipe objects in both, so recipes are
        // told apart by identity.
        Set<EmiRecipe> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        EmiRecipe first = null;
        EmiRecipeCategory firstCategory = null;
        int listed = 0;
        for (EmiRecipeCategory category : recipes.getCategories()) {
            for (EmiRecipe recipe : recipes.getRecipes(category)) {
                listed++;
                distinct.add(recipe);
                if (first == null) {
                    first = recipe;
                    firstCategory = category;
                }
            }
        }
        LOG.info("[dumper] EMI lists {} categories, {} recipes, {} distinct",
                recipes.getCategories().size(), listed, distinct.size());
        if (first == null) {
            throw new IllegalStateException("EMI has no recipes");
        }

        Files.createDirectories(output);
        String id = first.getId() == null ? "unnamed" : first.getId().toString();
        Path png = output.resolve(id.replaceAll("[^A-Za-z0-9._-]", "_") + ".png");
        try (NativeImage image = RecipeRenderer.render(minecraft, first, STILL_FRAME_MILLIS)) {
            long hash = RecipeRenderer.hash(image);
            image.writeToFile(png);
            LOG.info("[dumper] rendered {} ({}, display {}x{}) to {} at {}x{}, pixel hash {}",
                    id, firstCategory.getId(), first.getDisplayWidth(), first.getDisplayHeight(),
                    png, image.getWidth(), image.getHeight(), Long.toHexString(hash));
        }
    }
}
