package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * The raw per-frame hash sequence of a sample, written to {@code seq.csv} and nothing decided, in the format
 * {@code CheckDetection} reads. The loop rules (the strict period, the early stop in
 * {@code FramePolicy}) are functions of these sequences, so a pack bump re-checks them offline on identical pixels
 * with {@code :dumper:compare:checkDetection}, instead of comparing boots that differ anyway.
 * <p>
 * Frame {@code k} is drawn at fake clock {@code BASE + 50k} after exactly {@code k + 1} atlas ticks, after two warm-up
 * draws. The sample is {@link #PER_CATEGORY} recipes from every non-empty kept
 * category, sorted by category and size, with the seed picking which.
 */
final class Seq {
    static final int PER_CATEGORY = 6;
    /** Past the frame policy's 400, so a rule can be checked from a later start phase too. */
    static final int FRAMES = 460;
    private static final int WARM_UP = 2;
    private static final long FRAME_BUDGET_NANOS = 250_000_000L;
    private static final long BASE_MILLIS = 2_000_000L;

    private static final class Row {
        final EmiRecipe recipe;
        final String id, category;
        final int width, height;
        final long[] hashes = new long[FRAMES];
        long nanos;
        String failure;

        Row(Corpus.Entry entry) {
            this.recipe = entry.recipe();
            // The file format's stand-in for a recipe without an id, so the offline tools can still tell rows apart.
            this.id = recipe.getId() == null ? "null#" + Integer.toHexString(System.identityHashCode(recipe))
                    : recipe.getId().toString();
            this.category = entry.category();
            this.width = recipe.getDisplayWidth();
            this.height = recipe.getDisplayHeight();
        }
    }

    private final Minecraft minecraft;
    private final Path output;
    private final List<Row> rows;
    private int cursor;
    /** Draws of the current row so far, warm-ups included. */
    private int draw;
    private final long started = System.nanoTime();
    private long hashNanos;
    /**
     * Also draws every frame through {@link RecipeRenderer.Pipeline} and checks it reads back the same pixels as the
     * synchronous path: {@code -Dmonifactory.dumper.checkPipeline=true}.
     */
    private final boolean checkPipeline = Boolean.getBoolean("monifactory.dumper.checkPipeline");
    /**
     * With {@link #checkPipeline}, redraws each frame synchronously instead: the control that says how often a plain
     * redraw of the same frame differs on its own ({@code -Dmonifactory.dumper.checkSync=true}). It came out at 11 of
     * 73,140 frames against the pipeline's 9, so the pipeline adds nothing to the draws' own nondeterminism.
     */
    private final boolean checkSync = Boolean.getBoolean("monifactory.dumper.checkSync");
    private final RecipeRenderer.Pipeline pipeline = new RecipeRenderer.Pipeline();
    private long pipelineChecked, pipelineMismatches;

    private void comparePipeline(Row row, int frame, RecipeRenderer.Readback late) {
        pipelineChecked++;
        if (RecipeRenderer.fastHash(late.pixels, late.size()) != row.hashes[frame]) {
            if (pipelineMismatches++ < 20) LOG.warn("[dumper] seq: pipeline readback differs: {} frame {}", row.id, frame);
        }
    }

    private Seq(Minecraft minecraft, Path output, List<Row> rows) {
        this.minecraft = minecraft;
        this.output = output;
        this.rows = rows;
    }

    static Seq choose(Minecraft minecraft, Corpus corpus, Path output, long seed, int perCategory) throws IOException {
        Files.createDirectories(output);
        corpus.writeCategories(output.resolve("categories.tsv"));
        Map<String, List<Corpus.Entry>> byCategory = new LinkedHashMap<>();
        for (Corpus.Entry entry : corpus.kept) {
            byCategory.computeIfAbsent(entry.category(), k -> new ArrayList<>()).add(entry);
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Corpus.Entry>> category : byCategory.entrySet()) {
            List<Corpus.Entry> entries = new ArrayList<>(category.getValue());
            Collections.shuffle(entries, new Random(seed ^ category.getKey().hashCode()));
            for (Corpus.Entry entry : entries.subList(0, Math.min(perCategory, entries.size()))) {
                rows.add(new Row(entry));
            }
        }
        rows.sort(Comparator.comparing((Row r) -> r.category).thenComparingInt(r -> r.width)
                .thenComparingInt(r -> r.height));
        LOG.info("[dumper] seq: {} recipes, <= {} from each of {} categories, {} frames each", rows.size(),
                perCategory, byCategory.size(), FRAMES);
        return new Seq(minecraft, output, rows);
    }

    boolean advance() throws IOException {
        long start = System.nanoTime();
        while (System.nanoTime() - start < FRAME_BUDGET_NANOS) {
            if (cursor == rows.size()) {
                write();
                return true;
            }
            Row row = rows.get(cursor);
            long drawStart = System.nanoTime();
            boolean done;
            try {
                FakeTime.tickAtlas(minecraft.getTextureManager()::tick);
                int frame = draw - WARM_UP;
                long millis = frame < 0 ? BASE_MILLIS - 3_000 + draw : BASE_MILLIS + frame * FakeTime.FRAME_MILLIS;
                RecipeRenderer.Readback image = RecipeRenderer.draw(minecraft, row.recipe, millis);
                if (frame >= 0) {
                    long hashStart = System.nanoTime();
                    row.hashes[frame] = RecipeRenderer.fastHash(image.pixels, image.size());
                    hashNanos += System.nanoTime() - hashStart;
                    if (checkPipeline) {
                        // The same frame again, clock and atlas unchanged, through the overlapped readback the build
                        // uses; it hands back the frame before, which must hash the same as its synchronous twin.
                        if (checkSync) {
                            comparePipeline(row, frame, RecipeRenderer.draw(minecraft, row.recipe, millis));
                        } else {
                            RecipeRenderer.Readback late = pipeline.draw(minecraft, row.recipe, millis);
                            if (late != null) comparePipeline(row, frame - 1, late);
                            if (frame == FRAMES - 1) comparePipeline(row, frame, pipeline.finish());
                        }
                    }
                }
                done = ++draw == WARM_UP + FRAMES;
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("[dumper] seq: {} ({}) failed to render", row.id, row.category, e);
                row.failure = e.toString().replaceAll("\\s+", " ");
                done = true;
            }
            row.nanos += System.nanoTime() - drawStart;
            if (done) {
                pipeline.discard();
                cursor++;
                draw = 0;
                if (cursor % 100 == 0) {
                    LOG.info("[dumper] seq: {}/{} recipes in {} s", cursor, rows.size(),
                            (System.nanoTime() - started) / 1_000_000_000L);
                }
            }
        }
        return false;
    }

    private void write() throws IOException {
        int scale = dev.emi.emi.config.EmiConfig.recipeScreenshotScale < 1
                ? (int) minecraft.getWindow().getGuiScale() : dev.emi.emi.config.EmiConfig.recipeScreenshotScale;
        Path file = output.resolve("seq.csv");
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("recipe_id,category_id,display_w,display_h,scale,ms,error,hashes\n");
            for (Row row : rows) {
                StringBuilder line = new StringBuilder(FRAMES * 17 + 200);
                line.append(csv(row.id)).append(',').append(csv(row.category)).append(',').append(row.width).append(',')
                        .append(row.height).append(',').append(scale).append(',').append(row.nanos / 1_000_000L)
                        .append(',').append(csv(row.failure)).append(',');
                if (row.failure == null) {
                    for (int k = 0; k < FRAMES; k++) {
                        if (k > 0) line.append(' ');
                        line.append(Long.toHexString(row.hashes[k]));
                    }
                }
                out.write(line.append('\n').toString());
            }
        }
        if (checkPipeline) {
            LOG.info("[dumper] seq: pipeline readback checked on {} frames, {} differ", pipelineChecked,
                    pipelineMismatches);
        }
        LOG.info("[dumper] seq done in {} s: {} rows -> {}; draws spent {} s drawing and {} s reading back, {} s hashing",
                (System.nanoTime() - started) / 1_000_000_000L, rows.size(), file,
                RecipeRenderer.drawNanos / 1_000_000_000L, RecipeRenderer.readNanos / 1_000_000_000L,
                hashNanos / 1_000_000_000L);
    }

    private static String csv(String value) {
        if (value == null) return "";
        String s = value.replace('\n', ' ').replace('\r', ' ');
        return s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
