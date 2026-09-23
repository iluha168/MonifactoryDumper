package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
import com.iluha168.monifactory.imgencoder.Frame;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
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
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws one EMI recipe the way EMI's own recipe screenshot does, into an offscreen target, and reads it back.
 * Runs on the game thread, inside a frame.
 */
final class RecipeRenderer {
    private RecipeRenderer() {
    }

    /** EMI pads a recipe screenshot by this much, in GUI pixels, around the recipe's display size. */
    static final int PADDING = 8;

    /**
     * Renders {@code recipe} with the clock frozen at {@code millis} and returns the image, which the caller closes.
     * The size is {@code (displayWidth + 8) * scale} by {@code (displayHeight + 8) * scale}.
     */
    static NativeImage render(Minecraft minecraft, EmiRecipe recipe, long millis) {
        RenderTarget target = drawTo(minecraft, recipe, millis, 0);
        NativeImage image = new NativeImage(target.width, target.height, false);
        RenderSystem.bindTexture(target.getColorTextureId());
        image.downloadTexture(0, true);
        image.flipY();
        return image;
    }

    /**
     * A frame read back into memory the renderer owns: {@code width * height} pixels at the start of {@link #pixels},
     * in the GL's order, bottom row first, as {@code 0xAABBGGRR}. Valid until the next {@link #draw}.
     */
    static final class Readback {
        int width, height;
        int[] pixels = new int[0];

        int size() {
            return width * height;
        }

        /** The frame upright and as {@code 0xAARRGGBB}, in a fresh array. */
        Frame frame() {
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                int from = (height - 1 - y) * width, to = y * width;
                for (int x = 0; x < width; x++) {
                    int p = pixels[from + x];
                    argb[to + x] = p & 0xFF00FF00 | (p & 0xFF) << 16 | (p >>> 16) & 0xFF;
                }
            }
            return new Frame(width, height, argb);
        }
    }

    private static final Readback READBACK = new Readback();

    /** Time spent drawing and reading back through {@link #draw} and {@link Pipeline}, for the logs. Game thread only. */
    static long drawNanos, readNanos;

    /**
     * {@link #render} without the {@link NativeImage}: the frame is read straight into one reused array. For the frames
     * the build only hashes, which are nearly all of them, that saves allocating, flipping, copying out and freeing
     * a native image per draw.
     */
    static Readback draw(Minecraft minecraft, EmiRecipe recipe, long millis) {
        long start = System.nanoTime();
        RenderTarget target = drawTo(minecraft, recipe, millis, 0);
        long drawn = System.nanoTime();
        drawNanos += drawn - start;
        Readback readback = READBACK;
        readback.width = target.width;
        readback.height = target.height;
        if (readback.pixels.length < readback.size()) readback.pixels = new int[readback.size()];
        RenderSystem.bindTexture(target.getColorTextureId());
        // Rows of whole RGBA pixels are always 4-aligned. NativeImage sets the same before its own read.
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readback.pixels);
        readNanos += System.nanoTime() - drawn;
        return readback;
    }

    /**
     * Draws frames one after another with each readback overlapped with the next draw: the frame is copied into a
     * pixel buffer object, which the GL fills while the game thread already draws the next one, and it is read out of
     * that buffer only then. A synchronous read waits for the GPU to finish the frame first, and that wait was nearly
     * half of every draw. Two buffers and two targets take turns, so no draw has to wait for the copy before it.
     * Game thread only, one sequence at a time.
     */
    static final class Pipeline {
        private final int[] buffers = new int[2];
        private final long[] capacity = new long[2];
        private final int[] widths = new int[2], heights = new int[2];
        private int turn;
        /** The buffer holding a frame not yet read out, or -1. */
        private int pending = -1;

        /**
         * Draws {@code recipe} at {@code millis} and returns the frame drawn by the call before this one, or null if
         * there was none. The returned readback is valid until the next call.
         */
        Readback draw(Minecraft minecraft, EmiRecipe recipe, long millis) {
            long start = System.nanoTime();
            int slot = turn;
            turn ^= 1;
            RenderTarget target = drawTo(minecraft, recipe, millis, 1 + slot);
            long drawn = System.nanoTime();
            drawNanos += drawn - start;
            if (buffers[slot] == 0) buffers[slot] = GL15.glGenBuffers();
            long bytes = 4L * target.width * target.height;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[slot]);
            if (capacity[slot] < bytes) {
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ);
                capacity[slot] = bytes;
            }
            RenderSystem.bindTexture(target.getColorTextureId());
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            widths[slot] = target.width;
            heights[slot] = target.height;
            int previous = pending;
            pending = slot;
            Readback readback = previous < 0 ? null : collect(previous);
            // Nothing else in the game reads pixels through a bound pack buffer, so none may stay bound.
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            readNanos += System.nanoTime() - drawn;
            return readback;
        }

        /** Reads out the last frame drawn, if it has not been: the end of a sequence. */
        Readback finish() {
            if (pending < 0) return null;
            long start = System.nanoTime();
            Readback readback = collect(pending);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            pending = -1;
            readNanos += System.nanoTime() - start;
            return readback;
        }

        /** Forgets the frame in flight, if any. */
        void discard() {
            pending = -1;
        }

        private Readback collect(int slot) {
            Readback readback = READBACK;
            readback.width = widths[slot];
            readback.height = heights[slot];
            if (readback.pixels.length < readback.size()) readback.pixels = new int[readback.size()];
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[slot]);
            java.nio.ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY,
                    4L * readback.size(), null);
            if (mapped == null) throw new IllegalStateException("glMapBuffer returned nothing");
            try {
                mapped.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer().get(readback.pixels, 0, readback.size());
            } finally {
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
            return readback;
        }
    }

    /**
     * Draws {@code recipe} into a target of its size and returns the target, still holding the frame. Targets of one
     * size are told apart by {@code variant}, so {@link Pipeline} can draw into one while the other is being copied.
     */
    /** Pixels per GUI pixel, EMI's screenshot scale. 0 in its config means the window's GUI scale, headless 2. */
    static int scale(Minecraft minecraft) {
        return EmiConfig.recipeScreenshotScale < 1
                ? (int) minecraft.getWindow().getGuiScale()
                : EmiConfig.recipeScreenshotScale;
    }

    private static RenderTarget drawTo(Minecraft minecraft, EmiRecipe recipe, long millis, int variant) {
        int width = recipe.getDisplayWidth() + PADDING;
        int height = recipe.getDisplayHeight() + PADDING;
        int scale = scale(minecraft);

        RenderTarget target = TARGETS.get(width * scale, height * scale, variant);
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
        return target;
    }

    /**
     * Offscreen targets by pixel size, the least recently used dropped past {@link #CAPACITY}. A recipe is drawn several
     * times over and most categories hold one or two display sizes, so a handful of targets serve the whole corpus
     * instead of one being made and destroyed per draw. Game thread only.
     */
    private static final class Targets extends LinkedHashMap<Long, RenderTarget> {
        static final int CAPACITY = 32;

        Targets() {
            super(64, 0.75f, true);
        }

        RenderTarget get(int width, int height, int variant) {
            return computeIfAbsent(((long) variant << 60) | ((long) width << 30) | height,
                    size -> new TextureTarget(width, height, true, Minecraft.ON_OSX));
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, RenderTarget> eldest) {
            if (size() <= CAPACITY) return false;
            eldest.getValue().destroyBuffers();
            return true;
        }
    }

    private static final Targets TARGETS = new Targets();

    /**
     * FNV-1a over the image's pixels. Equal hashes mean equal frames, which is all animation detection asks.
     * <p>
     * The prototype hashed {@code NativeImage.asByteArray()}, which PNG-encodes the frame through STB first and was
     * 70% of the time spent per frame. The pixels decide the same equality, since that encoding is lossless and
     * deterministic, at 2.7x the frame rate.
     */
    static long hash(NativeImage image) {
        return hash(image.getPixelsRGBA());
    }

    /**
     * A 64-bit hash of the first {@code n} pixels, for telling frames of one recipe apart. Four independent lanes of a
     * multiply-rotate round, so it runs at memory speed where byte-wise FNV-1a is one long chain of multiplies; at the
     * hundreds of draws an animated recipe takes, that difference is a large part of the frame.
     */
    static long fastHash(int[] pixels, int n) {
        long a = 0x9E3779B97F4A7C15L, b = 0xC2B2AE3D27D4EB4FL, c = 0x165667B19E3779F9L, d = 0x85EBCA77C2B2AE63L;
        int i = 0;
        for (; i + 3 < n; i += 4) {
            a = Long.rotateLeft(a + pixels[i] * 0xC2B2AE3D27D4EB4FL, 31) * 0x9E3779B97F4A7C15L;
            b = Long.rotateLeft(b + pixels[i + 1] * 0xC2B2AE3D27D4EB4FL, 31) * 0x9E3779B97F4A7C15L;
            c = Long.rotateLeft(c + pixels[i + 2] * 0xC2B2AE3D27D4EB4FL, 31) * 0x9E3779B97F4A7C15L;
            d = Long.rotateLeft(d + pixels[i + 3] * 0xC2B2AE3D27D4EB4FL, 31) * 0x9E3779B97F4A7C15L;
        }
        for (; i < n; i++) {
            a = Long.rotateLeft(a + pixels[i] * 0xC2B2AE3D27D4EB4FL, 31) * 0x9E3779B97F4A7C15L;
        }
        long h = Long.rotateLeft(a, 1) + Long.rotateLeft(b, 7) + Long.rotateLeft(c, 12) + Long.rotateLeft(d, 18) + n;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ h >>> 33;
    }

    /** {@link #hash(NativeImage)} over pixels already read out with {@link NativeImage#getPixelsRGBA()}. */
    static long hash(int[] pixels) {
        long hash = 0xcbf29ce484222325L;
        for (int pixel : pixels) {
            for (int shift = 0; shift < 32; shift += 8) {
                hash ^= (pixel >>> shift) & 0xff;
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }
}
