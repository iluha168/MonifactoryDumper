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
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Draws a recipe's layers as tiles of one offscreen target and hands back each layer's straight-alpha picture
 * (DESIGN 3.5).
 * <p>
 * A tile is a crop box of the recipe's canvas and something that draws. It is drawn twice, over opaque black and over
 * opaque white, each into its own region of the target: the box plus a 1-pixel guard ring on every side, cleared on
 * its own. What lands on the ring is how {@link Matte} tells that a layer escaped its box. Tiles are shelf-packed
 * ({@link TilePacking}) into power-of-two targets up to {@value #MAX_SIDE} square, several passes when they don't fit
 * one, and each pass is read back once, only the rectangle it used.
 * <p>
 * Each draw goes to a scratch target the size of the canvas, with exactly the target size, viewport and transform
 * {@link RecipeRenderer} draws the whole recipe with, and then its box and ring are copied to the tile's region. So
 * every vertex is computed by the same arithmetic as in the real render and lands on the same pixel. Mapping only the
 * box onto a region-sized viewport, or moving a canvas-sized viewport onto the region, both round differently in the
 * last bits, and that flipped single pixels where an edge runs exactly through a pixel's centre, as the corners of a
 * block item drawn in 3D do: one pixel off in about one recipe in thirty. The ring outside the canvas is outside the
 * scratch target too, clipped as the real render clips it, and holds the clear colour. And since a region is copied
 * right after its own draw and cleared before it, a layer that turns the scissor off or moves the viewport can only
 * spoil its own picture, which the check against the real render then catches, never another tile's.
 * <p>
 * The readback goes into a pixel buffer object, so {@link #submit} returns once the GL has the copy queued, and the
 * pixels are only waited for in {@link Submission#collect}. Two buffers take turns: the caller can submit the next
 * batch before collecting this one, and the GPU finishes one while the render thread draws the other. Collecting maps
 * the buffer and runs the alpha solve, the ring check and the hash per tile straight out of the mapped memory: big
 * tiles on the caller's executor, small ones on the render thread meanwhile. The pixels never pass through a Java
 * array: {@code glReadPixels} into one held a JNI critical section through the whole GPU wait while other threads
 * allocated, and sync plus readback cost ten times as much (REPORT 8, trap 3).
 * <p>
 * Ordering. Submissions alternate between the two buffers, so submission N's buffer is written again by submission
 * N + 2. Collect N before submitting N + 2; a submission overwritten first throws when collected. Collect each
 * submission once. What {@code collect} returns is the caller's: the buffer is unmapped before it returns, and nothing
 * in the results points into it, so they stay valid through any later submit. Everything here is render thread only,
 * {@code collect} included, since it maps and unmaps a GL buffer; only the per-tile work runs on the executor, and
 * {@code collect} returns when all of it has.
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
    /** Tiles at least this many pixels, ring included, are solved on the executor; smaller ones on the caller. */
    private static final int POOL_PIXELS = 16_384;

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

        BlendDepth baseline = BlendDepth.read();
        for (int p = 0; p < passOffsets.length; p++) {
            drawPass(plan, tiles, layout, p, slot, passOffsets[p], bytes, baseline);
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

    /**
     * Draws pass {@code p}'s tiles into its target and reads the used rectangle into buffer {@code slot}: every black
     * draw in submission order, then every white one, each run starting from {@code baseline}. So a layer's two draws
     * both start from the state the layer before it left, as its one draw in the real render does, and nothing has to
     * be put back between them.
     */
    private void drawPass(LayerPlan plan, List<Tile> tiles, TilePacking.Layout layout, int p, int slot, int offset,
                          long bytes, BlendDepth baseline) {
        TilePacking.Pass pass = layout.passes().get(p);
        RenderTarget target = targets.computeIfAbsent(
                (long) slot << 62 | (long) pass.targetWidth() << 31 | pass.targetHeight(),
                key -> new TextureTarget(pass.targetWidth(), pass.targetHeight(), true, Minecraft.ON_OSX));
        RenderTarget canvas = targets.computeIfAbsent(2L << 62 | (long) plan.width() << 31 | plan.height(),
                key -> new TextureTarget(plan.width(), plan.height(), true, Minecraft.ON_OSX));

        PoseStack view = RenderSystem.getModelViewStack();
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        view.pushPose();
        // One layer that throws must not leave the model-view stack, the projection, the viewport or the target
        // behind for every draw after it.
        try {
            RenderSystem.setProjectionMatrix(new Matrix4f().identity(), VertexSorting.ORTHOGRAPHIC_Z);
            for (int white = 0; white < 2; white++) {
                baseline.apply();
                for (int i = 0; i < tiles.size(); i++) {
                    TilePacking.Place place = layout.places().get(i);
                    if (place.pass() != p) continue;
                    Tile tile = tiles.get(i);
                    int x = place.x() + white * (tile.box().width() + 2 * RING);
                    float grey = white;
                    Runnable draw = () -> drawTile(canvas, target, plan, tile, x, place.y(), grey);
                    if (white == 1 || tile.black() == null) draw.run();
                    else tile.black().around(draw);
                }
            }
            // The readback is of the pass's target, whatever a layer bound last.
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
     * One draw of {@code tile} over grey level {@code grey}: into {@code canvas}, a target the size of the recipe's
     * canvas, exactly as {@link RecipeRenderer} draws the whole recipe, then its box and ring copied to the region at
     * ({@code x}, {@code y}) from {@code atlas}'s bottom left. Everything a layer could have changed on its way is set
     * again, per draw: the framebuffer, the viewport, the scissor and the model-view.
     */
    private void drawTile(RenderTarget canvas, RenderTarget atlas, LayerPlan plan, Tile tile, int x, int y,
                          float grey) {
        LayerPlan.Box box = tile.box();
        int w = box.width() + 2 * RING, h = box.height() + 2 * RING;
        // The region on the canvas, ring included, in GL's rows from the bottom; and the part of it on the canvas.
        int left = box.x() - RING, bottom = plan.height() - box.y() - box.height() - RING;
        int x0 = Math.max(0, left), y0 = Math.max(0, bottom);
        int x1 = Math.min(plan.width(), left + w), y1 = Math.min(plan.height(), bottom + h);
        if (x0 > left || y0 > bottom || x1 < left + w || y1 < bottom + h) {
            // The ring runs off the canvas, where nothing is drawn or copied: it has to hold the clear colour.
            atlas.bindWrite(false);
            clear(x, y, w, h, grey, false);
        }

        canvas.bindWrite(false);
        RenderSystem.viewport(0, 0, plan.width(), plan.height());
        clear(x0, y0, x1 - x0, y1 - y0, grey, true);
        PoseStack view = RenderSystem.getModelViewStack();
        view.setIdentity();
        view.translate(-1.0, 1.0, 0.0);
        view.scale(2f / plan.guiWidth(), -2f / plan.guiHeight(), -1f / 1000f);
        view.translate(0.0, 0.0, 10.0);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        tile.body().accept(graphics);
        graphics.flush();

        // A blit is clipped by the scissor, and a layer may have left one on.
        RenderSystem.disableScissor();
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, canvas.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, atlas.frameBufferId);
        withMasks(() -> GlStateManager._glBlitFrameBuffer(x0, y0, x1, y1, x + x0 - left, y + y0 - bottom,
                x + x1 - left, y + y1 - bottom, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST));
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** Clears a rectangle of the bound framebuffer to {@code grey}, alpha 1, and depth to 1 if {@code depth}. */
    private static void clear(int x, int y, int w, int h, float grey, boolean depth) {
        RenderSystem.enableScissor(x, y, w, h);
        RenderSystem.clearColor(grey, grey, grey, 1f);
        RenderSystem.clearDepth(1.0);
        withMasks(() -> RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | (depth ? GL11.GL_DEPTH_BUFFER_BIT : 0),
                Minecraft.ON_OSX));
        RenderSystem.disableScissor();
    }

    /**
     * Runs {@code body}, a clear or a copy, with every colour channel and depth writable, as it needs, then puts back
     * the masks a layer left, which the next layer's draw starts from.
     */
    private static void withMasks(Runnable body) {
        boolean[] colour = new boolean[4];
        boolean depth;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int c = 0; c < 4; c++) colour[c] = mask.get(c) != 0;
            GL11.glGetBooleanv(GL11.GL_DEPTH_WRITEMASK, mask);
            depth = mask.get(0) != 0;
        }
        boolean all = colour[0] && colour[1] && colour[2] && colour[3];
        if (!all) RenderSystem.colorMask(true, true, true, true);
        if (!depth) RenderSystem.depthMask(true);
        body.run();
        if (!all) RenderSystem.colorMask(colour[0], colour[1], colour[2], colour[3]);
        if (!depth) RenderSystem.depthMask(false);
    }

    /**
     * The GL state a layer's draw blends and tests with: blending, its functions and equation, the depth test, depth
     * writes and function, face culling and the colour mask. Read from the GL, set back through {@link RenderSystem}
     * so its cache agrees.
     */
    private record BlendDepth(boolean blend, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, int equation,
                              boolean depthTest, boolean depthMask, int depthFunc, boolean cull, int colorMask) {
        static BlendDepth read() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer one = stack.mallocInt(1);
                ByteBuffer mask = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                int colorMask = (mask.get(0) != 0 ? 8 : 0) | (mask.get(1) != 0 ? 4 : 0) | (mask.get(2) != 0 ? 2 : 0)
                        | (mask.get(3) != 0 ? 1 : 0);
                GL11.glGetBooleanv(GL11.GL_DEPTH_WRITEMASK, mask);
                boolean depthMask = mask.get(0) != 0;
                return new BlendDepth(GL11.glIsEnabled(GL11.GL_BLEND), integer(GL14.GL_BLEND_SRC_RGB, one),
                        integer(GL14.GL_BLEND_DST_RGB, one), integer(GL14.GL_BLEND_SRC_ALPHA, one),
                        integer(GL14.GL_BLEND_DST_ALPHA, one), integer(GL20.GL_BLEND_EQUATION_RGB, one),
                        GL11.glIsEnabled(GL11.GL_DEPTH_TEST), depthMask, integer(GL11.GL_DEPTH_FUNC, one),
                        GL11.glIsEnabled(GL11.GL_CULL_FACE), colorMask);
            }
        }

        private static int integer(int name, IntBuffer into) {
            GL11.glGetIntegerv(name, into);
            return into.get(0);
        }

        void apply() {
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            RenderSystem.blendEquation(equation);
            if (depthTest) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            RenderSystem.depthMask(depthMask);
            RenderSystem.depthFunc(depthFunc);
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            RenderSystem.colorMask((colorMask & 8) != 0, (colorMask & 4) != 0, (colorMask & 2) != 0,
                    (colorMask & 1) != 0);
        }
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
            List<CompletableFuture<Matted>> work = new ArrayList<>(Collections.nCopies(tiles.size(), null));
            try {
                // Big tiles go to the pool first; the render thread does the small ones meanwhile. Handing a tile of a
                // few thousand pixels to a sleeping thread costs more than solving it.
                for (int small = 0; small < 2; small++) {
                    for (int i = 0; i < tiles.size(); i++) {
                        LayerPlan.Box box = tiles.get(i).box();
                        int from = black[i], rowStride = stride[i];
                        int w = box.width() + 2 * RING, h = box.height() + 2 * RING;
                        if ((w * h < POOL_PIXELS) != (small == 1)) continue;
                        Supplier<Matted> solve = () -> {
                            Matte.Result result = Matte.solve(pixels, from, from + w, rowStride, w, h);
                            return new Matted(result.still(), result.escaped(), result.alphaSpread(),
                                    StillHash.of(result.still()));
                        };
                        if (small == 0) {
                            work.set(i, CompletableFuture.supplyAsync(solve, executor));
                            continue;
                        }
                        try {
                            work.set(i, CompletableFuture.completedFuture(solve.get()));
                        } catch (RuntimeException | Error e) {
                            work.set(i, CompletableFuture.failedFuture(e));
                        }
                    }
                }
            } finally {
                work.removeIf(Objects::isNull);
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
