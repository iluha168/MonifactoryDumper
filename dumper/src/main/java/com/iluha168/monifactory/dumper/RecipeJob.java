package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.FramePolicy;
import com.iluha168.monifactory.imgencoder.layered.Compositor;
import com.iluha168.monifactory.imgencoder.layered.StillHash;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * One recipe through the layered renderer (DESIGN 3), or drawn whole when that fails (DESIGN 2.5): a state machine
 * the batch calls {@link #step} on until it returns true. A step is one pass or two of drawing, a few milliseconds, so
 * the batch can hand the frame back to the game loop between any two steps, even in the middle of a sequence.
 * <p>
 * <b>Frame 0.</b> The recipe is planned at {@link #BASE_MILLIS} and every layer drawn once in one submission, the
 * recorder watching each draw. A layer that drew on its guard ring gets the whole canvas as its box, and one that
 * blended in a way one draw can't capture (DESIGN 3.2) is drawn over black and white from then on; either is drawn
 * again. Boxes and ways of drawing stay what frame 0 made them for the rest of the recipe, since every still of a
 * layer must be one size. Then the real render is drawn at the same time and atlas state, and the layers composited
 * are compared with it. Layers the recorder saw read only a clock are drawn again at two other times, each with a plan
 * of its own (DESIGN 3.4), and are static if both come out as frame 0 did.
 * <p>
 * <b>Sequence.</b> With any layer left animated, frame after frame gets the atlas ticked (the sprites this recipe has
 * used, {@link SpriteTicks}) and the clock moved by {@link FakeTime#FRAME_MILLIS}, a new plan, and a submission of the
 * animated layers whose {@link LayerLoop} has not decided. Frame {@code k + 1} is submitted before frame {@code k} is
 * collected, so the GPU finishes one while the render thread draws the next: {@link TileRenderer}'s two pixel
 * buffers. The price is that frame {@code k + 1} may draw a layer that frame {@code k} decided, and that picture is
 * dropped. Every {@link #CHECK_EVERY}th frame draws every animated layer and the real render too, and so does the last
 * frame drawn, whose atlas state is still the current one when the sequence ends.
 * <p>
 * <b>Fallback.</b> Any failed check, a layer that is not a plain "over", a layer that escapes its box or blends in a
 * way one draw can't capture after frame 0, a plan whose layers change, or anything thrown, and the layered result is
 * dropped: the recipe is drawn whole from where the atlas stands, watched by the recorder like one layer (probed the
 * same way if it only read a clock), and sequenced with one {@link FramePolicy} over whole frames if it animates. Only
 * a failure there fails the recipe.
 * <p>
 * Nothing leaves the job until it is done: the batch reads {@link #layers()} then, so a recipe that fell back adds no
 * layered stills to the artifact. Render thread only.
 */
final class RecipeJob {
    /** Frame 0's clock. A whole second (DESIGN 3.4), and the census's origin, which is as good as any. */
    static final long BASE_MILLIS = 2_000_000L;
    /** The second clock probe's shift: far, and no multiple of any period a second or a frame would give. */
    static final long FAR_MILLIS = 999_983_777L;
    /** Every how many sequence frames the layers are checked against the real render (DESIGN 3.8). */
    static final int CHECK_EVERY = 16;

    /** What every recipe of a run draws with. */
    record Tools(Minecraft minecraft, TileRenderer tiles, LayerRecorder recorder, RecipeRenderer.Pipeline reference,
                 RecipeRenderer.Pipeline whole, SpriteTicks sprites, Executor checks, Timings timings) {
    }

    /** Render-thread nanoseconds by stage, summed over a run, for the log. */
    static final class Timings {
        /** Building layer plans. */
        long plan;
        /** Drawing tiles and queueing their readback. */
        long submit;
        /** Waiting for tiles' pixels, and their matte and hash on the pool. */
        long collect;
        /** Drawing the real render, waiting for it, compositing and comparing. */
        long reference;
        /** Drawing recipes whole, for fallbacks, less their atlas ticks. */
        long whole;
        /** Ticking the atlas for sequence frames, layered or whole. */
        long atlas;
    }

    enum Mode { LAYERED, FALLBACK, FAILED }

    /**
     * Why a recipe fell back. {@code kind} is one of a few fixed phrases, counted in the log; {@code detail} says
     * where, for {@code render.tsv}.
     */
    record Reason(String kind, String detail) {
        @Override
        public String toString() {
            return detail.isEmpty() ? kind : kind + ": " + detail;
        }
    }

    /**
     * A layer as it goes into the artifact: its box on the canvas and its loop, one still per frame from frame 0.
     * Equal stills are equal hashes; the pictures are the stills' pixels.
     */
    record Stored(LayerPlan.Box box, List<StillHash> hashes, List<Frame> pictures) {
    }

    private enum Phase { FRAME0, SEQUENCE, WHOLE0, WHOLE_SEQUENCE, DONE }

    private final Tools tools;
    private final EmiRecipe recipe;
    private Phase phase = Phase.FRAME0;

    // The layered attempt.
    private LayerPlan plan0;
    private LayerPlan.Box[] boxes;
    /** Per layer, whether it is drawn over black and white rather than once. */
    private boolean[] matte;
    /** The blend states that made each such layer be drawn so, for the log. Kept past a fallback. */
    private final List<Set<BlendState>> matteBlends = new ArrayList<>();
    /** Each layer's frame-0 picture, which is also every later one for a static layer. */
    private TileRenderer.Matted[] first;
    /** Per layer, its loop if it animates, else null. */
    private LayerLoop<StillHash>[] loops;
    private int[] animated;
    private int undecided;
    private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
    private final List<CompletableFuture<Reason>> checks = new ArrayList<>();
    private int nextFrame;
    /** The last frame submitted: its plan, and whether it was checked. */
    private LayerPlan lastPlan;
    private boolean lastChecked;

    // Drawn whole.
    private LayerLoop<Long> whole;
    private int wholeWidth, wholeHeight;

    // What came out.
    private Mode mode;
    private Reason reason;
    private String failure;
    private List<Stored> stored;
    private int framesDrawn, clockLayers, clockStatic;

    /** A submitted sequence frame: which layers it drew, and whether the real render was drawn with it. */
    private record InFlight(int frame, LayerPlan plan, int[] layers, TileRenderer.Submission submission,
                            boolean check) {
    }

    RecipeJob(Tools tools, EmiRecipe recipe) {
        this.tools = tools;
        this.recipe = recipe;
        tools.sprites().recipe();
    }

    /** Does the next piece of work. Returns true once the recipe is done, whichever way. */
    boolean step() {
        switch (phase) {
            case FRAME0, SEQUENCE -> {
                try {
                    if (phase == Phase.FRAME0) frame0();
                    else sequence();
                } catch (RuntimeException | LinkageError e) {
                    LOG.debug("[dumper] {} threw while drawn as layers", recipe.getId(), e);
                    fallBack("threw", oneLine(e));
                }
            }
            case WHOLE0, WHOLE_SEQUENCE -> {
                try {
                    if (phase == Phase.WHOLE0) whole0();
                    else wholeSequence();
                } catch (RuntimeException | LinkageError e) {
                    // The renderer puts its GL state and the clock back on the way out, so the recipes after this one
                    // are unaffected. The batch fails the run once everything else is written.
                    LOG.warn("[dumper] {} failed to render", recipe.getId(), e);
                    tools.whole().discard();
                    failure = "render: " + oneLine(e);
                    mode = Mode.FAILED;
                    phase = Phase.DONE;
                }
            }
            case DONE -> {
            }
        }
        return phase == Phase.DONE;
    }

    // Frame 0.

    @SuppressWarnings("unchecked")
    private void frame0() {
        LayerPlan plan = plan(BASE_MILLIS);
        plan0 = plan;
        int n = plan.layers().size();
        LayerPlan.Box canvas = new LayerPlan.Box(0, 0, plan.width(), plan.height());
        boxes = new LayerPlan.Box[n];
        matte = new boolean[n];
        LayerRecorder.Trace[] traces = new LayerRecorder.Trace[n];
        List<TileRenderer.Tile> tiles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LayerPlan.Layer layer = plan.layers().get(i);
            boxes[i] = layer.box();
            int at = i;
            tiles.add(new TileRenderer.Tile(boxes[i], layer::draw, false, draw -> {
                tools.recorder().start();
                try {
                    draw.run();
                } finally {
                    traces[at] = tools.recorder().stop();
                }
            }));
        }
        TileRenderer.Submission all = submit(plan, tiles);
        // Drawn while the GPU works on the layers. It lays the recipe out afresh, which closes the plan's LDLib UI as
        // every renderRecipe closes the one before it; the layers are drawn by then, and a redraw after it matched.
        startReference(BASE_MILLIS);
        first = collect(all).toArray(TileRenderer.Matted[]::new);

        // Escaped layers get the whole canvas. Its ring lies outside the canvas's viewport, where nothing can draw, so
        // a layer drawn at the whole canvas never escapes: it is clipped exactly as the real render clips it. Layers
        // one draw can't capture are drawn over black and white.
        List<Integer> redraw = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean escaped = first[i].escaped(), blends = !first[i].uncaptured().isEmpty();
            if (escaped) boxes[i] = canvas;
            if (blends) {
                matte[i] = true;
                matteBlends.add(first[i].uncaptured());
            }
            if (escaped || blends) redraw.add(i);
        }
        if (!redraw.isEmpty()) {
            List<TileRenderer.Tile> again = new ArrayList<>(redraw.size());
            for (int i : redraw) again.add(new TileRenderer.Tile(boxes[i], plan.layers().get(i)::draw, matte[i]));
            TileRenderer.Matted[] redrawn = collect(submit(plan, again)).toArray(TileRenderer.Matted[]::new);
            for (int k = 0; k < redraw.size(); k++) first[redraw.get(k)] = redrawn[k];
        }
        for (int i = 0; i < n; i++) {
            if (!usable(first[i], i, 0)) return;
        }

        // Clock-only layers: drawn at two other times, each from a plan of its own, while the real render is on its
        // way back.
        List<Integer> clock = new ArrayList<>();
        boolean[] moves = new boolean[n];
        for (int i = 0; i < n; i++) {
            switch (traces[i].verdict()) {
                case CLOCK -> clock.add(i);
                case ANIMATED -> moves[i] = true;
                case STATIC -> {
                }
            }
        }
        clockLayers = clock.size();
        List<TileRenderer.Submission> probes = new ArrayList<>(2);
        if (!clock.isEmpty()) {
            for (long millis : new long[]{BASE_MILLIS + FakeTime.FRAME_MILLIS, BASE_MILLIS + FAR_MILLIS}) {
                LayerPlan probe = plan(millis);
                if (!probe.sameLayers(plan)) {
                    // Laid out differently at another time: nothing to compare layer for layer, so they all move.
                    for (int i : clock) moves[i] = true;
                    probes.add(null);
                    continue;
                }
                List<TileRenderer.Tile> probeTiles = new ArrayList<>(clock.size());
                for (int i : clock)
                    probeTiles.add(new TileRenderer.Tile(boxes[i], probe.layers().get(i)::draw, matte[i]));
                probes.add(submit(probe, probeTiles));
            }
        }
        check(0, first);
        for (TileRenderer.Submission probe : probes) {
            if (probe == null) continue;
            List<TileRenderer.Matted> drawn = collect(probe);
            for (int k = 0; k < clock.size(); k++) {
                TileRenderer.Matted m = drawn.get(k);
                int i = clock.get(k);
                // A picture that isn't the layer's can't show it static; the sequence will tell what it is.
                if (m.escaped() || !m.uncaptured().isEmpty() || !m.hash().equals(first[i].hash())) moves[i] = true;
            }
        }
        for (int i : clock) if (!moves[i]) clockStatic++;

        List<Integer> moving = new ArrayList<>();
        for (int i = 0; i < n; i++) if (moves[i]) moving.add(i);
        animated = moving.stream().mapToInt(Integer::intValue).toArray();
        if (animated.length == 0) {
            finishLayered();
            return;
        }
        loops = new LayerLoop[n];
        for (int i : animated) {
            loops[i] = new LayerLoop<>();
            TileRenderer.Matted m = first[i];
            loops[i].offer(m.hash(), m::still);
        }
        undecided = animated.length;
        nextFrame = 1;
        lastPlan = plan;
        lastChecked = true;
        phase = Phase.SEQUENCE;
    }

    // The sequence.

    /** Submits the next frame, collects the one before it, and ends the sequence once nothing is left to draw. */
    private void sequence() {
        if (failedCheck(false)) return;
        if (inFlight.size() < 2 && wanted() && !submitFrame()) return;
        if (!inFlight.isEmpty() && (inFlight.size() == 2 || !wanted()) && !collectFrame(inFlight.poll())) return;
        if (inFlight.isEmpty() && !wanted()) lastFrame();
    }

    /** Whether another frame is to be drawn: some loop is open, and the policy's cap is not reached. */
    private boolean wanted() {
        return undecided > 0 && nextFrame < FramePolicy.CAP;
    }

    private boolean submitFrame() {
        int frame = nextFrame++;
        tickAtlas();
        LayerPlan plan = plan(BASE_MILLIS + frame * FakeTime.FRAME_MILLIS);
        if (!plan.sameLayers(plan0)) {
            fallBack("the layers change over time", "frame " + frame);
            return false;
        }
        boolean check = frame % CHECK_EVERY == 0;
        int[] layers = check ? animated : open();
        List<TileRenderer.Tile> tiles = new ArrayList<>(layers.length);
        for (int i : layers) tiles.add(new TileRenderer.Tile(boxes[i], plan.layers().get(i)::draw, matte[i]));
        TileRenderer.Submission submission = submit(plan, tiles);
        // Now, before the next frame ticks the atlas away from this one.
        if (check) startReference(plan.millis());
        inFlight.add(new InFlight(frame, plan, layers, submission, check));
        framesDrawn++;
        lastPlan = plan;
        lastChecked = check;
        return true;
    }

    /** The animated layers whose loop is still open. */
    private int[] open() {
        int[] out = new int[undecided];
        int k = 0;
        for (int i : animated) if (!loops[i].decided()) out[k++] = i;
        return out;
    }

    private boolean collectFrame(InFlight frame) {
        List<TileRenderer.Matted> drawn = collect(frame.submission());
        TileRenderer.Matted[] now = frame.check() ? first.clone() : null;
        for (int k = 0; k < frame.layers().length; k++) {
            int i = frame.layers()[k];
            TileRenderer.Matted m = drawn.get(k);
            if (m.escaped()) {
                fallBack("a layer escapes its box after frame 0", "frame " + frame.frame() + ", " + kind(i));
                return false;
            }
            if (!usable(m, i, frame.frame())) return false;
            if (!loops[i].decided() && loops[i].offer(m.hash(), m::still)) undecided--;
            if (now != null) now[i] = m;
        }
        if (now != null) check(frame.frame(), now);
        return true;
    }

    /**
     * The check of the last frame drawn, at the atlas state it was drawn at: nothing has ticked since. If that frame
     * was a checked one it is done; otherwise every animated layer is drawn again with its plan, and the real render.
     */
    private void lastFrame() {
        if (!lastChecked) {
            List<TileRenderer.Tile> tiles = new ArrayList<>(animated.length);
            for (int i : animated)
                tiles.add(new TileRenderer.Tile(boxes[i], lastPlan.layers().get(i)::draw, matte[i]));
            TileRenderer.Submission submission = submit(lastPlan, tiles);
            startReference(lastPlan.millis());
            List<TileRenderer.Matted> drawn = collect(submission);
            int frame = nextFrame - 1;
            TileRenderer.Matted[] now = first.clone();
            for (int k = 0; k < animated.length; k++) {
                int i = animated[k];
                TileRenderer.Matted m = drawn.get(k);
                if (m.escaped()) {
                    fallBack("a layer escapes its box after frame 0", "frame " + frame + ", " + kind(i));
                    return;
                }
                if (!usable(m, i, frame)) return;
                now[i] = m;
            }
            check(frame, now);
        }
        finishLayered();
    }

    private void finishLayered() {
        if (failedCheck(true)) return;
        List<Stored> out = new ArrayList<>(first.length);
        for (int i = 0; i < first.length; i++) {
            LayerLoop<StillHash> loop = loops == null ? null : loops[i];
            if (loop == null) {
                out.add(new Stored(boxes[i], List.of(first[i].hash()), List.of(first[i].still())));
                continue;
            }
            List<StillHash> hashes = loop.stored();
            out.add(new Stored(boxes[i], hashes, hashes.stream().map(loop::picture).toList()));
        }
        stored = out;
        mode = Mode.LAYERED;
        release();
        phase = Phase.DONE;
    }

    // Checks.

    /**
     * Whether {@code m} is layer {@code i}'s picture and a plain "over"; falls back if not. A layer frame 0 found could
     * be drawn once, but that blends in a way one draw can't capture later on, has no picture of that frame to go on.
     */
    private boolean usable(TileRenderer.Matted m, int i, int frame) {
        if (!m.uncaptured().isEmpty()) {
            fallBack("a layer drawn once blends in a way one draw can't capture", "frame " + frame + ", " + kind(i)
                    + ", " + m.uncaptured());
            return false;
        }
        if (m.alphaSpread() <= Matte.MAX_ALPHA_SPREAD) return true;
        fallBack("a layer is not a plain \"over\"", "frame " + frame + ", " + kind(i) + ", alpha spread "
                + m.alphaSpread());
        return false;
    }

    private void startReference(long millis) {
        long start = System.nanoTime();
        RecipeRenderer.Readback stale = tools.reference().draw(tools.minecraft(), recipe, millis);
        tools.timings().reference += System.nanoTime() - start;
        if (stale != null) throw new IllegalStateException("a real render was left unread");
    }

    /**
     * Starts the check of this frame: {@code layers}, one picture per layer at its box, composited and compared with
     * the real render {@link #startReference} drew for it. The render thread only waits for the real render's pixels;
     * compositing and comparing run on {@link Tools#checks}, and {@link #failedCheck} reads the verdicts.
     */
    private void check(int frame, TileRenderer.Matted[] layers) {
        long start = System.nanoTime();
        try {
            RecipeRenderer.Readback real = tools.reference().finish();
            if (real == null) throw new IllegalStateException("no real render to check frame " + frame + " against");
            int width = plan0.width(), height = plan0.height();
            if (real.width != width || real.height != height)
                throw new IllegalStateException("the real render is " + real.width + "x" + real.height + ", the plan "
                        + width + "x" + height);
            // The readback is the renderer's to reuse on its next draw, so the check gets a copy.
            int[] pixels = Arrays.copyOf(real.pixels, real.size());
            List<Compositor.Placed> placed = new ArrayList<>(layers.length);
            for (int i = 0; i < layers.length; i++)
                placed.add(new Compositor.Placed(boxes[i].x(), boxes[i].y(), layers[i].still()));
            checks.add(CompletableFuture.supplyAsync(() -> {
                Frame composite = Compositor.composite(width, height, placed);
                Reconstruction.Difference difference = Reconstruction.compare(pixels, composite);
                if (difference.faithful()) return null;
                return new Reason("differs from the real render", "frame " + frame + ", " + difference.pixels()
                        + " pixels off, by up to " + difference.worst());
            }, tools.checks()));
        } finally {
            tools.timings().reference += System.nanoTime() - start;
        }
    }

    /**
     * Falls back if a check that has finished failed, and returns true then. With {@code wait}, waits for every check
     * still running first. A check that threw throws here.
     */
    private boolean failedCheck(boolean wait) {
        long start = System.nanoTime();
        try {
            for (Iterator<CompletableFuture<Reason>> it = checks.iterator(); it.hasNext(); ) {
                CompletableFuture<Reason> check = it.next();
                if (!wait && !check.isDone()) continue;
                Reason failed;
                try {
                    failed = check.join();
                } catch (CompletionException e) {
                    if (e.getCause() instanceof RuntimeException cause) throw cause;
                    if (e.getCause() instanceof Error cause) throw cause;
                    throw e;
                }
                it.remove();
                if (failed != null) {
                    fallBack(failed.kind(), failed.detail());
                    return true;
                }
            }
            return false;
        } finally {
            tools.timings().reference += System.nanoTime() - start;
        }
    }

    /** Drops the layered attempt and draws the recipe whole instead. */
    private void fallBack(String kind, String detail) {
        reason = new Reason(kind, detail);
        release();
        // What is counted from here on is the whole recipe's.
        animated = null;
        framesDrawn = 0;
        nextFrame = 0;
        clockLayers = 0;
        clockStatic = 0;
        phase = Phase.WHOLE0;
    }

    /**
     * Lets go of the layered attempt: frames in flight are never collected (the renderer lets a submission go
     * uncollected), a real render in flight is forgotten, and the plans go with their widgets.
     */
    private void release() {
        inFlight.clear();
        // Checks still running finish on their own; nobody reads them.
        checks.clear();
        tools.reference().discard();
        plan0 = null;
        lastPlan = null;
        first = null;
        loops = null;
    }

    // Drawn whole.

    private void whole0() {
        long start = System.nanoTime();
        try {
            RecipeRenderer.Pipeline pipeline = tools.whole();
            pipeline.discard();
            LayerRecorder.Trace[] trace = new LayerRecorder.Trace[1];
            pipeline.draw(tools.minecraft(), recipe, BASE_MILLIS, draw -> {
                tools.recorder().start();
                try {
                    draw.run();
                } finally {
                    trace[0] = tools.recorder().stop();
                }
            });
            RecipeRenderer.Readback frame0 = pipeline.finish();
            wholeWidth = frame0.width;
            wholeHeight = frame0.height;
            long hash0 = RecipeRenderer.fastHash(frame0.pixels, frame0.size());
            Frame picture0 = frame0.frame();
            whole = new LayerLoop<>();
            whole.offer(hash0, () -> picture0);
            boolean moves = trace[0].verdict() == LayerRecorder.Verdict.ANIMATED;
            if (trace[0].verdict() == LayerRecorder.Verdict.CLOCK) {
                for (long millis : new long[]{BASE_MILLIS + FakeTime.FRAME_MILLIS, BASE_MILLIS + FAR_MILLIS}) {
                    pipeline.draw(tools.minecraft(), recipe, millis);
                    RecipeRenderer.Readback probe = pipeline.finish();
                    moves |= RecipeRenderer.fastHash(probe.pixels, probe.size()) != hash0;
                }
            }
            if (!moves) {
                finishWhole(List.of(hash0));
                return;
            }
            nextFrame = 1;
            phase = Phase.WHOLE_SEQUENCE;
        } finally {
            tools.timings().whole += System.nanoTime() - start;
        }
    }

    /**
     * One frame of the whole recipe's sequence. Frame {@code k}'s pixels come back while frame {@code k + 1} is
     * drawn, so the loop decides one draw late, and that last draw is thrown away.
     */
    private void wholeSequence() {
        long start = System.nanoTime(), atlas = tools.timings().atlas;
        try {
            RecipeRenderer.Readback frame;
            if (nextFrame < FramePolicy.CAP) {
                tickAtlas();
                frame = tools.whole().draw(tools.minecraft(), recipe,
                        BASE_MILLIS + nextFrame * FakeTime.FRAME_MILLIS);
                nextFrame++;
                framesDrawn++;
            } else {
                frame = tools.whole().finish();
            }
            if (frame == null) return;
            long hash = RecipeRenderer.fastHash(frame.pixels, frame.size());
            if (!whole.offer(hash, frame::frame)) return;
            tools.whole().discard();
            finishWhole(whole.stored());
        } finally {
            tools.timings().whole += System.nanoTime() - start - (tools.timings().atlas - atlas);
        }
    }

    private void finishWhole(List<Long> keys) {
        Map<Long, StillHash> hashes = new HashMap<>();
        List<StillHash> stills = new ArrayList<>(keys.size());
        List<Frame> pictures = new ArrayList<>(keys.size());
        for (long key : keys) {
            Frame picture = whole.picture(key);
            stills.add(hashes.computeIfAbsent(key, k -> StillHash.of(picture)));
            pictures.add(picture);
        }
        stored = List.of(new Stored(new LayerPlan.Box(0, 0, wholeWidth, wholeHeight), stills, pictures));
        whole = null;
        mode = Mode.FALLBACK;
        phase = Phase.DONE;
    }

    // Plumbing.

    private LayerPlan plan(long millis) {
        long start = System.nanoTime();
        try {
            return LayerPlan.build(tools.minecraft(), recipe, millis);
        } finally {
            tools.timings().plan += System.nanoTime() - start;
        }
    }

    private TileRenderer.Submission submit(LayerPlan plan, List<TileRenderer.Tile> tiles) {
        long start = System.nanoTime();
        try {
            return DrawTime.get(tools.minecraft(), plan.millis(), () -> tools.tiles().submit(plan, tiles));
        } finally {
            tools.timings().submit += System.nanoTime() - start;
        }
    }

    private List<TileRenderer.Matted> collect(TileRenderer.Submission submission) {
        long start = System.nanoTime();
        try {
            return submission.collect();
        } finally {
            tools.timings().collect += System.nanoTime() - start;
        }
    }

    private void tickAtlas() {
        long start = System.nanoTime();
        tools.sprites().tick();
        tools.timings().atlas += System.nanoTime() - start;
    }

    private String kind(int layer) {
        return "layer " + layer + " (" + plan0.layers().get(layer).kind() + ")";
    }

    private static String oneLine(Throwable t) {
        return t.toString().replaceAll("\\s+", " ");
    }

    // What came out, once done.

    Mode mode() {
        return mode;
    }

    /** Why the recipe was drawn whole, or null if it was not. */
    Reason reason() {
        return reason;
    }

    /** Why the recipe has no picture, for a failed one. */
    String failure() {
        return failure;
    }

    /** The picture's layers in draw order, layer 0 first; null for a failed recipe. */
    List<Stored> layers() {
        return stored;
    }

    int width() {
        return stored.get(0).box().width();
    }

    int height() {
        return stored.get(0).box().height();
    }

    /** Layers the recorder or the probes called animated: the ones sequenced. For a recipe drawn whole, 0 or 1. */
    int animatedLayers() {
        return switch (mode) {
            case LAYERED -> animated.length;
            case FALLBACK -> nextFrame > 0 ? 1 : 0;
            case FAILED -> 0;
        };
    }

    /** Sequence frames submitted after frame 0, for whichever way the recipe was last drawn. */
    int framesDrawn() {
        return framesDrawn;
    }

    /** Layers whose only mark was a clock read, and of those the ones the probes proved static. */
    int clockLayers() {
        return clockLayers;
    }

    int clockStatic() {
        return clockStatic;
    }

    /**
     * Per layer frame 0 had drawn over black and white, the blend states one draw can't capture that it used; also
     * for a recipe that fell back after.
     */
    List<Set<BlendState>> matteBlends() {
        return matteBlends;
    }
}
