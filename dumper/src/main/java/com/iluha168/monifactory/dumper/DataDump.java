package com.iluha168.monifactory.dumper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * The data-only artifact: {@code recipes.json} for the same recipes {@link Batch} would draw, with every
 * {@code "image"} null, plus {@code categories.tsv} and {@code meta.json}. Nothing is drawn or encoded, so the run is
 * the boot and EMI's reload and nothing else, minutes instead of the build's hours.
 */
final class DataDump {
    private DataDump() {
    }

    static void write(Corpus corpus, Pack pack, Path output, int limit) throws IOException {
        long start = System.nanoTime();
        Files.createDirectories(output);
        corpus.writeCategories(output.resolve("categories.tsv"));
        Batch.Selection selection = Batch.select(corpus, limit);
        RecipeJson.writeFile(selection.entries(), new String[selection.entries().size()],
                output.resolve("recipes.json"));
        // Last, as in the build: a directory with meta.json in it is finished.
        Meta.write(output.resolve("meta.json"), pack, selection, 0, null);
        LOG.info("[dumper] data-only artifact of {} recipes written in {} ms", selection.entries().size(),
                (System.nanoTime() - start) / 1_000_000L);
    }
}
