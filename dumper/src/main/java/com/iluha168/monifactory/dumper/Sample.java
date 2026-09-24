package com.iluha168.monifactory.dumper;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * A seeded sample of EMI's recipes, rendered to PNGs with a manifest, a few recipes per frame.
 * <p>
 * The sample is picked per recipe id, not by shuffling EMI's list: each id gets a score from the seed and the id
 * alone, and the lowest {@code count} scores are taken. EMI's total drifts by a few hundred recipes between boots, and
 * a shuffle of the whole list would then pick a different sample every time. Scored by id, two boots pick the same
 * recipes except where drift touches the sample itself, so two runs - two boots, or the dev and production roads -
 * can be compared recipe by recipe.
 * <p>
 * Every recipe is drawn {@link #PASSES} times, one pass over the whole sample after another, and only the last pass is
 * kept. The first draw of a recipe uploads sprites and builds caches, and a pass apart gives anything that happens a
 * frame later time to land. The manifest records whether the last two passes agreed.
 * <p>
 * Output: {@code <id>.png} per recipe, {@code manifest.tsv}, {@code categories.tsv}, which lists every category EMI
 * aggregates with its recipe count, including the empty ones, and {@code recipes.tsv}, every kept recipe's id (blank if
 * it has none), category and class.
 */
final class Sample {
    /** Two warm-up passes, then the one that is written. */
    static final int PASSES = 3;
    /** How long a frame may spend drawing before it hands control back to the game loop. */
    private static final long FRAME_BUDGET_NANOS = 250_000_000L;
    /**
     * Where the fake clock stands for every draw. Any fixed value will do; this one is frame 0's in the build and the
     * census, so renders of all three compare.
     */
    private static final long STILL_FRAME_MILLIS = 2_000_000L;

    private record Pick(EmiRecipe recipe, String id, String category, String file) {
    }

    private final Minecraft minecraft;
    private final Path output;
    private final List<Pick> picks;
    private final long[][] hashes;
    private final String[] failures;

    private int pass;
    private int cursor;

    private Sample(Minecraft minecraft, Path output, List<Pick> picks) {
        this.minecraft = minecraft;
        this.output = output;
        this.picks = picks;
        this.hashes = new long[PASSES][picks.size()];
        this.failures = new String[picks.size()];
    }

    /** Picks the sample from the corpus, and writes {@code categories.tsv} and {@code recipes.tsv}. */
    static Sample choose(Minecraft minecraft, Corpus corpus, Path output, long seed, int count) throws IOException {
        Files.createDirectories(output);
        corpus.writeCategories(output.resolve("categories.tsv"));

        // An id shared by two recipes cannot name one of them across boots, so neither is eligible.
        Map<String, Corpus.Entry> byId = new HashMap<>();
        Set<String> shared = new HashSet<>();
        int unnamed = 0;
        for (Corpus.Entry entry : corpus.kept) {
            if (entry.recipe().getId() == null) {
                unnamed++;
                continue;
            }
            String id = entry.recipe().getId().toString();
            if (byId.putIfAbsent(id, entry) != null) shared.add(id);
        }
        shared.forEach(byId::remove);

        // Every kept recipe, so two boots can be compared as sets and not only through the sample.
        try (BufferedWriter all = Files.newBufferedWriter(output.resolve("recipes.tsv"), StandardCharsets.UTF_8)) {
            all.write("id\tcategory\tclass\n");
            for (Corpus.Entry entry : corpus.kept) {
                EmiRecipe recipe = entry.recipe();
                all.write((recipe.getId() == null ? "" : recipe.getId().toString()) + "\t" + entry.category()
                        + "\t" + recipe.getClass().getName() + "\n");
            }
        }

        List<String> ids = new ArrayList<>(byId.keySet());
        ids.sort(Comparator.comparingLong((String id) -> score(seed, id)).thenComparing(Comparator.naturalOrder()));
        List<String> chosen = new ArrayList<>(ids.subList(0, Math.min(count, ids.size())));
        // Drawn in id order, so the order does not depend on the seed's scores or on EMI's order.
        Collections.sort(chosen);

        Set<String> files = new HashSet<>();
        List<Pick> picks = new ArrayList<>(chosen.size());
        for (String id : chosen) {
            Corpus.Entry entry = byId.get(id);
            String base = id.replaceAll("[^A-Za-z0-9._-]", "_");
            String file = base + ".png";
            for (int n = 2; !files.add(file); n++) file = base + "~" + n + ".png";
            picks.add(new Pick(entry.recipe(), id, entry.category(), file));
        }
        LOG.info("[dumper] {} kept recipes, {} without an id, {} ids shared; sampled {} of {} with seed {}",
                corpus.kept.size(), unnamed, shared.size(), picks.size(), ids.size(), seed);
        if (picks.isEmpty()) {
            throw new IllegalStateException("the corpus has no recipe to sample");
        }
        return new Sample(minecraft, output, picks);
    }

    /** SplitMix64 over the seed and the id's FNV-1a hash: depends on nothing but the two. */
    static long score(long seed, String id) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : id.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        long z = hash + seed * 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** Draws for one frame's budget. Returns true once the last pass is done and the manifest is written. */
    boolean advance() throws IOException {
        long start = System.nanoTime();
        while (System.nanoTime() - start < FRAME_BUDGET_NANOS) {
            if (cursor == picks.size()) {
                LOG.info("[dumper] pass {}/{} over {} recipes done", pass + 1, PASSES, picks.size());
                cursor = 0;
                if (++pass == PASSES) {
                    writeManifest();
                    return true;
                }
            }
            draw(picks.get(cursor), cursor);
            cursor++;
        }
        return false;
    }

    private void draw(Pick pick, int index) throws IOException {
        if (failures[index] != null) return;
        boolean last = pass == PASSES - 1;
        NativeImage image;
        try {
            image = RecipeRenderer.render(minecraft, pick.recipe(), STILL_FRAME_MILLIS);
        } catch (RuntimeException | LinkageError e) {
            // One recipe that cannot draw is a result, not the end of the run. The renderer restores its GL and clock
            // state on the way out.
            LOG.warn("[dumper] {} failed to render", pick.id(), e);
            failures[index] = e.toString().replaceAll("\\s+", " ");
            return;
        }
        try (image) {
            hashes[pass][index] = RecipeRenderer.hash(image);
            if (last) image.writeToFile(output.resolve(pick.file()));
        }
    }

    private void writeManifest() throws IOException {
        int failed = 0, unsettled = 0;
        try (BufferedWriter manifest = Files.newBufferedWriter(output.resolve("manifest.tsv"), StandardCharsets.UTF_8)) {
            manifest.write("id\tfile\tcategory\tclass\tdisplayWidth\tdisplayHeight\thash\tsettled\tfailure\n");
            for (int i = 0; i < picks.size(); i++) {
                Pick pick = picks.get(i);
                long hash = hashes[PASSES - 1][i];
                boolean settled = failures[i] == null && hashes[PASSES - 2][i] == hash;
                if (failures[i] != null) failed++;
                else if (!settled) unsettled++;
                manifest.write(String.join("\t",
                        pick.id(),
                        failures[i] == null ? pick.file() : "",
                        pick.category(),
                        pick.recipe().getClass().getName(),
                        Integer.toString(pick.recipe().getDisplayWidth()),
                        Integer.toString(pick.recipe().getDisplayHeight()),
                        failures[i] == null ? String.format("%016x", hash) : "",
                        Boolean.toString(settled),
                        failures[i] == null ? "" : failures[i]) + "\n");
            }
        }
        LOG.info("[dumper] rendered {} recipes to {}: {} failed, {} changed between the last two passes",
                picks.size() - failed, output, failed, unsettled);
    }
}
