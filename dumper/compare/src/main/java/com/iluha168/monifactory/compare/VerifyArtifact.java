package com.iluha168.monifactory.compare;

import com.iluha168.monifactory.imgencoder.layered.Layer;
import com.iluha168.monifactory.imgencoder.layered.LayeredImage;
import com.iluha168.monifactory.imgencoder.layered.StillEntry;
import com.iluha168.monifactory.imgencoder.layered.StillTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Checks that a format 2 artifact directory is whole, as {@code dumper/FORMAT.md} describes it: every picture a record
 * describes can be drawn from what the artifact holds.
 * <p>
 * The stills: {@code stills.json} tiles {@code stills.pak} exactly, from 0 to its last byte, and every still decodes as
 * a single WebP image of the size its row gives. The records: every non-null {@code "image"} obeys the format's rules,
 * which reading it through {@link Artifact#image} checks (still ids exist, the stills of a layer share a size, every
 * box lies inside the canvas, every duration is positive, there is a layer 0), and its canvas is
 * {@code (w + 8) * scale} by {@code (h + 8) * scale} for the record's display size. And {@code meta.json} agrees with
 * both: the recipe and still counts, the pak's size, and {@code layered + fallback + failed == recipes}, where a
 * failed record is one whose image is null.
 * <p>
 * A data-only artifact, whose {@code meta.json} says {@code "images": false}, has no {@code stills.*}, null image
 * fields in its meta, and {@code "image": null} in every record.
 * <p>
 * A format 1 artifact is refused as a whole: it has nothing this checks.
 * <p>
 * Usage: {@code --artifact <dir> [--threads <n>]}. Exits 1 on any failure and lists the first few.
 */
public final class VerifyArtifact {
    /** EMI's screenshot padding, in GUI pixels, which the picture adds to the display size. */
    static final int PADDING = 8;

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        Path artifact = Path.of(Objects.requireNonNull(options.get("artifact"), "--artifact <dir>"));
        int threads = Integer.parseInt(options.getOrDefault("threads",
                Integer.toString(Math.max(1, Runtime.getRuntime().availableProcessors() - 2))));
        System.exit(verify(artifact, threads).isEmpty() ? 0 : 1);
    }

    /** Checks {@code directory}, prints a summary, and returns every problem found, none for a whole artifact. */
    static List<String> verify(Path directory, int threads) throws IOException, InterruptedException {
        long started = System.nanoTime();
        List<String> failures = new ArrayList<>();
        try (Artifact artifact = Artifact.open(directory)) {
            if (artifact.images()) verifyImages(artifact, threads, failures);
            else verifyData(artifact, failures);
            long seconds = (System.nanoTime() - started) / 1_000_000_000L;
            System.out.println("checked in " + seconds + " s; meta.json: " + artifact.meta);
        } catch (IOException e) {
            // Not an artifact these tools can read at all, or it lost a file on the way.
            failures.add(e.getMessage());
        }
        if (failures.isEmpty()) {
            System.out.println("OK: every record's picture can be drawn from the artifact's stills");
        } else {
            System.out.println("FAILED: " + failures.size() + " problems; the first few:");
            failures.stream().limit(20).forEach(failure -> System.out.println("  " + failure));
        }
        return failures;
    }

    private static void verifyImages(Artifact artifact, int threads, List<String> failures)
            throws IOException, InterruptedException {
        StillTable table = artifact.stills;
        long pakSize = artifact.pak.size();
        if (pakSize != table.bytes()) {
            failures.add(StillTable.PAK + " is " + pakSize + " B, " + StillTable.JSON + " covers " + table.bytes()
                    + " B of it");
        }
        Long scale = artifact.metaNumber("scale");
        if (scale == null || scale < 1) failures.add("meta.json has images but scale " + scale);

        int[] counts = new int[3]; // records, with an image, of those animated
        int[] layers = new int[2]; // all, animated
        BitSet referenced = new BitSet(table.size());
        artifact.records((index, record) -> {
            counts[0]++;
            LayeredImage image;
            try {
                image = artifact.image(record);
            } catch (IllegalArgumentException e) {
                failures.add(Artifact.name(index, record) + ": " + e.getMessage());
                // It claims a picture, so the meta counts should too; only the picture is wrong.
                if (record.has("image") && !record.get("image").isJsonNull()) counts[1]++;
                return;
            }
            if (image == null) return;
            counts[1]++;
            if (image.animated()) counts[2]++;
            for (Layer layer : image.layers()) {
                layers[0]++;
                if (layer.loop().animated()) layers[1]++;
                for (int i = 0; i < layer.loop().size(); i++) referenced.set(layer.loop().still(i));
            }
            if (scale == null) return;
            if (!record.has("w") || !record.has("h")) {
                failures.add(Artifact.name(index, record) + ": no display size (w, h) to check the canvas against");
                return;
            }
            int w = record.get("w").getAsInt(), h = record.get("h").getAsInt();
            long width = (w + PADDING) * scale, height = (h + PADDING) * scale;
            if (image.width() != width || image.height() != height) {
                failures.add(Artifact.name(index, record) + ": a " + image.width() + "x" + image.height()
                        + " canvas, expected " + width + "x" + height + " for a " + w + "x" + h + " recipe at scale "
                        + scale);
            }
        });
        checkCounts(artifact, failures, counts[0], counts[1], table.size(), pakSize);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> checks = new ArrayList<>(table.size());
            for (int id = 0; id < table.size(); id++) {
                int still = id;
                checks.add(pool.submit(() -> {
                    try {
                        artifact.still(still);
                        return null;
                    } catch (IOException e) {
                        StillEntry entry = table.get(still);
                        return "still " + still + " (" + entry.payload().length() + " B at " + entry.payload().offset()
                                + "): " + e.getMessage();
                    }
                }));
            }
            for (Future<String> check : checks) {
                String failure;
                try {
                    failure = check.get();
                } catch (ExecutionException e) {
                    failure = "a still check threw " + e.getCause();
                }
                if (failure != null) failures.add(failure);
            }
        } finally {
            pool.shutdown();
        }
        System.out.printf("%s: %,d recipes, %,d with a picture (%,d animated) in %,d layers (%,d animated);"
                        + " %,d stills (%,d referenced by no record) in %,d B; %d threads%n", artifact.directory,
                counts[0], counts[1], counts[2], layers[0], layers[1], table.size(),
                table.size() - referenced.get(0, table.size()).cardinality(), pakSize, threads);
    }

    /** meta.json against what the artifact holds. {@code rendered} records have a picture; the rest failed. */
    private static void checkCounts(Artifact artifact, List<String> failures, int records, int rendered, int stills,
                                    long pakSize) {
        expect(artifact, failures, "recipes", records, "recipes.json holds " + records + " records");
        expect(artifact, failures, "failed", records - rendered, (records - rendered) + " records have no picture");
        expect(artifact, failures, "stills", stills, StillTable.JSON + " lists " + stills + " stills");
        expect(artifact, failures, "stillsBytes", pakSize, StillTable.PAK + " is " + pakSize + " B");
        Long layered = artifact.metaNumber("layered"), fallback = artifact.metaNumber("fallback");
        if (layered == null || fallback == null || layered < 0 || fallback < 0 || layered + fallback != rendered) {
            failures.add("meta.json counts " + layered + " layered and " + fallback + " fallback recipes, but "
                    + rendered + " records have a picture");
        }
    }

    private static void expect(Artifact artifact, List<String> failures, String key, long actual, String what) {
        Long claimed = artifact.metaNumber(key);
        if (claimed == null || claimed != actual) failures.add("meta.json says " + key + " " + claimed + ", " + what);
    }

    private static void verifyData(Artifact artifact, List<String> failures) throws IOException {
        for (String file : new String[]{StillTable.PAK, StillTable.JSON, "render.tsv"}) {
            if (Files.exists(artifact.directory.resolve(file))) {
                failures.add("meta.json says the artifact has no images, but there is a " + file);
            }
        }
        for (String key : new String[]{"stills", "stillsBytes", "layered", "fallback", "scale"}) {
            if (artifact.metaNumber(key) != null) {
                failures.add("meta.json says the artifact has no images, but " + key + " is set");
            }
        }
        int[] records = {0};
        artifact.records((index, record) -> {
            records[0]++;
            try {
                // With no still table, anything but "image": null throws.
                artifact.image(record);
            } catch (IllegalArgumentException e) {
                failures.add(Artifact.name(index, record) + ": " + e.getMessage());
            }
        });
        expect(artifact, failures, "recipes", records[0], "recipes.json holds " + records[0] + " records");
        System.out.printf("%s: %,d recipes, data only%n", artifact.directory, records[0]);
    }
}
