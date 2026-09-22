package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.FramePolicy;
import com.iluha168.monifactory.imgencoder.PakEntry;
import com.iluha168.monifactory.imgencoder.PakWriter;
import com.iluha168.monifactory.imgencoder.WebpEncoder;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * The build: every kept recipe drawn and encoded into {@code images.pak}, and {@code recipes.json} written with each
 * recipe's frame count, length and offset.
 * <p>
 * Static or animated is the probe ladder of PLAN section 4: frame 0 against frames 53, 97 and 199, one frame being one
 * atlas tick and 50 ms of the fake clock. A recipe is static only if all three match frame 0, and then the last probe
 * is the image stored. No probe is a multiple of 40, the GT progress arrow's period, whose plateaus would otherwise
 * match frame 0 by coincidence.
 * <p>
 * The atlas ticker is global and cannot be rewound, so recipes go in chunks, one pass over the chunk per frame of the
 * ladder: two warm-up passes for first-use sprite uploads, frame 0, then the atlas ticked forward to each probe in turn.
 * That is 199 atlas ticks per chunk instead of per recipe. A pass may span several game frames; nothing ticks the atlas
 * between them, since the renderer holds it.
 * <p>
 * Then each animated recipe of the chunk, in record order, gets a sequence of its own: frame after frame, the atlas
 * ticked and the clock moved 50 ms before each one, until {@link FramePolicy} has seen its loop close or
 * {@link FramePolicy#CAP} frames go by. The sequence starts wherever the atlas stands, which only picks the phase the
 * stored loop starts at; the ticker is global, so every recipe starts at an arbitrary phase anyway. Frames are hashed
 * as they are drawn and kept only up to {@link FramePolicy#MAX_STORED}, each distinct picture once.
 * <p>
 * Encoding is lossless WebP at method 6, stills and animations alike, on worker threads with an encoder each, while the
 * game thread keeps drawing. Payloads are appended to the pak in record order, so the file's layout depends on the
 * corpus and nothing else. The game thread stops drawing whenever the pictures waiting for an encoder add up to
 * {@link #BUFFER_PROPERTY}.
 */
final class Batch {
    static final int[] PROBES = {53, 97, 199};
    private static final int WARM_UP = 2;
    /** Warm-up passes, frame 0, then one pass per probe. The sequences come after them, as pass {@code PASSES}. */
    private static final int PASSES = WARM_UP + 1 + PROBES.length;
    private static final int CHUNK = 1024;
    private static final long FRAME_BUDGET_NANOS = 250_000_000L;
    /** Where the fake clock stands at frame 0. The census's origin, which is as good as any. */
    private static final long BASE_MILLIS = 2_000_000L;
    /** Worker threads encoding, by default every core but the game thread's and one to spare. */
    static final String ENCODERS_PROPERTY = "monifactory.dumper.encoders";
    /** How many MiB of pictures may wait for an encoder before drawing pauses. Default 1024. */
    static final String BUFFER_PROPERTY = "monifactory.dumper.encodeBufferMiB";
    /**
     * Every how many animated recipes one also has its source frames written out as PNGs next to its WebP, under
     * {@code frames/<record index>/}, so {@code ignored/enc/verify.py} can check the file against what was drawn.
     * Default 0, none.
     */
    static final String KEEP_FRAMES_PROPERTY = "monifactory.dumper.keepFramesEvery";
    /**
     * Renders only every how many-th kept recipe, in corpus order: a partial artifact that is a systematic sample of
     * the whole. The corpus is grouped by category, so the sample is spread over every category in proportion to its
     * size, and its totals times this factor estimate the full build's in a fraction of the hours. Default 1, all.
     */
    static final String EVERY_PROPERTY = "monifactory.dumper.every";

    private enum State { PENDING, ANIMATED, STATIC, FAILED }

    private final Minecraft minecraft;
    private final Path output;
    private final List<Corpus.Entry> entries;
    private final State[] states;
    private final long[] firstHashes;
    /** The probe frame that first differed from frame 0, for animated recipes. */
    private final int[] animatedAt;
    /** Animated recipes: the strict period, or -1 if trimmed; frames drawn to decide it; distinct pictures kept. */
    private final int[] periods, sequenceDraws, distinctFrames;
    private final String[] failures;
    private final RecipeJson.Image[] images;
    /** Each record's payload from when it is queued until it is written; null otherwise. */
    private final Future<byte[]>[] payloads;
    /** Frames each record stores, set as its encode is queued: 1 for a still. 0 until then. */
    private final int[] storedFrames;
    private final ExecutorService encoders;
    private final int threads;
    private final PakWriter pak;
    private final long bufferBytes;
    private final int keepFramesEvery;
    private int animatedQueued;
    /** Bytes of pictures handed to an encoder and not yet encoded. */
    private final AtomicLong waitingBytes = new AtomicLong();

    private int chunkStart;
    private int pass;
    private int cursor;
    /** Atlas ticks since the current chunk's frame 0. The clock stands at {@code BASE_MILLIS + tick * 50}. */
    private int tick;
    /** Every record before this one is either written to the pak or will never be. */
    private int written;
    private boolean finished;

    /** The animated recipe being sequenced, or -1. */
    private int sequencing = -1;
    private FramePolicy policy;
    /** Frames of the current sequence by index, up to {@link FramePolicy#MAX_STORED}; equal pictures share a Frame. */
    private final List<Frame> kept = new ArrayList<>();
    private final Map<Long, Frame> keptByHash = new HashMap<>();
    private final RecipeRenderer.Pipeline pipeline = new RecipeRenderer.Pipeline();
    /** Frames of the current sequence drawn so far, which runs one ahead of the frames the policy has seen. */
    private int sequenceDrawn;

    private final long started = System.nanoTime();
    private long drawNanos, draws, sequenceNanos, sequenceFrames, stalls;
    private final AtomicLong encodeNanos = new AtomicLong(), animatedEncodeNanos = new AtomicLong();
    private final AtomicInteger encodeCount = new AtomicInteger();

    @SuppressWarnings("unchecked")
    private Batch(Minecraft minecraft, Path output, List<Corpus.Entry> entries, int threads, long bufferBytes)
            throws IOException {
        this.minecraft = minecraft;
        this.output = output;
        this.entries = entries;
        int n = entries.size();
        this.states = new State[n];
        Arrays.fill(states, State.PENDING);
        this.firstHashes = new long[n];
        this.animatedAt = new int[n];
        this.periods = new int[n];
        this.sequenceDraws = new int[n];
        this.distinctFrames = new int[n];
        this.failures = new String[n];
        this.images = new RecipeJson.Image[n];
        this.payloads = new Future[n];
        this.storedFrames = new int[n];
        this.pak = PakWriter.create(output.resolve("images.pak"));
        this.threads = threads;
        this.bufferBytes = bufferBytes;
        this.keepFramesEvery = Integer.getInteger(KEEP_FRAMES_PROPERTY, 0);
        this.encoders = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger();

            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "dumper-encoder-" + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private static final ThreadLocal<WebpEncoder> ENCODER = ThreadLocal.withInitial(WebpEncoder::new);

    /**
     * Starts the build over the whole kept corpus, or its first {@code limit} recipes if that is fewer (a partial
     * artifact, for trying the build out).
     */
    static Batch start(Minecraft minecraft, Corpus corpus, Path output, int limit) throws IOException {
        Files.createDirectories(output);
        corpus.writeCategories(output.resolve("categories.tsv"));
        List<Corpus.Entry> entries = corpus.kept;
        int every = Integer.getInteger(EVERY_PROPERTY, 1);
        if (every < 1) throw new IllegalArgumentException(EVERY_PROPERTY + " must be at least 1, not " + every);
        if (every > 1) {
            List<Corpus.Entry> sample = new ArrayList<>(entries.size() / every + 1);
            for (int i = 0; i < entries.size(); i += every) sample.add(entries.get(i));
            LOG.warn("[dumper] rendering every {}th of {} kept recipes, {} in all; the artifact is a sample", every,
                    entries.size(), sample.size());
            entries = sample;
        }
        if (limit < entries.size()) {
            LOG.warn("[dumper] rendering only the first {} of {} kept recipes; the artifact is partial", limit,
                    entries.size());
            entries = entries.subList(0, limit);
        }
        int threads = Integer.getInteger(ENCODERS_PROPERTY, Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        long buffer = Long.getLong(BUFFER_PROPERTY, 1024L) << 20;
        LOG.info("[dumper] batch: {} recipes in chunks of {}, probes {} after {} warm-up draws, then strict periods up"
                        + " to {} frames (trim to {}); {} encoder threads, {} MiB encode buffer", entries.size(), CHUNK,
                Arrays.toString(PROBES), WARM_UP, FramePolicy.CAP, FramePolicy.TRIM, threads, buffer >> 20);
        return new Batch(minecraft, output, entries, threads, buffer);
    }

    /** Works for one frame's budget. Returns true once {@code images.pak} and {@code recipes.json} are written. */
    boolean advance() throws IOException {
        long start = System.nanoTime();
        while (System.nanoTime() - start < FRAME_BUDGET_NANOS) {
            drain();
            if (chunkStart == entries.size()) {
                if (written < entries.size()) {
                    // Only encoders left to wait for. Waiting here would stall the game loop for no gain.
                    return false;
                }
                finish();
                return true;
            }
            int chunkEnd = Math.min(chunkStart + CHUNK, entries.size());
            if (pass == PASSES) {
                if (!sequence(chunkEnd)) return false;
                continue;
            }
            if (cursor == chunkStart && pass > WARM_UP) {
                // A probe pass starts: move the atlas to its frame.
                int to = PROBES[pass - WARM_UP - 1];
                while (tick < to) tickAtlas();
            }
            if (pass == PASSES - 1 && waitingBytes.get() >= bufferBytes) {
                stalls++;
                return false;
            }
            probe(cursor);
            if (++cursor == chunkEnd) {
                cursor = chunkStart;
                pass++;
            }
        }
        return false;
    }

    private void tickAtlas() {
        FakeTime.tickAtlas(minecraft.getTextureManager()::tick);
        tick++;
    }

    /** One draw of record {@code index} in the current ladder pass, and whatever it decides. */
    private void probe(int index) {
        if (states[index] != State.PENDING) return;
        int frame = pass <= WARM_UP ? 0 : PROBES[pass - WARM_UP - 1];
        RecipeRenderer.Readback image = draw(index, frame);
        if (image == null || pass < WARM_UP) return;
        long hash = RecipeRenderer.fastHash(image.pixels, image.size());
        if (pass == WARM_UP) {
            firstHashes[index] = hash;
            return;
        }
        if (hash != firstHashes[index]) {
            states[index] = State.ANIMATED;
            animatedAt[index] = frame;
            return;
        }
        if (pass < PASSES - 1) return;
        states[index] = State.STATIC;
        storedFrames[index] = 1;
        submit(index, List.of(image.frame()));
    }

    /** Draws record {@code index} at {@code frame} of the clock; null, with the record failed, if the draw throws. */
    private RecipeRenderer.Readback draw(int index, int frame) {
        long drawStart = System.nanoTime();
        try {
            return RecipeRenderer.draw(minecraft, entries.get(index).recipe(), BASE_MILLIS + frame * FakeTime.FRAME_MILLIS);
        } catch (RuntimeException | LinkageError e) {
            // Recorded, and the build fails once everything else is written. The renderer restores its GL and clock
            // state on the way out, so the recipes after this one are unaffected.
            Corpus.Entry entry = entries.get(index);
            LOG.warn("[dumper] {} ({}) failed to render", entry.recipe().getId(), entry.category(), e);
            failures[index] = "render: " + e.toString().replaceAll("\\s+", " ");
            states[index] = State.FAILED;
            return null;
        } finally {
            drawNanos += System.nanoTime() - drawStart;
            draws++;
        }
    }

    /**
     * One frame of the chunk's current animated recipe, starting the next one first if none is running. Returns false
     * to give the frame back while the encoders catch up; moves on to the next chunk once every animated recipe in
     * this one is queued.
     */
    private boolean sequence(int chunkEnd) {
        if (sequencing < 0) {
            int next = chunkStart;
            while (next < chunkEnd && (states[next] != State.ANIMATED || storedFrames[next] != 0)) next++;
            if (next == chunkEnd) {
                pass = 0;
                tick = 0;
                chunkStart = chunkEnd;
                cursor = chunkStart;
                progress();
                return true;
            }
            // The record the pak is waiting on always goes ahead, or the queue behind it could never drain.
            if (waitingBytes.get() >= bufferBytes && next != written) {
                stalls++;
                return false;
            }
            sequencing = next;
            policy = new FramePolicy();
        }

        int index = sequencing;
        long frameStart = System.nanoTime();
        // Frame k's pixels come back while frame k+1 is drawn, so the policy decides one draw late and the last frame
        // drawn is thrown away: one draw a recipe, for the readback overlapping the draw on all the others.
        if (sequenceDrawn > 0) tickAtlas();
        RecipeRenderer.Readback image;
        long drawStart = System.nanoTime();
        try {
            image = pipeline.draw(minecraft, entries.get(index).recipe(), BASE_MILLIS + tick * FakeTime.FRAME_MILLIS);
        } catch (RuntimeException | LinkageError e) {
            pipeline.discard();
            Corpus.Entry entry = entries.get(index);
            LOG.warn("[dumper] {} ({}) failed to render", entry.recipe().getId(), entry.category(), e);
            failures[index] = "render: " + e.toString().replaceAll("\\s+", " ");
            states[index] = State.FAILED;
            endSequence();
            return true;
        } finally {
            drawNanos += System.nanoTime() - drawStart;
            draws++;
        }
        sequenceDrawn++;
        sequenceFrames++;
        if (image == null) return true;
        long hash = RecipeRenderer.fastHash(image.pixels, image.size());
        if (policy.frames() < FramePolicy.MAX_STORED) {
            kept.add(keptByHash.computeIfAbsent(hash, h -> image.frame()));
        }
        boolean decided = policy.offer(hash);
        sequenceNanos += System.nanoTime() - frameStart;
        if (!decided) return true;
        pipeline.discard();

        periods[index] = policy.period();
        sequenceDraws[index] = policy.frames();
        List<Frame> frames = List.copyOf(kept.subList(0, policy.stored()));
        // Equal pictures share one Frame. If the stored frames are all one picture (the probes caught a change that the
        // stored span does not show), the file is a still, and the record says so.
        Frame first = frames.get(0);
        if (frames.stream().allMatch(frame -> frame == first)) {
            frames = List.of(first);
        }
        storedFrames[index] = frames.size();
        distinctFrames[index] = submit(index, frames);
        endSequence();
        return true;
    }

    private void endSequence() {
        sequencing = -1;
        sequenceDrawn = 0;
        policy = null;
        kept.clear();
        keptByHash.clear();
    }

    /**
     * Queues the encode of record {@code index}'s frames and returns how many distinct pictures they are. Pictures
     * shared between frames are counted against the buffer once.
     */
    private int submit(int index, List<Frame> frames) {
        Map<Frame, Boolean> distinct = new IdentityHashMap<>();
        long bytes = 0;
        for (Frame frame : frames) {
            if (distinct.put(frame, Boolean.TRUE) == null) bytes += 4L * frame.argb().length;
        }
        long pictureBytes = bytes;
        boolean animated = frames.size() > 1;
        boolean keep = animated && keepFramesEvery > 0 && animatedQueued++ % keepFramesEvery == 0;
        Path keepDir = output.resolve("frames").resolve(Integer.toString(index));
        waitingBytes.addAndGet(pictureBytes);
        payloads[index] = encoders.submit(() -> {
            long encodeStart = System.nanoTime();
            try {
                byte[] payload = ENCODER.get().encode(frames, (int) FakeTime.FRAME_MILLIS);
                if (keep) keepFrames(keepDir, frames, payload);
                return payload;
            } finally {
                long took = System.nanoTime() - encodeStart;
                encodeNanos.addAndGet(took);
                if (animated) animatedEncodeNanos.addAndGet(took);
                encodeCount.incrementAndGet();
                waitingBytes.addAndGet(-pictureBytes);
            }
        });
        return distinct.size();
    }

    private static void keepFrames(Path dir, List<Frame> frames, byte[] payload) throws IOException {
        Files.createDirectories(dir);
        for (int k = 0; k < frames.size(); k++) {
            Frame frame = frames.get(k);
            var image = new java.awt.image.BufferedImage(frame.width(), frame.height(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, frame.width(), frame.height(), frame.argb(), 0, frame.width());
            javax.imageio.ImageIO.write(image, "png", dir.resolve(String.format("frame_%03d.png", k)).toFile());
        }
        Files.write(dir.resolve("anim.webp"), payload);
    }

    /**
     * Appends finished encodes to the pak in record order, and moves past records that will never have one. Stops at
     * the first encode still running and at the first record with nothing queued yet.
     */
    private void drain() throws IOException {
        while (written < entries.size()) {
            if (states[written] == State.FAILED) {
                written++;
                continue;
            }
            Future<byte[]> payload = payloads[written];
            if (payload == null || !payload.isDone()) return;
            payloads[written] = null;
            try {
                PakEntry entry = pak.append(payload.get());
                images[written] = new RecipeJson.Image(storedFrames[written], entry.offset(), entry.length());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                LOG.warn("[dumper] {} failed to encode", entries.get(written).recipe().getId(), cause);
                failures[written] = "encode: " + cause.toString().replaceAll("\\s+", " ");
                states[written] = State.FAILED;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for an encode", e);
            }
            written++;
        }
    }

    private void progress() {
        int statics = 0, animated = 0, failed = 0;
        for (int i = 0; i < chunkStart; i++) {
            switch (states[i]) {
                case STATIC -> statics++;
                case ANIMATED -> animated++;
                case FAILED -> failed++;
                default -> {
                }
            }
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        LOG.info("[dumper] batch: {}/{} drawn in {} s: {} static, {} animated, {} failed; {} encoded, {} written,"
                        + " {} MiB waiting, pak {} B; {} draws at {} us, {} sequence frames at {} us, {} stalls",
                chunkStart, entries.size(), seconds, statics, animated, failed, encodeCount.get(), written,
                waitingBytes.get() >> 20, pak.size(), draws, draws == 0 ? 0 : drawNanos / draws / 1000,
                sequenceFrames, sequenceFrames == 0 ? 0 : sequenceNanos / sequenceFrames / 1000, stalls);
    }

    /** Frame-policy buckets for the summary: the plan's section 5 table. */
    private static String bucket(int period, int stored) {
        if (stored == 1) return "static";
        if (period < 0) return "trimmed to " + FramePolicy.TRIM;
        if (period == 1) return "animated at the probes, unchanging in sequence";
        return period <= 40 ? "closes at <= 40" : "closes at 41-200";
    }

    private void finish() throws IOException {
        if (finished) return;
        finished = true;
        encoders.shutdown();
        pak.close();

        int failed = 0;
        Map<Integer, Integer> byProbe = new TreeMap<>();
        // bucket -> {recipes, bytes, frames}
        Map<String, long[]> byBucket = new TreeMap<>();
        // category -> {static, animated, static bytes, animated bytes, trimmed}
        Map<String, long[]> byCategory = new TreeMap<>();
        long drawnInSequence = 0;
        for (int i = 0; i < entries.size(); i++) {
            long[] category = byCategory.computeIfAbsent(entries.get(i).category(), k -> new long[5]);
            switch (states[i]) {
                case STATIC -> {
                    category[0]++;
                    category[2] += images[i].bytes();
                    long[] b = byBucket.computeIfAbsent("static", k -> new long[3]);
                    b[0]++;
                    b[1] += images[i].bytes();
                    b[2]++;
                }
                case ANIMATED -> {
                    category[1]++;
                    category[3] += images[i].bytes();
                    if (periods[i] < 0) category[4]++;
                    byProbe.merge(animatedAt[i], 1, Integer::sum);
                    drawnInSequence += sequenceDraws[i];
                    long[] b = byBucket.computeIfAbsent(bucket(periods[i], storedFrames[i]), k -> new long[3]);
                    b[0]++;
                    b[1] += images[i].bytes();
                    b[2] += storedFrames[i];
                }
                case FAILED -> failed++;
                default -> throw new IllegalStateException("record " + i + " was never classified");
            }
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        int animated = byProbe.values().stream().mapToInt(Integer::intValue).sum();
        LOG.info("[dumper] batch done in {} s: {} recipes, {} failed, pak {} B; {} animated (first differing probe {}),"
                        + " {} sequence frames drawn for them; {} draws at {} us, {} sequence frames at {} us; {} encodes"
                        + " at {} ms mean over {} threads, animated ones {} ms mean; {} stalls on the encode buffer",
                seconds, entries.size(), failed, pak.size(), animated, byProbe, drawnInSequence, draws,
                draws == 0 ? 0 : drawNanos / draws / 1000, sequenceFrames,
                sequenceFrames == 0 ? 0 : sequenceNanos / sequenceFrames / 1000, encodeCount.get(),
                encodeCount.get() == 0 ? 0 : encodeNanos.get() / encodeCount.get() / 1_000_000L, threads,
                animated == 0 ? 0 : animatedEncodeNanos.get() / animated / 1_000_000L, stalls);
        StringBuilder buckets = new StringBuilder();
        byBucket.forEach((name, b) -> buckets.append("\n  ").append(name).append('\t').append(b[0]).append(" recipes\t")
                .append(b[2]).append(" frames\t").append(b[1]).append(" B\t").append(b[0] == 0 ? 0 : b[1] / b[0])
                .append(" B mean"));
        LOG.info("[dumper] batch by frame policy:{}", buckets);
        StringBuilder perCategory = new StringBuilder();
        byCategory.forEach((category, n) -> perCategory.append("\n  ").append(category).append('\t').append(n[0])
                .append(" static\t").append(n[0] == 0 ? 0 : n[2] / n[0]).append(" B mean\t").append(n[1])
                .append(" animated\t").append(n[4]).append(" trimmed\t").append(n[1] == 0 ? 0 : n[3] / n[1])
                .append(" B mean"));
        LOG.info("[dumper] batch per category:{}", perCategory);

        writeAnimation(output.resolve("animation.tsv"));
        RecipeJson.writeFile(entries, images, output.resolve("recipes.json"));
        if (failed > 0) {
            StringBuilder first = new StringBuilder();
            for (int i = 0, shown = 0; i < entries.size() && shown < 10; i++) {
                if (failures[i] == null) continue;
                shown++;
                first.append("\n  ").append(entries.get(i).recipe().getId()).append(" (").append(entries.get(i).category())
                        .append("): ").append(failures[i]);
            }
            throw new IllegalStateException(failed + " recipes failed to render or encode; first few:" + first);
        }
    }

    /**
     * What the frame policy decided for each animated recipe, by record index (the line of {@code recipes.json}, not
     * counting the opening bracket). Not part of the artifact's contract: it is how the policy gets checked.
     */
    private void writeAnimation(Path file) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("index\temiRecipeId\tcategory\tclass\tprobe\tperiod\tframes\tdrawn\tdistinct\tbytes\toffset\n");
            for (int i = 0; i < entries.size(); i++) {
                if (states[i] != State.ANIMATED) continue;
                EmiRecipe recipe = entries.get(i).recipe();
                out.write(String.join("\t",
                        Integer.toString(i),
                        recipe.getId() == null ? "" : recipe.getId().toString(),
                        entries.get(i).category(),
                        recipe.getClass().getName(),
                        Integer.toString(animatedAt[i]),
                        Integer.toString(periods[i]),
                        Integer.toString(storedFrames[i]),
                        Integer.toString(sequenceDraws[i]),
                        Integer.toString(distinctFrames[i]),
                        Integer.toString(images[i].bytes()),
                        Long.toString(images[i].offset())) + "\n");
            }
        }
    }
}
