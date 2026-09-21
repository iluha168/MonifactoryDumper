package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.runtime.EmiDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;

/**
 * Draws one EMI recipe the way EMI's own recipe screenshot does, into an offscreen target, and reads it back.
 * Runs on the game thread, inside a frame.
 */
final class RecipeRenderer {
    private RecipeRenderer() {
    }

    /** EMI pads a recipe screenshot by this much, in GUI pixels, around the recipe's display size. */
    private static final int PADDING = 8;

    /**
     * Renders {@code recipe} with the clock frozen at {@code millis} and returns the image, which the caller closes.
     * The size is {@code (displayWidth + 8) * scale} by {@code (displayHeight + 8) * scale}.
     */
    static NativeImage render(Minecraft minecraft, EmiRecipe recipe, long millis) {
        int width = recipe.getDisplayWidth() + PADDING;
        int height = recipe.getDisplayHeight() + PADDING;
        // 0 in the config means "the window's GUI scale", and the headless window's is 2.
        int scale = EmiConfig.recipeScreenshotScale < 1
                ? (int) minecraft.getWindow().getGuiScale()
                : EmiConfig.recipeScreenshotScale;

        RenderTarget target = new TextureTarget(width * scale, height * scale, true, Minecraft.ON_OSX);
        try {
            target.setClearColor(0f, 0f, 0f, 0f);
            // Clear before bindWrite, never after: clear() ends by unbinding, which would leave the draw going to
            // the main framebuffer and the target blank.
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            PoseStack view = RenderSystem.getModelViewStack();
            Matrix4f projection = RenderSystem.getProjectionMatrix();
            view.pushPose();
            FakeTime.freeze(millis);
            // One recipe that throws must not leave the model-view stack, the projection or the clock behind for
            // every recipe after it.
            try {
                view.setIdentity();
                view.translate(-1.0, 1.0, 0.0);
                view.scale(2f / width, -2f / height, -1f / 1000f);
                view.translate(0.0, 0.0, 10.0);
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(new Matrix4f().identity(), VertexSorting.ORTHOGRAPHIC_Z);

                GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
                EmiRenderHelper.renderRecipe(recipe, EmiDrawContext.wrap(graphics), 0, 0, false, -1);
                graphics.flush();
            } finally {
                FakeTime.release();
                RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
                view.popPose();
                RenderSystem.applyModelViewMatrix();
                target.unbindWrite();
                minecraft.getMainRenderTarget().bindWrite(true);
            }

            NativeImage image = new NativeImage(target.width, target.height, false);
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, true);
            image.flipY();
            return image;
        } finally {
            target.destroyBuffers();
        }
    }

    /**
     * FNV-1a over the image's pixels. Equal hashes mean equal frames, which is all animation detection asks.
     * <p>
     * The prototype hashed {@code NativeImage.asByteArray()}, which PNG-encodes the frame through STB first and was
     * 70% of the time spent per frame. The pixels decide the same equality, since that encoding is lossless and
     * deterministic, at 2.7x the frame rate.
     */
    static long hash(NativeImage image) {
        long hash = 0xcbf29ce484222325L;
        for (int pixel : image.getPixelsRGBA()) {
            for (int shift = 0; shift < 32; shift += 8) {
                hash ^= (pixel >>> shift) & 0xff;
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }
}
