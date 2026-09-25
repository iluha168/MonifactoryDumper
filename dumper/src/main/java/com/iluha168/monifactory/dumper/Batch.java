package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.imgencoder.FramePolicy;
import com.iluha168.monifactory.imgencoder.WebpEncoder;
import com.iluha168.monifactory.imgencoder.layered.Layer;
import com.iluha168.monifactory.imgencoder.layered.LayeredImage;
import com.iluha168.monifactory.imgencoder.layered.StillTable;
import com.iluha168.monifactory.imgencoder.layered.StillWriter;
import com.iluha168.monifactory.imgencoder.layered.Timeline;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.TagEmiIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * The build: every kept recipe drawn as layers, or whole where the layers do not reproduce it, with every distinct
 * still written once to {@code stills.pak} and each recipe's picture into its {@code recipes.json} record (artifact
 * format 2, {@code dumper/FORMAT.md}).
 * <p>
 * Recipes go one at a time in corpus order, each a {@link RecipeJob} stepped until it is done; a recipe's sequence
 * may span many game frames. {@link #advance} works for about {@link #FRAME_BUDGET_NANOS} and hands the frame back.
 * Nothing between frames touches what a job holds: the atlas only ticks through the job, and its GL buffers wait.
 * <p>
 * A finished recipe's stills go to the {@link StillWriter} right away, on this thread, in layer and loop order, so ids
 * go out in record order and the same corpus gets the same ids. Only then: a recipe that fell back never adds the
 * stills of its layered attempt. WebP encoding runs on a pool of its own; drawing pauses between recipes while more
 * than {@link #BUFFER_PROPERTY} of pixels wait for an encoder.
 * <p>
 * Two pools, not one. The matte pool solves the alpha of big tiles and hashes them while the render thread waits in
 * {@link TileRenderer.Submission#collect}, so its latency is the render thread's; it also composites and compares the
 * checks against the real render, whose verdict a recipe needs before it is done. Sharing a queue with encodes, which
 * take tens of milliseconds each at method 6, that work would wait behind them. Its threads sleep between bursts; when
 * they wake, the scheduler favours them over encoders that have been running, so they are not starved by encoders on
 * every other core. It is small ({@link #matteThreads}): a collect's big tiles are one or two, and a check is a
 * millisecond or two.
 */
final class Batch {
    private static final long FRAME_BUDGET_NANOS = 250_000_000L;
    /** A progress line every this many recipes, or every {@link #PROGRESS_NANOS}, whichever comes first. */
    private static final int PROGRESS_EVERY = 1024;
    private static final long PROGRESS_NANOS = 60_000_000_000L;
    /** Categories named in the summary's fallback counts. */
    private static final int TOP_CATEGORIES = 20;
    /** Worker threads encoding, by default every core but the game thread's and one to spare. */
    static final String ENCODERS_PROPERTY = "monifactory.dumper.encoders";
    /** How many MiB of stills may wait for an encoder before drawing pauses. Default 1024. */
    static final String BUFFER_PROPERTY = "monifactory.dumper.encodeBufferMiB";
    /**
     * Renders only every how many-th kept recipe, in corpus order: a partial artifact that is a systematic sample of
     * the whole. The corpus is grouped by category, so the sample is spread over every category in proportion to its
     * size, and its totals times this factor estimate the full build's in a fraction of the hours. Default 1, all.
     */
    static final String EVERY_PROPERTY = "monifactory.dumper.every";
    /**
     * Renders about one kept recipe in this many, picked by a hash of what the recipe is rather than where it sits, so
     * two boots pick the same recipes even though EMI's list drifts by a few dozen between them. {@link #EVERY_PROPERTY}
     * is the better size estimate; this is the sample two builds can be compared on (the rebuild check). Default 1.
     */
    static final String SAMPLE_PROPERTY = "monifactory.dumper.sample";
    /**
     * How many games the build runs at once ({@code -Pmonifactory.processes}), and which of them this is, from 0. Each
     * renders the recipes whose key ({@link #stableKeys}) hashes to its index, and the build merges their artifacts.
     * Default 1 and 0: this game renders everything.
     */
    static final String SHARDS_PROPERTY = "monifactory.dumper.shards";
    static final String SHARD_PROPERTY = "monifactory.dumper.shard";
    /**
     * The seed of the shard hash. Not {@link #SAMPLE_PROPERTY}'s 0: under the same hash, a 1-in-50 sample split three
     * ways would pick recipes whose hash is a multiple of 50, and those are not spread evenly over the residues of 3.
     */
    private static final long SHARD_SEED = 0x5AADL;
    /** The sidecar a shard writes next to its artifact: the whole selection's keys in order, and which it owns. */
    static final String SHARD_FILE = "shard.tsv";

    private static final ThreadLocal<WebpEncoder> ENCODER = ThreadLocal.withInitial(WebpEncoder::new);

    private final Minecraft minecraft;
    private final Path output;
    private final Pack pack;
    /** How the entries were picked out of the kept corpus, for meta.json. */
    private final Selection selection;
    private final List<Corpus.Entry> entries;
    private final int encoderThreads, matteThreads;
    private final ExecutorService encoders, mattes;
    private final long bufferBytes;
    private final StillWriter stills;
    private final TileRenderer tiles;
    private final RecipeJob.Tools tools;
    private final RecipeJob.Timings timings = new RecipeJob.Timings();
    private final SpriteTicks sprites;

    /** Per record: its picture as recipes.json holds it, or null; and what render.tsv says of it. */
    private final String[] images;
    private final RecipeJob.Mode[] modes;
    private final String[] reasons;
    private final int[] layerCounts, animatedLayers, framesDrawn;
    private final long[] recipeNanos;
    private final FallbackTally fallbacks = new FallbackTally();
    private int layered, fallback, failed;
    private long layers, animated, clockLayers, clockStatic, frames;
    /** Layers drawn over black and white, and how many of them used each blend state that made them. */
    private long matteLayers;
    private final Map<String, Integer> matteBlends = new TreeMap<>();

    private int next;
    private RecipeJob job;
    private boolean finished;

    private final long started = System.nanoTime();
    private long lastProgress = started;
    private long stallSince, stallNanos, drawnAt;
    private int stalls;
    private final AtomicLong encodeNanos = new AtomicLong();
    private final AtomicInteger encodes = new AtomicInteger();

    private Batch(Minecraft minecraft, Pack pack, Path output, Selection selection, int encoderThreads,
                  int matteThreads, long bufferBytes) throws IOException {
        this.minecraft = minecraft;
        this.pack = pack;
        this.output = output;
        this.selection = selection;
        this.entries = selection.entries();
        int n = entries.size();
        this.images = new String[n];
        this.modes = new RecipeJob.Mode[n];
        this.reasons = new String[n];
        this.layerCounts = new int[n];
        this.animatedLayers = new int[n];
        this.framesDrawn = new int[n];
        this.recipeNanos = new long[n];
        this.encoderThreads = encoderThreads;
        this.matteThreads = matteThreads;
        this.bufferBytes = bufferBytes;
        this.encoders = pool("dumper-encoder-", encoderThreads);
        this.mattes = pool("dumper-matte-", matteThreads);
        this.stills = StillWriter.create(output, encoders, still -> {
            long start = System.nanoTime();
            try {
                return ENCODER.get().encode(still);
            } finally {
                encodeNanos.addAndGet(System.nanoTime() - start);
                encodes.incrementAndGet();
            }
        });
        this.tiles = new TileRenderer(minecraft, mattes);
        this.sprites = new SpriteTicks(minecraft);
        this.tools = new RecipeJob.Tools(minecraft, tiles, new LayerRecorder(minecraft), new RecipeRenderer.Pipeline(),
                new RecipeRenderer.Pipeline(), sprites, mattes, timings);
    }

    private static ExecutorService pool(String name, int threads) {
        AtomicInteger count = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, name + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The recipes a run writes: the whole kept corpus, or the sample {@link #EVERY_PROPERTY} or {@link #SAMPLE_PROPERTY}
     * pick of it, or its first {@code limit} if that is fewer (a partial artifact, for trying the build out). The data
     * mode picks the same way, so its {@code partial} means the same thing. {@code picked} is how many that is; a shard
     * of the build writes only its own {@code entries} of them, see {@link Shard}.
     */
    record Selection(List<Corpus.Entry> entries, int picked, int corpus, int every, int sample, int limit,
                     Shard shard) {
        /** A sample or a capped run is a partial artifact. It is valid, but it is not the pack. */
        boolean partial() {
            return every > 1 || sample > 1 || picked < corpus;
        }
    }

    /**
     * One game's part of a build that runs several: the stable key of every recipe the selection picked, in corpus
     * order, and which of them this game renders. The merge orders the shards' records by these lists, and they are
     * what it tells drift by: EMI's list differs by a few dozen recipes from boot to boot, so the shards' lists differ
     * too.
     */
    record Shard(int index, int count, List<String> keys, BitSet owned) {
        /** {@link #SHARD_FILE}: {@code record} (the line in this shard's recipes.json, blank if not its) and {@code key}. */
        void write(Path file) throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("record\tkey\n");
                for (int i = 0, record = 0; i < keys.size(); i++) {
                    out.write((owned.get(i) ? Integer.toString(record++) : "") + "\t" + keys.get(i) + "\n");
                }
            }
        }
    }

    static Selection select(Corpus corpus, int limit) {
        List<Corpus.Entry> entries = corpus.kept;
        int shards = Integer.getInteger(SHARDS_PROPERTY, 1), shard = Integer.getInteger(SHARD_PROPERTY, 0);
        if (shards < 1 || shard < 0 || shard >= shards) {
            throw new IllegalArgumentException(SHARD_PROPERTY + "=" + shard + " of " + SHARDS_PROPERTY + "=" + shards);
        }
        int every = Integer.getInteger(EVERY_PROPERTY, 1);
        if (every < 1) throw new IllegalArgumentException(EVERY_PROPERTY + " must be at least 1, not " + every);
        if (every > 1 && shards > 1) {
            // Every Nth by position in each game's own list, and the lists drift: past the first recipe one boot has
            // and another has not, the games would be counting from different starts.
            throw new IllegalArgumentException(EVERY_PROPERTY + " picks by position, which differs between the "
                    + shards + " games of a sharded build; use " + SAMPLE_PROPERTY);
        }
        int sample = Integer.getInteger(SAMPLE_PROPERTY, 1);
        if (sample < 1) throw new IllegalArgumentException(SAMPLE_PROPERTY + " must be at least 1, not " + sample);
        // Over the whole kept corpus: whether a recipe's identity is shared is a question about all of it.
        List<String> keys = sample > 1 || shards > 1 ? stableKeys(entries) : null;
        if (every > 1) {
            List<Corpus.Entry> picked = new ArrayList<>(entries.size() / every + 1);
            for (int i = 0; i < entries.size(); i += every) picked.add(entries.get(i));
            LOG.warn("[dumper] taking every {}th of {} kept recipes, {} in all; the artifact is a sample", every,
                    entries.size(), picked.size());
            entries = picked;
            if (keys != null) {
                List<String> pickedKeys = new ArrayList<>(picked.size());
                for (int i = 0; i < keys.size(); i += every) pickedKeys.add(keys.get(i));
                keys = pickedKeys;
            }
        }
        if (sample > 1) {
            List<Corpus.Entry> picked = new ArrayList<>(entries.size() / sample + 1);
            List<String> pickedKeys = new ArrayList<>(entries.size() / sample + 1);
            for (int i = 0; i < entries.size(); i++) {
                if (Long.remainderUnsigned(Sample.score(0, keys.get(i)), sample) == 0) {
                    picked.add(entries.get(i));
                    pickedKeys.add(keys.get(i));
                }
            }
            LOG.warn("[dumper] taking a 1-in-{} sample of {} kept recipes by recipe hash, {} in all; the artifact is"
                    + " a sample", sample, entries.size(), picked.size());
            entries = picked;
            keys = pickedKeys;
        }
        if (limit < entries.size()) {
            LOG.warn("[dumper] taking only the first {} of {} kept recipes; the artifact is partial", limit,
                    entries.size());
            entries = entries.subList(0, limit);
            if (keys != null) keys = keys.subList(0, limit);
        }
        int picked = entries.size();
        if (shards == 1) return new Selection(entries, picked, corpus.kept.size(), every, sample, limit, null);

        // After the sample and the limit, so that the shards together render what one game would have.
        BitSet owned = new BitSet(picked);
        List<Corpus.Entry> mine = new ArrayList<>(picked / shards + 1);
        for (int i = 0; i < picked; i++) {
            if (Long.remainderUnsigned(Sample.score(SHARD_SEED, keys.get(i)), shards) == shard) {
                owned.set(i);
                mine.add(entries.get(i));
            }
        }
        LOG.info("[dumper] shard {} of {}: {} of the {} recipes picked", shard, shards, mine.size(), picked);
        return new Selection(mine, picked, corpus.kept.size(), every, sample, limit,
                new Shard(shard, shards, List.copyOf(keys), owned));
    }

    /** Starts the build over {@link #select}'s recipes. */
    static Batch start(Minecraft minecraft, Corpus corpus, Pack pack, Tags tags, Path output, int limit)
            throws IOException {
        Files.createDirectories(output);
        corpus.writeCategories(output.resolve("categories.tsv"));
        Lang.write(minecraft, output);
        tags.write(output.resolve("tags.json"));
        Selection selection = select(corpus, limit);
        // Alone, every core but the render thread's and one to spare encodes. Several games share the rest: each has a
        // render thread of its own, and an encoder too many takes time from some game's render thread, which is the
        // slowest part of the build. Two matte threads are enough for a collect's one or two big tiles.
        int cores = Runtime.getRuntime().availableProcessors();
        int shards = selection.shard() == null ? 1 : selection.shard().count();
        int encoderThreads = Integer.getInteger(ENCODERS_PROPERTY, Math.max(1, (cores - 1 - shards) / shards));
        int matteThreads = Math.max(1, Math.min(4, cores / 3));
        if (shards > 1) matteThreads = Math.min(2, matteThreads);
        long buffer = Long.getLong(BUFFER_PROPERTY, 1024L) << 20;
        LOG.info("[dumper] batch: {} recipes as layers, checked against the real render at frame 0, every {}th"
                        + " sequence frame and the last; loops up to {} frames (trim to {}); {} encoder threads,"
                        + " {} matte threads, {} MiB encode buffer", selection.entries().size(), RecipeJob.CHECK_EVERY,
                FramePolicy.CAP, FramePolicy.TRIM, encoderThreads, matteThreads, buffer >> 20);
        return new Batch(minecraft, pack, output, selection, encoderThreads, matteThreads, buffer);
    }

    /**
     * Every recipe's key, one each and the same in the next boot ({@link StableKeys}): its {@link #identity}, and its
     * {@link #detail} where another recipe of the list has the same identity.
     */
    static List<String> stableKeys(List<Corpus.Entry> entries) {
        long start = System.nanoTime();
        List<String> identities = new ArrayList<>(entries.size());
        for (Corpus.Entry entry : entries) identities.add(identity(entry));
        StableKeys.Keys keys = StableKeys.of(identities, i -> detail(entries.get(i).recipe()));
        LOG.info("[dumper] keyed {} recipes in {} ms: {} share their identity and are told apart by their stacks, {} of"
                        + " those by occurrence as well", entries.size(), (System.nanoTime() - start) / 1_000_000L,
                keys.detailed(), keys.occurrences());
        return keys.keys();
    }

    /**
     * What a recipe is, in terms that hold from boot to boot: its category and its ids where it has any. A recipe with
     * neither id is its category, display class, size and the ids of its stacks, each list sorted and without counts
     * or NBT, since those are what the pack varies per boot (tag member order, NBT variants, GT's fluid amounts).
     */
    static String identity(Corpus.Entry entry) {
        EmiRecipe recipe = entry.recipe();
        String emiId = recipe.getId() == null ? null : recipe.getId().toString();
        String underlying = null;
        try {
            var backing = recipe.getBackingRecipe();
            if (backing != null) underlying = backing.getId().toString();
        } catch (RuntimeException e) {
            // Some bridged recipes cannot name what backs them; the rest of the key still stands.
        }
        StringBuilder key = new StringBuilder(entry.category()).append('|');
        if (emiId != null || underlying != null) return key.append(emiId).append('|').append(underlying).toString();
        key.append(recipe.getClass().getName()).append('|').append(recipe.getDisplayWidth()).append('x')
                .append(recipe.getDisplayHeight());
        for (List<? extends EmiIngredient> list : List.of(recipe.getInputs(), recipe.getCatalysts(),
                recipe.getOutputs())) {
            List<String> ids = new ArrayList<>();
            for (var ingredient : list) {
                for (var stack : ingredient.getEmiStacks()) ids.add(String.valueOf(stack.getId()));
            }
            ids.sort(null);
            key.append('|').append(String.join(",", ids));
        }
        return key.toString();
    }

    /**
     * Everything {@code recipes.json} says of a recipe's stacks, for recipes whose {@link #identity} is not their own:
     * inputs, catalysts and outputs, each stack with its amount, chance, remainder and a hash of its NBT's text, a tag
     * by its name, and outputs off the card where the recipe lists none (see {@link RecipeJson}; the framing saw's
     * results are only there). Each list is sorted, since some mods list a recipe's stacks in hash set order.
     */
    static String detail(EmiRecipe recipe) {
        StringBuilder detail = new StringBuilder();
        List<EmiIngredient> inputs = null, catalysts = null;
        try {
            inputs = recipe.getInputs();
            detail.append("in=").append(stacks(inputs));
        } catch (RuntimeException e) {
            detail.append("in=?");
        }
        try {
            catalysts = recipe.getCatalysts();
            detail.append("|cats=").append(stacks(catalysts));
        } catch (RuntimeException e) {
            detail.append("|cats=?");
        }
        try {
            List<? extends EmiIngredient> outputs = recipe.getOutputs();
            int width = recipe.getDisplayWidth(), height = recipe.getDisplayHeight();
            if (outputs.isEmpty() && width > 0 && height > 0) {
                outputs = RecipeJson.resultSlots(recipe, width, height, inputs, catalysts);
            }
            detail.append("|out=").append(stacks(outputs));
        } catch (RuntimeException e) {
            detail.append("|out=?");
        }
        return detail.toString();
    }

    private static String stacks(List<? extends EmiIngredient> list) {
        List<String> out = new ArrayList<>(list.size());
        for (EmiIngredient ingredient : list) {
            if (ingredient == null) {
                out.add("null");
                continue;
            }
            StringBuilder one = new StringBuilder();
            if (ingredient instanceof TagEmiIngredient tag) {
                one.append('#').append(tag.key.location());
            } else if (ingredient instanceof EmiStack stack) {
                one.append(stack.getId());
                CompoundTag nbt = stack.getNbt();
                if (nbt != null && !nbt.isEmpty()) {
                    // Its text, not the tag, since NBT strings may hold tabs and newlines and shard.tsv may not.
                    one.append('{').append(Long.toHexString(Sample.score(0, nbt.toString()))).append('}');
                }
                EmiStack remainder = stack.getRemainder();
                if (remainder != null && !remainder.isEmpty()) one.append("->").append(remainder.getId());
            } else {
                List<String> ids = new ArrayList<>();
                for (EmiStack option : ingredient.getEmiStacks()) ids.add(String.valueOf(option.getId()));
                ids.sort(null);
                one.append('[').append(String.join(",", ids)).append(']');
            }
            one.append('*').append(ingredient.getAmount());
            if (ingredient.getChance() != 1.0f) one.append('@').append(ingredient.getChance());
            out.add(one.toString());
        }
        out.sort(null);
        return String.join(";", out);
    }

    /**
     * Works for one frame's budget. Returns true once the artifact is written. Never waits on the encoders: once
     * everything is drawn it returns until they are done, so the game loop keeps running meanwhile.
     */
    boolean advance() throws IOException, InterruptedException {
        if (finished) return true;
        long start = System.nanoTime();
        while (System.nanoTime() - start < FRAME_BUDGET_NANOS) {
            if (job == null) {
                if (next == entries.size()) {
                    if (drawnAt == 0) {
                        drawnAt = System.nanoTime();
                        progress();
                    }
                    if (!stills.settled()) return false;
                    finish();
                    return true;
                }
                if (stills.waitingBytes() >= bufferBytes) {
                    if (stallSince == 0) {
                        stallSince = System.nanoTime();
                        stalls++;
                    }
                    return false;
                }
                if (stallSince != 0) {
                    stallNanos += System.nanoTime() - stallSince;
                    stallSince = 0;
                }
                job = new RecipeJob(tools, entries.get(next).recipe());
            }
            long stepStart = System.nanoTime();
            boolean done = job.step();
            if (done) record(next, job);
            recipeNanos[next] += System.nanoTime() - stepStart;
            if (!done) continue;
            job = null;
            next++;
            if (next % PROGRESS_EVERY == 0 || System.nanoTime() - lastProgress > PROGRESS_NANOS) progress();
        }
        return false;
    }

    /** Takes a finished recipe's result: its stills to the writer, its picture and diagnostics to the records. */
    private void record(int index, RecipeJob done) {
        modes[index] = done.mode();
        for (Set<BlendState> blends : done.matteBlends()) {
            matteLayers++;
            for (BlendState blend : blends) matteBlends.merge(blend.toString(), 1, Integer::sum);
        }
        if (done.mode() == RecipeJob.Mode.FAILED) {
            failed++;
            reasons[index] = done.failure();
            return;
        }
        List<RecipeJob.Stored> stored = done.layers();
        List<Layer> out = new ArrayList<>(stored.size());
        for (RecipeJob.Stored layer : stored) {
            int[] ids = new int[layer.hashes().size()];
            for (int k = 0; k < ids.length; k++) ids[k] = stills.add(layer.hashes().get(k), layer.pictures().get(k));
            LayerPlan.Box box = layer.box();
            out.add(new Layer(box.x(), box.y(), box.width(), box.height(), Timeline.of(ids)));
        }
        images[index] = new LayeredImage(done.width(), done.height(), out).toJson();
        layerCounts[index] = stored.size();
        animatedLayers[index] = done.animatedLayers();
        framesDrawn[index] = done.framesDrawn();
        frames += done.framesDrawn();
        if (done.mode() == RecipeJob.Mode.LAYERED) {
            layered++;
            layers += stored.size();
            animated += done.animatedLayers();
            clockLayers += done.clockLayers();
            clockStatic += done.clockStatic();
        } else {
            fallback++;
            reasons[index] = done.reason().toString();
            fallbacks.add(entries.get(index).category(), done.reason().kind());
        }
    }

    private void progress() {
        lastProgress = System.nanoTime();
        LOG.info("[dumper] batch: {}/{} recipes in {} s: {} layered, {} drawn whole, {} failed; {} stills, {} MiB"
                        + " waiting to encode, {} encoded; render thread {}; {} stalls on the encode buffer, {} s",
                next, entries.size(), (lastProgress - started) / 1_000_000_000L, layered, fallback, failed,
                stills.count(), stills.waitingBytes() >> 20, encodes.get(), stages(), stalls,
                stallNanos / 1_000_000_000L);
    }

    /** Render-thread time so far, in seconds, split by stage. */
    private String stages() {
        long total = 0;
        for (int i = 0; i < next; i++) total += recipeNanos[i];
        long other = total - timings.plan - timings.submit - timings.collect - timings.reference - timings.whole
                - timings.atlas;
        return String.format("%.1f s (plan %.1f, submit %.1f, collect %.1f, reference %.1f, whole %.1f, atlas %.1f,"
                        + " other %.1f)", total / 1e9, timings.plan / 1e9, timings.submit / 1e9, timings.collect / 1e9,
                timings.reference / 1e9, timings.whole / 1e9, timings.atlas / 1e9, other / 1e9);
    }

    private void finish() throws IOException, InterruptedException {
        if (finished) return;
        finished = true;
        StillTable table = stills.finish();
        encoders.shutdown();
        mattes.shutdown();
        tiles.close();

        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        long tail = (System.nanoTime() - drawnAt) / 1_000_000_000L;
        int n = entries.size();
        LOG.info("[dumper] batch done in {} s: {} recipes, {} layered, {} drawn whole, {} failed; {} layers in the"
                        + " layered ones, {} of them animated, {} of {} clock-only ones static by the probes;"
                        + " {} sequence frames; {} stills, {} B; render thread {}, {} ms a recipe; {} encodes at {} ms"
                        + " mean over {} threads; {} stalls on the encode buffer, {} s; {} s for the last encodes",
                seconds, n, layered, fallback, failed, layers, animated, clockStatic, clockLayers, frames,
                table.size(), table.bytes(), stages(), n == 0 ? 0 : sum(recipeNanos) / n / 1_000_000L, encodes.get(),
                encodes.get() == 0 ? 0 : encodeNanos.get() / encodes.get() / 1_000_000L, encoderThreads, stalls,
                stallNanos / 1_000_000_000L, tail);
        LOG.info("[dumper] {}", sprites.summary());
        LOG.info("[dumper] {} layers drawn over black and white, not once, for the blend states they used: {}",
                matteLayers, matteBlends);
        if (fallbacks.total() > 0) {
            LOG.info("[dumper] drawn whole, by reason:{}", fallbacks.byReason());
            LOG.info("[dumper] drawn whole, by category (top {}):{}", TOP_CATEGORIES,
                    fallbacks.byCategory(TOP_CATEGORIES));
        }

        writeRenderTsv(output.resolve("render.tsv"));
        RecipeJson.writeFile(entries, images, output.resolve("recipes.json"));
        if (selection.shard() != null) selection.shard().write(output.resolve(SHARD_FILE));
        // Last: a directory with meta.json in it is finished.
        Meta.write(output.resolve("meta.json"), pack, selection, failed,
                new Meta.Images(RecipeRenderer.scale(minecraft), table.size(), table.bytes(), layered, fallback));
        if (failed > 0) {
            StringBuilder first = new StringBuilder();
            for (int i = 0, shown = 0; i < n && shown < 10; i++) {
                if (modes[i] != RecipeJob.Mode.FAILED) continue;
                shown++;
                first.append("\n  ").append(entries.get(i).recipe().getId()).append(" (")
                        .append(entries.get(i).category()).append("): ").append(reasons[i]);
            }
            throw new IllegalStateException(failed + " recipes failed to render; first few:" + first);
        }
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long v : values) total += v;
        return total;
    }

    /**
     * {@code render.tsv}: how each record was drawn, by record index (the line of {@code recipes.json},
     * not counting the opening bracket), plus the render thread's milliseconds on it. Diagnostics, not contract.
     */
    private void writeRenderTsv(Path file) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("index\temiRecipeId\tcategory\tclass\tlayers\tanimatedLayers\tframesDrawn\tmode\treason"
                    + "\tmillis\n");
            for (int i = 0; i < entries.size(); i++) {
                EmiRecipe recipe = entries.get(i).recipe();
                out.write(String.join("\t",
                        Integer.toString(i),
                        recipe.getId() == null ? "" : recipe.getId().toString(),
                        entries.get(i).category(),
                        recipe.getClass().getName(),
                        Integer.toString(layerCounts[i]),
                        Integer.toString(animatedLayers[i]),
                        Integer.toString(framesDrawn[i]),
                        modes[i] == null ? "" : modes[i].name().toLowerCase(java.util.Locale.ROOT),
                        reasons[i] == null ? "" : reasons[i].replaceAll("\\s+", " "),
                        Long.toString(recipeNanos[i] / 1_000_000L)) + "\n");
            }
        }
    }
}
