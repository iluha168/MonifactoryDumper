package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
import com.mojang.blaze3d.platform.NativeImage;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * How much of the corpus animates, and at what period: a sample rendered frame by frame, in memory, with nothing
 * written but {@code census.tsv}. It is also the smoke batch, since every sampled recipe is drawn dozens to hundreds
 * of times and a draw that throws is recorded rather than ending the run.
 * <p>
 * The sample is {@link #PER_CATEGORY} recipes from every non-empty kept category, then random recipes from the whole
 * kept corpus up to the target, which makes the tail size-proportional. It is drawn by identity from the corpus, so
 * recipes without an id are in it too.
 * <p>
 * Per recipe: two warm-up draws for first-use uploads, then two classifications on one frame sequence. The census that
 * PLAN section 5 quotes compared 8 phases 5 frames apart; the rule PLAN section 4 adopts compares frame 0 with frames
 * 53, 97 and 199. Both are recorded, so the old fraction and the new rule can be told apart. A recipe either calls
 * animated then gets a period under the strict rule: the smallest {@code p} with {@code hash[k] == hash[k+p]} for every
 * {@code k} of the frames drawn, checked at 64, 128, 256 and {@link #CAP} frames. That is the checkpoint ladder the
 * section 5 buckets came from, so they compare as well.
 * <p>
 * One frame is one atlas tick and 50 ms of the fake clock, the game's own relationship, so a period in frames is the
 * true period.
 */
final class Census {
    static final int PER_CATEGORY = 8;
    static final int CAP = 400;
    private static final int[] PHASES = {0, 5, 10, 15, 20, 25, 30, 35};
    private static final int[] PROBES = {53, 97, 199};
    private static final int[] CHECKPOINTS = {64, 128, 256, CAP};
    private static final int WARM_UP = 2;
    private static final long FRAME_BUDGET_NANOS = 250_000_000L;
    /** The prototype census's clock origin, which is as good as any. */
    private static final long BASE_MILLIS = 2_000_000L;

    private static final class Probe {
        final EmiRecipe recipe;
        final String id;
        final String category;
        final int width, height;
        int distinct8 = -1;
        /** The first of the probe frames that differs from frame 0, or 0 if none does. */
        int ladder = -1;
        boolean animated;
        /** Strict-rule period in frames, or -1 if none closed within {@link #CAP}. 0 until measured. */
        int period;
        int draws;
        long nanos;
        String failure;

        Probe(EmiRecipe recipe, String category) {
            this.recipe = recipe;
            this.id = recipe.getId() == null ? "" : recipe.getId().toString();
            this.category = category;
            this.width = recipe.getDisplayWidth();
            this.height = recipe.getDisplayHeight();
        }
    }

    private enum Stage { WARM_UP, CLASSIFY, PERIOD, NEXT }

    private final Minecraft minecraft;
    private final Path output;
    private final List<Probe> probes;

    private int cursor;
    private Stage stage = Stage.WARM_UP;
    /** Index into the stage's frame list, or the frame number within the period sequence. */
    private int frame;
    /** Atlas ticks since this recipe's frame 0. */
    private int tick;
    private final List<Long> hashes = new ArrayList<>();
    private final long started = System.nanoTime();

    private Census(Minecraft minecraft, Path output, List<Probe> probes) {
        this.minecraft = minecraft;
        this.output = output;
        this.probes = probes;
    }

    static Census choose(Minecraft minecraft, Corpus corpus, Path output, long seed, int target) throws IOException {
        Files.createDirectories(output);
        corpus.writeCategories(output.resolve("categories.tsv"));

        Map<String, List<Corpus.Entry>> byCategory = new LinkedHashMap<>();
        for (Corpus.Entry entry : corpus.kept) {
            byCategory.computeIfAbsent(entry.category(), k -> new ArrayList<>()).add(entry);
        }
        Set<EmiRecipe> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Probe> probes = new ArrayList<>();
        for (Map.Entry<String, List<Corpus.Entry>> category : byCategory.entrySet()) {
            List<Corpus.Entry> entries = new ArrayList<>(category.getValue());
            Collections.shuffle(entries, new Random(seed ^ category.getKey().hashCode()));
            for (Corpus.Entry entry : entries.subList(0, Math.min(PER_CATEGORY, entries.size()))) {
                if (chosen.add(entry.recipe())) probes.add(new Probe(entry.recipe(), entry.category()));
            }
        }
        int stratified = probes.size();
        List<Corpus.Entry> flat = new ArrayList<>(corpus.kept);
        Collections.shuffle(flat, new Random(seed));
        for (Corpus.Entry entry : flat) {
            if (probes.size() >= target) break;
            if (chosen.add(entry.recipe())) probes.add(new Probe(entry.recipe(), entry.category()));
        }
        probes.sort(Comparator.comparing((Probe p) -> p.category).thenComparingInt(p -> p.width)
                .thenComparingInt(p -> p.height));
        LOG.info("[dumper] census: {} stratified (<= {} from each of {} categories) + {} random = {} of {} kept recipes",
                stratified, PER_CATEGORY, byCategory.size(), probes.size() - stratified, probes.size(),
                corpus.kept.size());
        return new Census(minecraft, output, probes);
    }

    /** Draws for one frame's budget. Returns true once every recipe is measured and {@code census.tsv} is written. */
    boolean advance() throws IOException {
        long start = System.nanoTime();
        while (System.nanoTime() - start < FRAME_BUDGET_NANOS) {
            if (cursor == probes.size()) {
                write();
                return true;
            }
            Probe probe = probes.get(cursor);
            long drawStart = System.nanoTime();
            try {
                step(probe);
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("[dumper] census: {} ({}) failed to render", probe.id, probe.category, e);
                probe.failure = e.toString().replaceAll("\\s+", " ");
                stage = Stage.NEXT;
            }
            probe.nanos += System.nanoTime() - drawStart;
            if (stage == Stage.NEXT) {
                cursor++;
                stage = Stage.WARM_UP;
                frame = 0;
                tick = 0;
                hashes.clear();
                if (cursor % 250 == 0) {
                    LOG.info("[dumper] census: {}/{} recipes in {} s", cursor, probes.size(),
                            (System.nanoTime() - started) / 1_000_000_000L);
                }
            }
        }
        return false;
    }

    /** One draw of {@code probe}, and whatever it decides. */
    private void step(Probe probe) {
        switch (stage) {
            case WARM_UP -> {
                tickAtlas();
                draw(probe, BASE_MILLIS - 3_000 + frame);
                if (++frame == WARM_UP) {
                    stage = Stage.CLASSIFY;
                    frame = 0;
                    tick = 0;
                }
            }
            case CLASSIFY -> {
                int at = frame < PHASES.length ? PHASES[frame] : PROBES[frame - PHASES.length];
                while (tick < at) {
                    tickAtlas();
                    tick++;
                }
                hashes.add(draw(probe, BASE_MILLIS + at * FakeTime.FRAME_MILLIS));
                if (++frame < PHASES.length + PROBES.length) return;
                probe.distinct8 = new HashSet<>(hashes.subList(0, PHASES.length)).size();
                probe.ladder = 0;
                for (int i = 0; i < PROBES.length; i++) {
                    if (!hashes.get(PHASES.length + i).equals(hashes.get(0))) {
                        probe.ladder = PROBES[i];
                        break;
                    }
                }
                probe.animated = probe.distinct8 > 1 || probe.ladder > 0;
                hashes.clear();
                frame = 0;
                stage = probe.animated ? Stage.PERIOD : Stage.NEXT;
            }
            case PERIOD -> {
                // A fresh sequence. The atlas carries on from where classification left it, which only shifts the
                // phase the loop starts at.
                if (frame > 0) tickAtlas();
                hashes.add(draw(probe, BASE_MILLIS + frame * FakeTime.FRAME_MILLIS));
                frame++;
                for (int checkpoint : CHECKPOINTS) {
                    if (frame != checkpoint) continue;
                    int period = strictPeriod(hashes);
                    if (period > 0 || frame == CAP) {
                        probe.period = period;
                        stage = Stage.NEXT;
                    }
                }
            }
            default -> {
            }
        }
    }

    private void tickAtlas() {
        FakeTime.tickAtlas(minecraft.getTextureManager()::tick);
    }

    private long draw(Probe probe, long millis) {
        probe.draws++;
        try (NativeImage image = RecipeRenderer.render(minecraft, probe.recipe, millis)) {
            return RecipeRenderer.hash(image);
        }
    }

    /** The smallest p with hashes[k] == hashes[k+p] for every k in [0, n-p), up to n/2; -1 if there is none. */
    static int strictPeriod(List<Long> hashes) {
        int n = hashes.size();
        candidates:
        for (int p = 1; p <= n / 2; p++) {
            for (int k = 0; k + p < n; k++) {
                if (hashes.get(k).longValue() != hashes.get(k + p).longValue()) continue candidates;
            }
            return p;
        }
        return -1;
    }

    private void write() throws IOException {
        int failed = 0, animated8 = 0, animated = 0, closed = 0;
        long draws = 0;
        try (BufferedWriter out = Files.newBufferedWriter(output.resolve("census.tsv"), StandardCharsets.UTF_8)) {
            out.write("id\tcategory\tclass\tdisplayWidth\tdisplayHeight\tdistinct8\tladder\tanimated\tperiod\tdraws"
                    + "\tms\tfailure\n");
            for (Probe probe : probes) {
                draws += probe.draws;
                if (probe.failure != null) failed++;
                else {
                    if (probe.distinct8 > 1) animated8++;
                    if (probe.animated) animated++;
                    if (probe.period > 0) closed++;
                }
                out.write(String.join("\t",
                        probe.id,
                        probe.category,
                        probe.recipe.getClass().getName(),
                        Integer.toString(probe.width),
                        Integer.toString(probe.height),
                        Integer.toString(probe.distinct8),
                        Integer.toString(probe.ladder),
                        probe.failure == null ? Boolean.toString(probe.animated) : "",
                        Integer.toString(probe.period),
                        Integer.toString(probe.draws),
                        Long.toString(probe.nanos / 1_000_000L),
                        probe.failure == null ? "" : probe.failure) + "\n");
            }
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        LOG.info("[dumper] census done in {} s: {} recipes, {} draws, {} failed; animated {} (8-phase {}),"
                        + " {} closed within {} frames",
                seconds, probes.size(), draws, failed, animated, animated8, closed, CAP);
    }
}
