package com.iluha168.monifactory.compare;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.iluha168.monifactory.imgencoder.PakEntry;
import com.iluha168.monifactory.imgencoder.PakWriter;
import com.iluha168.monifactory.imgencoder.layered.Layer;
import com.iluha168.monifactory.imgencoder.layered.LayeredImage;
import com.iluha168.monifactory.imgencoder.layered.StillEntry;
import com.iluha168.monifactory.imgencoder.layered.StillTable;
import com.iluha168.monifactory.imgencoder.layered.Timeline;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Merges the artifacts of a sharded build, one per game, into one ordinary format 2 artifact.
 * <p>
 * Each game rendered the recipes whose stable key hashes to its index, and wrote next to its artifact
 * {@code shard.tsv}: the key of every recipe the selection picked in its boot, in its order, with the line of its own
 * {@code recipes.json} for the ones it owns. Those lists are not quite equal, since EMI's list drifts by a few dozen
 * recipes between boots, so the merged order is built from them and not from any one shard's records:
 * <ul>
 * <li>Shard 0's list is the spine. A recipe on it sits at its place there, whichever shard drew it.</li>
 * <li>A recipe its owner saw and shard 0 did not goes right after the last recipe before it in its owner's list that
 * is on the spine (at the very start if there is none). Several after the same one go by shard index, then by their
 * place in that shard's list.</li>
 * <li>A recipe on the spine whose owner did not see it is in no shard's records, and is left out: it is what two
 * single-game builds lose to drift too.</li>
 * </ul>
 * A key several recipes share (no ids, same stacks) is told apart by its occurrence: the second one in a list is the
 * second one in every list. All occurrences of a key hash to the same shard.
 * <p>
 * The order depends only on the shards' contents and indices, never on the order they finished or are named in.
 * <p>
 * Still ids are handed out again, by first reference in merged record order, the way the renderer hands them out in
 * one game. Two shards that drew the same still each stored it, and the merge keeps one: stills are the same when their
 * WebP bytes are. The encoder is deterministic (CompareDumps relies on this too), so equal pixels give equal bytes, and
 * lossless WebP decodes different pixels from different bytes, so equal bytes are never two pictures. The payloads are
 * copied, not re-encoded.
 * <p>
 * {@code categories.tsv} and {@code corpus} in {@code meta.json} are shard 0's: they count its boot's corpus, which
 * differs from the others' by the drift. {@code meta.json} gets {@code "processes"} (the shard count) and
 * {@code "drift"}: recipes some shard's list had and another's did not.
 * <p>
 * Usage: {@code --out <dir> --shard <dir> [--shard <dir>...]}. {@code out} must be empty or absent. Refuses shards of
 * different packs, pack versions or modes, renderers, or selections.
 */
public final class MergeShards {
    static final String SHARD_FILE = "shard.tsv";
    /** meta.json fields every shard must agree on, since the merged artifact states each of them once. */
    private static final List<String> SAME = List.of("format", "pack", "minecraft", "forge", "renderer", "images",
            "every", "sample", "limit", "scale", "frameMillis", "framePolicy");

    /** What a merge did, as the summary prints it. */
    record Result(int records, int stills, long stillsBytes, int sharedStills, int drift, int inserted, int lost) {
    }

    public static void main(String[] args) throws Exception {
        Path out = null;
        List<Path> shards = new ArrayList<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[i + 1]);
                case "--shard" -> shards.add(Path.of(args[i + 1]));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        merge(shards, Objects.requireNonNull(out, "--out <dir>"));
    }

    /** One recipe in a shard's list: its key, and which of the recipes with that key it is. */
    private record Item(String key, int occurrence) {
    }

    /** A record of a shard's {@code recipes.json}. */
    private record Owned(int shard, int record) {
    }

    private static final class Shard implements AutoCloseable {
        final Path directory;
        final Artifact artifact;
        final int index;
        final List<Item> items = new ArrayList<>();
        /** Per item, the record it is in this shard's recipes.json, or -1 if another shard owns it. */
        final List<Integer> records = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final List<String> render = new ArrayList<>();
        String renderHeader;
        /** Per old still id, the merged one, or -1 until a record uses it. */
        int[] ids;

        Shard(Path directory) throws IOException {
            this.directory = directory;
            this.artifact = Artifact.open(directory);
            JsonObject shard = artifact.meta.getAsJsonObject("shard");
            if (shard == null) throw new IOException(directory + " is not a shard: its meta.json has no \"shard\"");
            this.index = shard.get("index").getAsInt();
        }

        int count() {
            return artifact.meta.getAsJsonObject("shard").get("count").getAsInt();
        }

        void read() throws IOException {
            if (!artifact.images()) throw new IOException(directory + " has no images; only full builds are sharded");
            ids = new int[artifact.stills.size()];
            Arrays.fill(ids, -1);
            try (BufferedReader reader = Files.newBufferedReader(artifact.directory.resolve("recipes.json"),
                    StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.equals("[") || line.equals("]") || line.isEmpty()) continue;
                    lines.add(line.endsWith(",") ? line.substring(0, line.length() - 1) : line);
                }
            }
            List<String> tsv = Files.readAllLines(directory.resolve(SHARD_FILE), StandardCharsets.UTF_8);
            if (tsv.isEmpty() || !tsv.get(0).equals("record\tkey")) {
                throw new IOException(directory.resolve(SHARD_FILE) + " does not start with record<TAB>key");
            }
            Map<String, Integer> seen = new HashMap<>();
            int owned = 0;
            for (int i = 1; i < tsv.size(); i++) {
                String row = tsv.get(i);
                int tab = row.indexOf('\t');
                if (tab < 0) throw new IOException(SHARD_FILE + " line " + (i + 1) + " has no tab");
                String key = row.substring(tab + 1);
                items.add(new Item(key, seen.merge(key, 1, Integer::sum) - 1));
                if (tab == 0) {
                    records.add(-1);
                    continue;
                }
                int record = Integer.parseInt(row.substring(0, tab));
                if (record != owned) {
                    throw new IOException(SHARD_FILE + " line " + (i + 1) + " names record " + record + ", expected "
                            + owned);
                }
                records.add(owned++);
            }
            if (owned != lines.size()) {
                throw new IOException(directory + ": " + SHARD_FILE + " owns " + owned + " recipes, recipes.json has "
                        + lines.size());
            }
            List<String> renderLines = Files.readAllLines(directory.resolve("render.tsv"), StandardCharsets.UTF_8);
            renderHeader = renderLines.get(0);
            render.addAll(renderLines.subList(1, renderLines.size()));
            if (render.size() != lines.size()) {
                throw new IOException(directory + ": render.tsv has " + render.size() + " rows, recipes.json "
                        + lines.size() + " records");
            }
        }

        @Override
        public void close() throws IOException {
            artifact.close();
        }
    }

    /** Merges {@code shardDirs}, in any order, into {@code out}. */
    static Result merge(List<Path> shardDirs, Path out) throws IOException {
        long started = System.nanoTime();
        if (Files.exists(out)) {
            try (Stream<Path> children = Files.list(out)) {
                if (children.findAny().isPresent()) throw new IOException(out + " is not empty");
            }
        }
        List<Shard> shards = new ArrayList<>();
        try {
            for (Path dir : shardDirs) shards.add(new Shard(dir));
            shards.sort(Comparator.comparingInt(shard -> shard.index));
            check(shards);
            for (Shard shard : shards) shard.read();
            Files.createDirectories(out);
            return write(shards, out, started);
        } finally {
            for (Shard shard : shards) shard.close();
        }
    }

    /** Refuses shards that are not one build's: the indices 0 to n-1 once each, and the same everything else. */
    private static void check(List<Shard> shards) throws IOException {
        if (shards.isEmpty()) throw new IOException("no shards to merge");
        int count = shards.get(0).count();
        for (int i = 0; i < shards.size(); i++) {
            Shard shard = shards.get(i);
            if (shard.count() != count || shard.index != i) {
                throw new IOException("the shards are not 0 to " + (count - 1) + " of " + count + " once each: "
                        + shard.directory + " is " + shard.index + " of " + shard.count());
            }
        }
        if (shards.size() != count) {
            throw new IOException("have " + shards.size() + " shards of " + count);
        }
        JsonObject first = shards.get(0).artifact.meta;
        for (Shard shard : shards.subList(1, shards.size())) {
            for (String key : SAME) {
                if (!Objects.equals(first.get(key), shard.artifact.meta.get(key))) {
                    throw new IOException("refusing to merge: shard 0 has \"" + key + "\": " + first.get(key)
                            + ", shard " + shard.index + " (" + shard.directory + ") has " + shard.artifact.meta.get(key));
                }
            }
        }
    }

    private static Result write(List<Shard> shards, Path out, long started) throws IOException {
        // Who owns what, and the spine.
        Map<Item, Owned> owners = new HashMap<>();
        Map<Item, Integer> presence = new HashMap<>();
        for (Shard shard : shards) {
            for (int i = 0; i < shard.items.size(); i++) {
                Item item = shard.items.get(i);
                presence.merge(item, 1, Integer::sum);
                int record = shard.records.get(i);
                if (record < 0) continue;
                Owned previous = owners.put(item, new Owned(shard.index, record));
                if (previous != null) {
                    throw new IOException("shards " + previous.shard() + " and " + shard.index + " both drew "
                            + item.key() + " (occurrence " + item.occurrence() + ")");
                }
            }
        }
        List<Item> spine = shards.get(0).items;
        Map<Item, Integer> spinePlace = new HashMap<>();
        for (int i = 0; i < spine.size(); i++) spinePlace.put(spine.get(i), i);

        // Recipes shard 0 did not see, each after the last spine recipe before it in its owner's list.
        Map<Integer, List<Owned>> after = new HashMap<>();
        int inserted = 0;
        for (Shard shard : shards) {
            int anchor = -1;
            for (int i = 0; i < shard.items.size(); i++) {
                Integer place = spinePlace.get(shard.items.get(i));
                if (place != null) {
                    anchor = place;
                } else if (shard.records.get(i) >= 0) {
                    after.computeIfAbsent(anchor, k -> new ArrayList<>()).add(new Owned(shard.index,
                            shard.records.get(i)));
                    inserted++;
                }
            }
        }
        List<Owned> order = new ArrayList<>(owners.size());
        order.addAll(after.getOrDefault(-1, List.of()));
        int lost = 0;
        for (int i = 0; i < spine.size(); i++) {
            Owned owned = owners.get(spine.get(i));
            if (owned != null) order.add(owned);
            else lost++;
            order.addAll(after.getOrDefault(i, List.of()));
        }
        // Items no shard owns that shard 0 did not see either are lost as well.
        for (Map.Entry<Item, Integer> entry : presence.entrySet()) {
            if (!spinePlace.containsKey(entry.getKey()) && !owners.containsKey(entry.getKey())) lost++;
        }
        int drift = 0;
        for (int n : presence.values()) if (n < shards.size()) drift++;
        if (order.size() != owners.size()) {
            throw new IllegalStateException(order.size() + " records placed of " + owners.size() + " drawn");
        }

        // Records, with their stills renumbered by first reference and copied into the merged pak.
        Map<String, Integer> byContent = new HashMap<>();
        List<StillEntry> table = new ArrayList<>();
        int shared = 0;
        List<String> renderRows = new ArrayList<>(order.size());
        MessageDigest sha = sha256();
        try (PakWriter pak = PakWriter.create(out.resolve(StillTable.PAK));
             BufferedWriter recipes = Files.newBufferedWriter(out.resolve("recipes.json"), StandardCharsets.UTF_8)) {
            recipes.write("[\n");
            for (int index = 0; index < order.size(); index++) {
                Owned owned = order.get(index);
                Shard shard = shards.get(owned.shard());
                String line = shard.lines.get(owned.record());
                LayeredImage image;
                try {
                    image = shard.artifact.image(JsonParser.parseString(line).getAsJsonObject());
                } catch (IllegalArgumentException | JsonParseException | IllegalStateException e) {
                    throw new IOException(shard.directory + " record " + owned.record() + ": " + e.getMessage(), e);
                }
                if (image != null) {
                    List<Layer> layers = new ArrayList<>(image.layers().size());
                    for (Layer layer : image.layers()) {
                        Timeline loop = layer.loop();
                        int[] stills = new int[loop.size()], ticks = new int[loop.size()];
                        for (int k = 0; k < stills.length; k++) {
                            int old = loop.still(k);
                            if (shard.ids[old] < 0) {
                                StillEntry entry = shard.artifact.stills.get(old);
                                byte[] payload = shard.artifact.pak.read(entry.payload());
                                String content = HexFormat.of().formatHex(sha.digest(payload));
                                Integer known = byContent.get(content);
                                if (known == null) {
                                    known = table.size();
                                    byContent.put(content, known);
                                    PakEntry written = pak.append(payload);
                                    table.add(new StillEntry(written, entry.width(), entry.height()));
                                } else {
                                    shared++;
                                }
                                shard.ids[old] = known;
                            }
                            stills[k] = shard.ids[old];
                            ticks[k] = loop.ticks(k);
                        }
                        layers.add(new Layer(layer.x(), layer.y(), layer.width(), layer.height(),
                                new Timeline(stills, ticks)));
                    }
                    String before = ",\"image\":" + image.toJson();
                    int at = line.lastIndexOf(before);
                    if (at < 0) {
                        throw new IOException(shard.directory + " record " + owned.record()
                                + ": its image is not written the way the renderer writes one");
                    }
                    line = line.substring(0, at) + ",\"image\":"
                            + new LayeredImage(image.width(), image.height(), layers).toJson()
                            + line.substring(at + before.length());
                }
                recipes.write(line);
                recipes.write(index + 1 < order.size() ? ",\n" : "\n");
                String row = shard.render.get(owned.record());
                renderRows.add(index + row.substring(row.indexOf('\t')));
            }
            recipes.write("]\n");
        }
        StillTable stills = new StillTable(table);
        stills.write(out.resolve(StillTable.JSON));

        try (BufferedWriter render = Files.newBufferedWriter(out.resolve("render.tsv"), StandardCharsets.UTF_8)) {
            render.write(shards.get(0).renderHeader + "\n");
            for (String row : renderRows) render.write(row + "\n");
        }
        Files.copy(shards.get(0).directory.resolve("categories.tsv"), out.resolve("categories.tsv"));

        int unused = 0;
        for (Shard shard : shards) for (int id : shard.ids) if (id < 0) unused++;
        Result result = new Result(order.size(), stills.size(), stills.bytes(), shared, drift, inserted, lost);
        writeMeta(shards, out, result);

        StringBuilder perShard = new StringBuilder();
        for (Shard shard : shards) {
            perShard.append(perShard.isEmpty() ? "" : ", ").append(shard.index).append(": ").append(shard.lines.size())
                    .append(" recipes of ").append(shard.items.size()).append(" listed, ")
                    .append(shard.artifact.stills.size()).append(" stills");
        }
        System.out.println("merged " + shards.size() + " shards (" + perShard + ") into " + out + " in "
                + (System.nanoTime() - started) / 1_000_000L + " ms: " + result.records() + " recipes, "
                + result.stills() + " stills, " + result.stillsBytes() + " B; " + shared
                + " stills were drawn by more than one shard and kept once" + (unused > 0 ? "; " + unused
                + " stills no record used, dropped" : ""));
        System.out.println("drift: " + drift + " recipes were not in every shard's list; " + inserted
                + " that shard 0 did not list went in after their neighbours, " + lost
                + " were listed but not drawn, since the shard that owns them did not list them");
        return result;
    }

    /**
     * Shard 0's meta.json with the counts of the whole. Every record a shard drew is in the merged artifact once, so
     * its layered, fallback and failed counts add up.
     */
    private static void writeMeta(List<Shard> shards, Path out, Result result) throws IOException {
        JsonObject meta = shards.get(0).artifact.meta.deepCopy();
        meta.remove("shard");
        boolean partial = false;
        long failed = 0, layered = 0, fallback = 0;
        for (Shard shard : shards) {
            JsonElement value = shard.artifact.meta.get("partial");
            partial |= value != null && value.getAsBoolean();
            failed += shard.artifact.metaNumber("failed");
            layered += shard.artifact.metaNumber("layered");
            fallback += shard.artifact.metaNumber("fallback");
        }
        meta.addProperty("recipes", result.records());
        meta.addProperty("partial", partial);
        meta.addProperty("failed", failed);
        meta.addProperty("stills", result.stills());
        meta.addProperty("stillsBytes", result.stillsBytes());
        meta.addProperty("layered", layered);
        meta.addProperty("fallback", fallback);
        meta.addProperty("processes", shards.size());
        meta.addProperty("drift", result.drift());
        Files.writeString(out.resolve("meta.json"),
                new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(meta) + "\n",
                StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }
}
