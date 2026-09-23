package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.layered.StillHash;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Draws a recipe's layers as tiles of one offscreen target and hands back each layer's straight-alpha picture
 * (DESIGN 3.5).
 * <p>
 * A tile is a crop box of the recipe's canvas and something that draws. It is drawn twice, over opaque black and over
 * opaque white, each into its own region of the target: the box plus a 1-pixel guard ring on every side, cleared on
 * its own, with the viewport on the region and a transform that maps exactly that part of the canvas onto it. What a
 * draw puts outside the region the viewport clips, so tiles cannot spill into each other, and what lands on the ring
 * is how {@link Matte} tells that a layer escaped its box. Tiles are shelf-packed ({@link TilePacking}) into
 * power-of-two targets up to {@value #MAX_SIDE} square, several passes when they don't fit one, and each pass is read
 * back once, only the rectangle it used.
 * <p>
 * The readback goes into a pixel buffer object, so {@link #submit} returns once the GL has the copy queued, and the
 * pixels are only waited for in {@link Submission#collect}. Two buffers take turns: the caller can submit the next
 * batch before collecting this one, and the GPU finishes one while the render thread draws the other. Collecting maps
 * the buffer and runs the alpha solve, the ring check and the hash per tile on the caller's executor, straight out of
 * the mapped memory. The pixels never pass through a Java array: {@code glReadPixels} into one held a JNI critical
 * section through the whole GPU wait while other threads allocated, and sync plus readback cost ten times as much
 * (REPORT 8, trap 3).
 * <p>
 * Ordering. Submissions alternate between the two buffers, so submission N's buffer is written again by submission
 * N + 2. Collect N before submitting N + 2; a submission overwritten first throws when collected. Collect each
 * submission once. What {@code collect} returns is the caller's: the buffer is unmapped before it returns, and nothing
 * in the results points into it, so they stay valid through any later submit. Everything here is render thread only, {@code collect} included, since it maps and unmaps a GL
 * buffer; only the per-tile work runs on the executor, and {@code collect} returns when all of it has.
 * <p>
 * Time. Nothing here freezes the clock or stands the player's tick. The caller wraps {@link #submit} in
 * {@link DrawTime} at the plan's {@link LayerPlan#millis()} (DESIGN 3.3): every draw of the batch, flushes included,
 * happens inside the call.
 * <p>
 * One renderer serves a whole run. It keeps no per-recipe state; what it holds is a few targets and the two buffers,
 * bounded, which {@link #close} releases.
 */
final class TileRenderer implements AutoCloseable {
    /** The largest target side. Anything bigger is split into passes; a tile bigger than this can't be drawn. */
    static final int MAX_SIDE = 4096;
    /** The guard ring around a crop box, in pixels on every side. */
    static final int RING = 1;
    /**
     * Targets kept, by size and buffer turn. A recipe's passes come in a few sizes, and one turn's target is never the
     * one the other turn's readback may still be copying out of.
     */
    private static final int TARGETS = 8;

    /**
     * Wraps a tile's draw over black, the one the caller watches: it must run {@code draw} exactly once. The draw
     * includes the flush, so what the layer sends to the GPU happens inside.
     */
    @FunctionalInterface
    interface Bracket {
        void around(Runnable draw);
    }

    /**
     * One tile: where on the canvas it is cut, in pixels, what draws it, and what brackets its draw over black, or
     * null. The body draws with the recipe's transform set: GUI pixel (0, 0) is the card's corner, as for
     * {@link LayerPlan.Layer#draw}.
     */
    record Tile(LayerPlan.Box box, Consumer<GuiGraphics> body, Bracket black) {
        Tile(LayerPlan.Box box, Consumer<GuiGraphics> body) {
            this(box, body, null);
        }

        /** A layer of a plan, in its own box. */
        static Tile of(LayerPlan.Layer layer) {
            return new Tile(layer.box(), layer::draw);
        }
    }

    /**
     * A tile's result.
     *
     * @param still       the box's picture, straight alpha, top row first
     * @param escaped     whether the layer drew on the guard ring, outside its box
     * @param alphaSpread how far the colour channels disagreed on alpha; see {@link Matte#MAX_ALPHA_SPREAD}
     * @param hash        the still's identity
     */
    record Matted(Frame still, boolean escaped, int alphaSpread, StillHash hash) {
    }

    private final Minecraft minecraft;
    private final Executor executor;
    private final int[] buffers = new int[2];
    private final long[] capacity = new long[2];
    /** The submission each buffer holds, collected or not. */
    private final Submission[] holders = new Submission[2];
    private int turn;
    private boolean closed;

    private final Map<Long, RenderTarget> targets = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, RenderTarget> eldest) {
            if (size() <= TARGETS) return false;
            eldest.getValue().destroyBuffers();
            return true;
        }
    };

    /** A renderer that runs the per-tile work of {@link Submission#collect} on {@code executor}. */
    TileRenderer(Minecraft minecraft, Executor executor) {
        this.minecraft = minecraft;
        this.executor = executor;
    }

    /**
     * Draws {@code tiles} of {@code plan}'s canvas and queues their readback. Each tile's box must lie within the
     * canvas; the ring around it may not. Run it inside {@link DrawTime} at the plan's time. If a body throws, the GL
     * state is put back, and the submission before the last can no longer be collected: collect it first.
     */
    Submission submit(LayerPlan plan, List<Tile> tiles) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("the tile renderer is closed");
        int n = tiles.size();
        int[] widths = new int[n], heights = new int[n];
        for (int i = 0; i < n; i++) {
            LayerPlan.Box box = tiles.get(i).box();
            if (box.x() < 0 || box.y() < 0 || box.width() < 1 || box.height() < 1
                    || box.x() + box.width() > plan.width() || box.y() + box.height() > plan.height())
                throw new IllegalArgumentException("tile " + i + " " + box + " is not inside the " + plan.width() + "x"
                        + plan.height() + " canvas");
            // Black and white side by side: one shelf item, so the pair shares a pass and a row stride.
            widths[i] = 2 * (box.width() + 2 * RING);
            heights[i] = box.height() + 2 * RING;
        }
        if (n == 0) return new Submission(-1, List.of(), new int[0], new int[0], 0);
        TilePacking.Layout layout = TilePacking.pack(widths, heights, MAX_SIDE);

        int slot = turn;
        // The buffer's previous holder is gone from the first readback on, whether this submit gets to the end or not.
        if (holders[slot] != null) holders[slot].stale = true;
        holders[slot] = null;
        // Where each pass's rectangle starts in the buffer, in ints, and the whole buffer.
        int[] passOffsets = new int[layout.passes().size()];
        long ints = 0;
        for (int p = 0; p < passOffsets.length; p++) {
            passOffsets[p] = Math.toIntExact(ints);
            TilePacking.Pass pass = layout.passes().get(p);
            ints += (long) pass.width() * pass.height();
        }
        long bytes = 4 * ints;
        if (bytes > Integer.MAX_VALUE) throw new IllegalArgumentException("a " + bytes + "-byte readback is too large");

        for (int p = 0; p < passOffsets.length; p++) {
            drawPass(plan, tiles, layout, p, slot, passOffsets[p], bytes);
        }
        turn ^= 1;
        int[] black = new int[n], stride = new int[n];
        for (int i = 0; i < n; i++) {
            TilePacking.Place place = layout.places().get(i);
            int passWidth = layout.passes().get(place.pass()).width();
            black[i] = passOffsets[place.pass()] + place.y() * passWidth + place.x();
            stride[i] = passWidth;
        }
        Submission submission = new Submission(slot, List.copyOf(tiles), black, stride, bytes);
        submission.passes = passOffsets.length;
        holders[slot] = submission;
        return submission;
    }

    /** Draws pass {@code p}'s tiles into its target and reads the used rectangle into buffer {@code slot}. */
    private void drawPass(LayerPlan plan, List<Tile> tiles, TilePacking.Layout layout, int p, int slot, int offset,
                          long bytes) {
        TilePacking.Pass pass = layout.passes().get(p);
        RenderTarget target = targets.computeIfAbsent(
                (long) slot << 62 | (long) pass.targetWidth() << 31 | pass.targetHeight(),
                key -> new TextureTarget(pass.targetWidth(), pass.targetHeight(), true, Minecraft.ON_OSX));
        target.bindWrite(true);

        float scale = plan.scale();
        PoseStack view = RenderSystem.getModelViewStack();
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        view.pushPose();
        // One layer that throws must not leave the model-view stack, the projection, the viewport or the target
        // behind for every draw after it.
        try {
            RenderSystem.setProjectionMatrix(new Matrix4f().identity(), VertexSorting.ORTHOGRAPHIC_Z);
            for (int i = 0; i < tiles.size(); i++) {
                TilePacking.Place place = layout.places().get(i);
                if (place.pass() != p) continue;
                Tile tile = tiles.get(i);
                int w = tile.box().width() + 2 * RING;
                Runnable black = () -> drawTile(target, tile, scale, place.x(), place.y(), 0f);
                if (tile.black() == null) black.run();
                else tile.black().around(black);
                drawTile(target, tile, scale, place.x() + w, place.y(), 1f);
            }
            // A layer may have bound another framebuffer on its way; the readback is of this one.
            target.bindWrite(false);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer(slot, bytes));
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glReadPixels(0, 0, pass.width(), pass.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4L * offset);
        } finally {
            // Nothing else in the game reads pixels through a bound pack buffer, so none may stay bound.
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            RenderSystem.disableScissor();
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            view.popPose();
            RenderSystem.applyModelViewMatrix();
            target.unbindWrite();
            minecraft.getMainRenderTarget().bindWrite(true);
        }
    }

    /**
     * One draw of {@code tile} into its region at ({@code x}, {@code y}) from the target's bottom left, over grey
     * level {@code clear}. Everything a layer could have changed on its way is set again, per draw: the framebuffer
     * (a layer may bind its own and leave it bound), the viewport and the model-view.
     */
    private void drawTile(RenderTarget target, Tile tile, float scale, int x, int y, float clear) {
        LayerPlan.Box box = tile.box();
        int w = box.width() + 2 * RING, h = box.height() + 2 * RING;
        target.bindWrite(false);
        RenderSystem.viewport(x, y, w, h);
        RenderSystem.enableScissor(x, y, w, h);
        RenderSystem.clearColor(clear, clear, clear, 1f);
        RenderSystem.clearDepth(1.0);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.disableScissor();

        // RecipeRenderer.drawTo maps the canvas, GUI (0, 0) to (guiWidth, guiHeight), onto the whole viewport. This
        // maps the tile's part of it, ring included, the same way: the same pixels per GUI pixel, moved so the tile's
        // corner lands on the viewport's. Boxes are whole pixels, so every vertex lands where it does on the canvas,
        // shifted by whole pixels.
        float left = (box.x() - RING) / scale, top = (box.y() - RING) / scale;
        PoseStack view = RenderSystem.getModelViewStack();
        view.setIdentity();
        view.translate(-1.0f, 1.0f, 0.0f);
        view.scale(2f * scale / w, -2f * scale / h, -1f / 1000f);
        view.translate(-left, -top, 10.0f);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        tile.body().accept(graphics);
        graphics.flush();
    }

    /** Buffer {@code slot}, made or grown to hold {@code bytes}. */
    private int buffer(int slot, long bytes) {
        if (buffers[slot] == 0) buffers[slot] = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[slot]);
        if (capacity[slot] < bytes) {
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ);
            capacity[slot] = bytes;
        }
        return buffers[slot];
    }

    /**
     * A submitted batch whose pixels are on their way. {@link #collect} it once, on the render thread, before the
     * submission after next.
     */
    final class Submission {
        private final int slot;
        private final List<Tile> tiles;
        /** Per tile, the int offset of its black draw in the buffer; the white one is right of it. */
        private final int[] black;
        private final int[] stride;
        private final long bytes;
        private int passes;
        private boolean collected, stale;

        private Submission(int slot, List<Tile> tiles, int[] black, int[] stride, long bytes) {
            this.slot = slot;
            this.tiles = tiles;
            this.black = black;
            this.stride = stride;
            this.bytes = bytes;
        }

        /** How many targets the batch took: one unless its tiles didn't fit {@value #MAX_SIDE} square. */
        int passes() {
            return passes;
        }

        /**
         * Waits for the pixels and returns each tile's result, in the order the tiles were submitted. The per-tile work
         * runs on the renderer's executor; this returns when all of it is done, and rethrows the first failure.
         */
        List<Matted> collect() {
            RenderSystem.assertOnRenderThread();
            if (collected) throw new IllegalStateException("collected twice");
            if (stale || closed)
                throw new IllegalStateException("its buffer was reused: collect a submission before the one after next");
            collected = true;
            if (tiles.isEmpty()) return List.of();

            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[slot]);
            ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, bytes, GL30.GL_MAP_READ_BIT);
            if (mapped == null) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                throw new IllegalStateException("glMapBufferRange returned nothing");
            }
            IntBuffer pixels = mapped.order(ByteOrder.nativeOrder()).asIntBuffer();
            List<CompletableFuture<Matted>> work = new ArrayList<>(tiles.size());
            try {
                for (int i = 0; i < tiles.size(); i++) {
                    LayerPlan.Box box = tiles.get(i).box();
                    int from = black[i], rowStride = stride[i];
                    int w = box.width() + 2 * RING, h = box.height() + 2 * RING;
                    work.add(CompletableFuture.supplyAsync(() -> {
                        Matte.Result result = Matte.solve(pixels, from, from + w, rowStride, w, h);
                        return new Matted(result.still(), result.escaped(), result.alphaSpread(),
                                StillHash.of(result.still()));
                    }, executor));
                }
            } finally {
                // Every task that started reads the mapped memory, so all of them must be over before it goes away,
                // however they ended.
                CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).handle((ok, failed) -> null).join();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[slot]);
                boolean intact = GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                if (!intact) throw new IllegalStateException("the pixel buffer's contents were lost while mapped");
            }
            List<Matted> results = new ArrayList<>(work.size());
            for (CompletableFuture<Matted> future : work) {
                try {
                    results.add(future.join());
                } catch (CompletionException e) {
                    if (e.getCause() instanceof RuntimeException cause) throw cause;
                    if (e.getCause() instanceof Error cause) throw cause;
                    throw e;
                }
            }
            return results;
        }
    }

    /** Frees the targets and the buffers. Submissions not yet collected can no longer be. */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        for (RenderTarget target : targets.values()) target.destroyBuffers();
        targets.clear();
        for (int slot = 0; slot < 2; slot++) {
            if (buffers[slot] != 0) GL15.glDeleteBuffers(buffers[slot]);
            buffers[slot] = 0;
            capacity[slot] = 0;
        }
    }
}
