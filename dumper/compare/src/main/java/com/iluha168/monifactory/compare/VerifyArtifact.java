package com.iluha168.monifactory.compare;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iluha168.monifactory.imgencoder.PakEntry;
import com.iluha168.monifactory.imgencoder.PakReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Checks that an artifact directory is whole (PLAN M5): every {@code recipes.json} entry names a payload in
 * {@code images.pak}, and every payload decodes to the picture the entry describes.
 * <p>
 * Per entry: {@code frames}, {@code bytes} and {@code offset} are set; the payloads tile {@code images.pak} in record
 * order with no gap, overlap or trailing byte; the payload decodes in full, a still for one frame and an animation
 * otherwise; the picture is {@code (w + 8) * scale} by {@code (h + 8) * scale}; and an animation loops forever and
 * runs {@code frames} times the frame length. {@code meta.json}, when there is one, must agree on the record count.
 * <p>
 * Usage: {@code --artifact <dir> [--threads <n>]}. Exits 1 on any failure and lists the first few.
 */
public final class VerifyArtifact {
    /** EMI's screenshot padding, in GUI pixels, which the picture adds to the display size. */
    static final int PADDING = 8;
    /** One animation frame is one atlas tick. meta.json says so too; this is the value when it is absent. */
    static final int FRAME_MILLIS = 50;

    record Entry(int index, String name, int width, int height, Integer frames, Integer bytes, Long offset) {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        Path artifact = Path.of(Objects.requireNonNull(options.get("artifact"), "--artifact <dir>"));
        int threads = Integer.parseInt(options.getOrDefault("threads",
                Integer.toString(Math.max(1, Runtime.getRuntime().availableProcessors() - 2))));
        System.exit(verify(artifact, threads) == 0 ? 0 : 1);
    }

    static int verify(Path artifact, int threads) throws Exception {
        long started = System.nanoTime();
        JsonObject meta = null;
        Path metaFile = artifact.resolve("meta.json");
        if (Files.isRegularFile(metaFile)) {
            meta = JsonParser.parseString(Files.readString(metaFile, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int frameMillis = meta != null && meta.has("frameMillis") ? meta.get("frameMillis").getAsInt() : FRAME_MILLIS;
        Integer scale = meta != null && meta.has("scale") ? meta.get("scale").getAsInt() : null;

        List<Entry> entries = load(artifact.resolve("recipes.json"));
        List<String> failures = new ArrayList<>();
        if (meta != null && meta.get("recipes").getAsInt() != entries.size()) {
            failures.add("meta.json counts " + meta.get("recipes").getAsInt() + " recipes, recipes.json holds "
                    + entries.size());
        }

        Path pakFile = artifact.resolve("images.pak");
        long pakSize = Files.size(pakFile);
        long end = 0;
        for (Entry entry : entries) {
            if (entry.frames() == null || entry.bytes() == null || entry.offset() == null) {
                failures.add(entry.name() + ": no image (frames/bytes/offset are null)");
                continue;
            }
            if (entry.offset() != end) {
                failures.add(entry.name() + ": offset " + entry.offset() + ", but the previous payload ends at " + end);
            }
            if (entry.bytes() <= 0 || entry.frames() <= 0) {
                failures.add(entry.name() + ": " + entry.frames() + " frames in " + entry.bytes() + " B");
            }
            end = Math.max(end, entry.offset() + entry.bytes());
        }
        if (end != pakSize) {
            failures.add("images.pak is " + pakSize + " B, the entries cover " + end + " B of it");
        }

        long[] kinds = new long[2];
        AtomicLong animationFrames = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (PakReader pak = PakReader.open(pakFile)) {
            List<Future<String>> checks = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                if (entry.frames() == null || entry.bytes() == null || entry.offset() == null) continue;
                if (entry.bytes() <= 0 || entry.offset() + entry.bytes() > pakSize) {
                    failures.add(entry.name() + ": " + entry.bytes() + " B at " + entry.offset()
                            + " is not inside images.pak");
                    continue;
                }
                kinds[entry.frames() == 1 ? 0 : 1]++;
                checks.add(pool.submit(() -> {
                    WebpFile file;
                    try {
                        file = WebpFile.decode(pak.read(new PakEntry(entry.offset(), entry.bytes())));
                    } catch (IOException e) {
                        return entry.name() + ": " + e.getMessage();
                    }
                    int step = scale != null ? scale : Math.max(1, file.width() / (entry.width() + PADDING));
                    int width = (entry.width() + PADDING) * step, height = (entry.height() + PADDING) * step;
                    if (file.width() != width || file.height() != height) {
                        return entry.name() + ": " + file.width() + "x" + file.height() + ", expected " + width + "x"
                                + height + " at scale " + step;
                    }
                    if (entry.frames() == 1) {
                        return file.animated() ? entry.name() + ": one frame, but the payload is an animation" : null;
                    }
                    if (!file.animated()) return entry.name() + ": " + entry.frames() + " frames, but a still";
                    animationFrames.addAndGet(file.frames());
                    if (file.loops() != 0) return entry.name() + ": loops " + file.loops() + " times, not forever";
                    if (file.frames() > entry.frames()) {
                        return entry.name() + ": " + file.frames() + " stored frames for " + entry.frames() + " frames";
                    }
                    if (file.durationMillis() != (long) entry.frames() * frameMillis) {
                        return entry.name() + ": runs " + file.durationMillis() + " ms, expected " + entry.frames()
                                + " x " + frameMillis + " ms";
                    }
                    return null;
                }));
            }
            for (Future<String> check : checks) {
                String failure = check.get();
                if (failure != null) failures.add(failure);
            }
        } finally {
            pool.shutdown();
        }

        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        System.out.printf("%s: %,d recipes, %,d stills and %,d animations (%,d stored frames), images.pak %,d B;"
                        + " checked in %d s on %d threads%n", artifact, entries.size(), kinds[0], kinds[1],
                animationFrames.get(), pakSize, seconds, threads);
        if (meta != null) System.out.println("meta.json: " + meta);
        if (failures.isEmpty()) {
            System.out.println("OK: every entry resolves to a decodable image of its recipe's size");
            return 0;
        }
        System.out.println("FAILED: " + failures.size() + " problems; the first few:");
        failures.stream().limit(20).forEach(failure -> System.out.println("  " + failure));
        return failures.size();
    }

    static List<Entry> load(Path file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null; ) {
                line = line.strip();
                if (line.equals("[") || line.equals("]") || line.isEmpty()) continue;
                if (line.endsWith(",")) line = line.substring(0, line.length() - 1);
                JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                int index = entries.size();
                String id = string(json, "emiRecipeId");
                if (id == null) id = string(json, "underlyingRecipeId");
                String name = "#" + index + " " + (id == null ? "" : id + " ") + "(" + string(json, "cat") + ")";
                entries.add(new Entry(index, name, json.get("w").getAsInt(), json.get("h").getAsInt(),
                        integer(json, "frames"), integer(json, "bytes"),
                        json.get("offset").isJsonNull() ? null : json.get("offset").getAsLong()));
            }
        }
        return entries;
    }

    private static Integer integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsInt();
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
