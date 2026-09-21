package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.PakEntry;
import com.iluha168.monifactory.imgencoder.PakWriter;
import com.iluha168.monifactory.imgencoder.WebpEncoder;
import com.mojang.blaze3d.platform.NativeImage;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * The build: every kept recipe drawn, the static ones encoded into {@code images.pak}, and {@code recipes.json} written
 * with each static recipe's frame count, length and offset. Animated recipes are found and left with null render
 * fields for the animation pass to fill.
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
 * Encoding is lossless WebP at method 6, 350 ms a still on average with ten encoders sharing the machine, some two
 * hundred times a draw. It runs on worker threads, each with its own encoder, while the game thread keeps drawing.
 * Payloads are appended to the pak in record order, so the file's layout depends on the corpus and nothing else; the
 * game thread stops drawing whenever {@link #MAX_IN_FLIGHT} images wait to be encoded or written.
 */
final class Batch {
    static final int[] PROBES = {53, 97, 199};
    private static final int WARM_UP = 2;
    /** Warm-up passes, frame 0, then one pass per probe. */
    private static final int PASSES = WARM_UP + 1 + PROBES.length;
    private static final int CHUNK = 1024;
    private static final int MAX_IN_FLIGHT = 256;
    private static final long FRAME_BUDGET_NANOS = 250_000_000L;
    /** Where the fake clock stands at frame 0. The census's origin, which is as good as any. */
    private static final long BASE_MILLIS = 2_000_000L;
    /** Worker threads encoding, by default every core but the game thread's and one to spare. */
    static final String ENCODERS_PROPERTY = "monifactory.dumper.encoders";

    private enum State { PENDING, ANIMATED, STATIC, FAILED }

    private final Minecraft minecraft;
    private final Path output;
    private final List<Corpus.Entry> entries;
    private final State[] states;
    private final long[] firstHashes;
    /** The probe frame that first differed from frame 0, for animated recipes. */
    private final int[] animatedAt;
    private final String[] failures;
    private final RecipeJson.Image[] images;
    private final ExecutorService encoders;
    private final PakWriter pak;
    /** Encodes submitted and not yet written, in record order. */
    private final ArrayDeque<Pending> inFlight = new ArrayDeque<>();

    private record Pending(int index, Future<byte[]> payload) {
    }

    private int chunkStart;
    private int pass;
    private int cursor;
    /** Atlas ticks since the current chunk's frame 0. */
    private int tick;
    /** Every record before this one is either written to the pak or will never be. */
    private int written;
    private boolean finished;
    private final long started = System.nanoTime();
    private long drawNanos, draws, encodeNanosTotal;
    private final AtomicInteger encodeCount = new AtomicInteger();

    private Batch(Minecraft minecraft, Path output, List<Corpus.Entry> entries, int threads) throws IOException {
        this.minecraft = minecraft;
        this.output = output;
        this.entries = entries;
        int n = entries.size();
        this.states = new State[n];
        java.util.Arrays.fill(states, State.PENDING);
        this.firstHashes = new long[n];
        this.animatedAt = new int[n];
        this.failures = new String[n];
        this.images = new RecipeJson.Image[n];
        this.pak = PakWriter.create(output.resolve("images.pak"));
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
        if (limit < entries.size()) {
            LOG.warn("[dumper] rendering only the first {} of {} kept recipes; the artifact is partial", limit,
                    entries.size());
            entries = entries.subList(0, limit);
        }
        int threads = Integer.getInteger(ENCODERS_PROPERTY, Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        LOG.info("[dumper] batch: {} recipes in chunks of {}, probes {} after {} warm-up draws, {} encoder threads",
                entries.size(), CHUNK, java.util.Arrays.toString(PROBES), WARM_UP, threads);
        return new Batch(minecraft, output, entries, threads);
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
            if (cursor == chunkStart && pass > WARM_UP) {
                // A probe pass starts: move the atlas to its frame.
                int to = PROBES[pass - WARM_UP - 1];
                while (tick < to) {
                    FakeTime.tickAtlas(minecraft.getTextureManager()::tick);
                    tick++;
                }
            }
            if (pass == PASSES - 1 && inFlight.size() >= MAX_IN_FLIGHT) {
                return false;
            }
            draw(cursor);
            if (++cursor == chunkEnd) {
                cursor = chunkStart;
                if (++pass == PASSES) {
                    pass = 0;
                    tick = 0;
                    chunkStart = chunkEnd;
                    cursor = chunkStart;
                    progress();
                }
            }
        }
        return false;
    }

    /** One draw of record {@code index} in the current pass, and whatever it decides. */
    private void draw(int index) {
        if (states[index] != State.PENDING) return;
        Corpus.Entry entry = entries.get(index);
        EmiRecipe recipe = entry.recipe();
        int frame = pass <= WARM_UP ? 0 : PROBES[pass - WARM_UP - 1];
        long drawStart = System.nanoTime();
        NativeImage image;
        try {
            image = RecipeRenderer.render(minecraft, recipe, BASE_MILLIS + frame * FakeTime.FRAME_MILLIS);
        } catch (RuntimeException | LinkageError e) {
            // Recorded, and the build fails once everything else is written. The renderer restores its GL and clock
            // state on the way out, so the recipes after this one are unaffected.
            LOG.warn("[dumper] {} ({}) failed to render", recipe.getId(), entry.category(), e);
            failures[index] = "render: " + e.toString().replaceAll("\\s+", " ");
            states[index] = State.FAILED;
            return;
        } finally {
            drawNanos += System.nanoTime() - drawStart;
            draws++;
        }
        try (image) {
            if (pass < WARM_UP) return;
            int[] pixels = image.getPixelsRGBA();
            long hash = RecipeRenderer.hash(pixels);
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
            Frame still = Frame.fromAbgr(image.getWidth(), image.getHeight(), pixels);
            inFlight.add(new Pending(index, encoders.submit(() -> {
                long encodeStart = System.nanoTime();
                byte[] payload = ENCODER.get().encode(still);
                encodeCount.incrementAndGet();
                synchronized (this) {
                    encodeNanosTotal += System.nanoTime() - encodeStart;
                }
                return payload;
            })));
        }
    }

    /**
     * Appends finished encodes to the pak in record order, and moves past records that will never have one. Stops at
     * the first encode still running, or at the first record not yet classified: those before the current chunk, and
     * in the chunk's last pass those already drawn in it.
     */
    private void drain() throws IOException {
        int classified = pass == PASSES - 1 ? cursor : chunkStart;
        while (written < classified) {
            State state = states[written];
            if (state == State.STATIC) {
                Pending head = inFlight.peek();
                if (head == null || head.index() != written) {
                    throw new IllegalStateException("record " + written + " is static but has no encode queued");
                }
                if (!head.payload().isDone()) return;
                inFlight.poll();
                try {
                    PakEntry entry = pak.append(head.payload().get());
                    images[written] = new RecipeJson.Image(1, entry.offset(), entry.length());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    LOG.warn("[dumper] {} failed to encode", entries.get(written).recipe().getId(), cause);
                    failures[written] = "encode: " + cause.toString().replaceAll("\\s+", " ");
                    states[written] = State.FAILED;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted waiting for an encode", e);
                }
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
        LOG.info("[dumper] batch: {}/{} classified in {} s: {} static, {} animated, {} failed; {} encoded, {} queued,"
                        + " pak {} B; {} draws at {} us", chunkStart, entries.size(), seconds, statics, animated, failed,
                encodeCount.get(), inFlight.size(), pak.size(), draws, draws == 0 ? 0 : drawNanos / draws / 1000);
    }

    private void finish() throws IOException {
        if (finished) return;
        finished = true;
        encoders.shutdown();
        pak.close();

        int statics = 0, animated = 0, failed = 0;
        long bytes = 0;
        Map<Integer, Integer> byProbe = new TreeMap<>();
        Map<String, long[]> byCategory = new TreeMap<>();
        for (int i = 0; i < entries.size(); i++) {
            long[] category = byCategory.computeIfAbsent(entries.get(i).category(), k -> new long[3]);
            switch (states[i]) {
                case STATIC -> {
                    statics++;
                    bytes += images[i].bytes();
                    category[0]++;
                    category[2] += images[i].bytes();
                }
                case ANIMATED -> {
                    animated++;
                    category[1]++;
                    byProbe.merge(animatedAt[i], 1, Integer::sum);
                }
                case FAILED -> failed++;
                default -> throw new IllegalStateException("record " + i + " was never classified");
            }
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        LOG.info("[dumper] batch done in {} s: {} recipes, {} static ({} B in images.pak, mean {} B), {} animated"
                        + " (first differing probe {}), {} failed; {} draws at {} us, encode {} ms mean over {} threads",
                seconds, entries.size(), statics, bytes, statics == 0 ? 0 : bytes / statics, animated, byProbe, failed,
                draws, draws == 0 ? 0 : drawNanos / draws / 1000,
                statics == 0 ? 0 : encodeNanosTotal / statics / 1_000_000L,
                ((java.util.concurrent.ThreadPoolExecutor) encoders).getMaximumPoolSize());
        StringBuilder perCategory = new StringBuilder();
        byCategory.forEach((category, n) -> perCategory.append("\n  ").append(category).append('\t').append(n[0])
                .append(" static\t").append(n[1]).append(" animated\t").append(n[0] == 0 ? 0 : n[2] / n[0])
                .append(" B mean"));
        LOG.info("[dumper] batch per category:{}", perCategory);

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
}
