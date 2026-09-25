package com.iluha168.monifactory.compare;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.layered.StillTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.iluha168.monifactory.compare.TestArtifact.canvas;
import static com.iluha168.monifactory.compare.TestArtifact.card;
import static com.iluha168.monifactory.compare.TestArtifact.image;
import static com.iluha168.monifactory.compare.TestArtifact.layer;
import static org.junit.jupiter.api.Assertions.*;

class MergeShardsTest {
    /** Display size of the test recipes: a 24x24 canvas at scale 2. */
    private static final int W = 4, H = 4;
    private static final Frame CARD = card(canvas(W), canvas(H), 1);

    @TempDir
    Path dir;

    /**
     * One game's artifact of a sharded build: every recipe it listed, in its order, and the ones it drew. A drawn recipe
     * is the shared card with one widget layer, whose picture is its seed's; the shard stores each distinct still once,
     * as the renderer does.
     */
    private static final class Shard {
        final TestArtifact artifact = TestArtifact.withImages();
        final List<String> tsv = new ArrayList<>(List.of("record\tkey"));
        final List<String> render = new ArrayList<>();
        final Map<Long, Integer> stills = new HashMap<>();
        /** Per still id, its row of still_uses.json. */
        final List<String> uses = new ArrayList<>();
        /** Rows by seed other than {@link #row}'s. */
        final Map<Long, String> rows = new HashMap<>();
        int owned;

        Shard(int index, int count) {
            artifact.meta.put("shard", "{\"index\":" + index + ",\"count\":" + count + "}");
            artifact.meta.put("partial", "false");
        }

        /** Listed here, drawn by another shard. */
        Shard listed(String key) {
            tsv.add("\t" + key);
            return this;
        }

        /** Listed and drawn here, with a widget that shows picture {@code widget}. */
        Shard drawn(String key, long widget) {
            int card = still(0), picture = still(widget);
            artifact.recipe(key, W, H, image(canvas(W), canvas(H), layer(0, 0, card), layer(2, 2, picture)));
            tsv.add(owned + "\t" + key);
            render.add(owned + "\ttest:" + key + "\ttest:category\ttest.Recipe\t2\t0\t1\tlayered\t\t0");
            owned++;
            return this;
        }

        /** What drew the still of {@code seed}, from here on. */
        Shard uses(long seed, String row) {
            rows.put(seed, row);
            return this;
        }

        /** The card's still is drawn from EMI's widget texture, a widget's from an item named after its seed. */
        static String row(long seed) {
            return seed == 0 ? "{\"textures\":[\"emi:textures/gui/widgets.png\"]}"
                    : "{\"items\":[\"test:item_" + seed + "\"]}";
        }

        private int still(long seed) {
            return stills.computeIfAbsent(seed, s -> {
                int id = artifact.still(s == 0 ? CARD : card(6, 6, s));
                uses.add(rows.getOrDefault(s, row(s)));
                return id;
            });
        }

        Path write(Path directory) throws IOException {
            artifact.write(directory);
            Files.write(directory.resolve(MergeShards.SHARD_FILE), tsv, StandardCharsets.UTF_8);
            List<String> rows = new ArrayList<>(List.of("index\temiRecipeId\tcategory\tclass\tlayers\tanimatedLayers"
                    + "\tframesDrawn\tmode\treason\tmillis"));
            rows.addAll(render);
            Files.write(directory.resolve("render.tsv"), rows, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("categories.tsv"), "category\trecipes\tdropped\n", StandardCharsets.UTF_8);
            StillTable.writeUses(directory.resolve(StillTable.USES), uses);
            return directory;
        }
    }

    private MergeShards.Result merge(Path out, Path... shards) throws Exception {
        MergeShards.Result result = MergeShards.merge(List.of(shards), out);
        assertEquals(List.of(), VerifyArtifact.verify(out, 2), "the merged artifact verifies");
        return result;
    }

    /** Each record's id and its layers' still ids, in merged order, e.g. "a 0 1". */
    private static List<String> records(Path artifact) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(artifact.resolve("recipes.json"), StandardCharsets.UTF_8)) {
            if (line.equals("[") || line.equals("]")) continue;
            JsonObject record = JsonParser.parseString(line.replaceAll(",$", "")).getAsJsonObject();
            StringBuilder row = new StringBuilder(record.get("emiRecipeId").getAsString().substring("test:".length()));
            record.getAsJsonObject("image").getAsJsonArray("layers")
                    .forEach(layer -> layer.getAsJsonObject().getAsJsonArray("f").forEach(f -> row.append(' ').append(f)));
            out.add(row.toString());
        }
        return out;
    }

    private static JsonObject meta(Path artifact) throws IOException {
        return JsonParser.parseString(Files.readString(artifact.resolve("meta.json"))).getAsJsonObject();
    }

    @Test
    void stillsDrawnByTwoShardsAreStoredOnce() throws Exception {
        Path a = new Shard(0, 2).drawn("a", 7).listed("b").write(dir.resolve("0"));
        Path b = new Shard(1, 2).listed("a").drawn("b", 7).write(dir.resolve("1"));
        MergeShards.Result result = merge(dir.resolve("out"), a, b);

        // The card and the widget are the same pictures in both shards, and each is stored once.
        assertEquals(List.of("a 0 1", "b 0 1"), records(dir.resolve("out")));
        assertEquals(2, result.stills());
        assertEquals(2, result.sharedStills());
        assertEquals(Files.size(a.resolve("stills.pak")), Files.size(dir.resolve("out/stills.pak")));
        assertArrayEquals(Files.readAllBytes(a.resolve("stills.pak")), Files.readAllBytes(dir.resolve("out/stills.pak")));
    }

    /** The rows of an artifact's still_uses.json, in still id order. */
    private static List<String> uses(Path artifact) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(artifact.resolve(StillTable.USES), StandardCharsets.UTF_8)) {
            if (!line.equals("[") && !line.equals("]")) out.add(line.replaceAll(",$", ""));
        }
        return out;
    }

    @Test
    void usesFollowTheirStillsAndAStillSeveralShardsDrewGetsEveryShardsUses() throws Exception {
        Path s0 = new Shard(0, 2).listed("a").uses(20, "{\"items\":[\"test:b\"],\"texts\":[\"1 < 2\"]}")
                .drawn("b", 20).listed("c").write(dir.resolve("0"));
        Path s1 = new Shard(1, 2).uses(0, "{\"textures\":[\"test:card.png\",\"emi:textures/gui/widgets.png\"]}")
                .drawn("a", 10).listed("b").uses(20, "{\"textures\":[\"test:t.png\"],\"items\":[\"test:b2\"]}")
                .drawn("c", 20).write(dir.resolve("1"));
        merge(dir.resolve("out"), s0, s1);

        // a's widget is shard 1's still, b's and c's widgets one picture both shards drew.
        assertEquals(List.of("a 0 1", "b 0 2", "c 0 2"), records(dir.resolve("out")));
        assertEquals(List.of(
                "{\"textures\":[\"test:card.png\",\"emi:textures/gui/widgets.png\"]}",
                "{\"items\":[\"test:item_10\"]}",
                "{\"textures\":[\"test:t.png\"],\"items\":[\"test:b\",\"test:b2\"],\"texts\":[\"1 < 2\"]}"),
                uses(dir.resolve("out")));
    }

    @Test
    void stillIdsFollowFirstReferenceInMergedOrder() throws Exception {
        // Shard 1 drew a and c, so its still ids are card 0, a's 1, c's 2; shard 0 drew b: card 0, b's 1.
        Path s0 = new Shard(0, 2).listed("a").drawn("b", 20).listed("c").write(dir.resolve("0"));
        Path s1 = new Shard(1, 2).drawn("a", 10).listed("b").drawn("c", 30).write(dir.resolve("1"));
        merge(dir.resolve("out"), s0, s1);

        assertEquals(List.of("a 0 1", "b 0 2", "c 0 3"), records(dir.resolve("out")));
        JsonObject meta = meta(dir.resolve("out"));
        assertEquals(3, meta.get("recipes").getAsInt());
        assertEquals(4, meta.get("stills").getAsInt());
        assertEquals(2, meta.get("processes").getAsInt());
        assertFalse(meta.has("shard"));
        List<String> render = Files.readAllLines(dir.resolve("out/render.tsv"));
        assertEquals(4, render.size());
        assertTrue(render.get(1).startsWith("0\ttest:a\t"));
        assertTrue(render.get(2).startsWith("1\ttest:b\t"));
        assertTrue(render.get(3).startsWith("2\ttest:c\t"));
    }

    @Test
    void theOrderDoesNotDependOnWhichShardCameFirst() throws Exception {
        Path s0 = new Shard(0, 3).drawn("a", 1).listed("b").listed("c").listed("d").write(dir.resolve("0"));
        Path s1 = new Shard(1, 3).listed("a").drawn("b", 2).listed("c").drawn("x", 5).listed("d")
                .write(dir.resolve("1"));
        Path s2 = new Shard(2, 3).listed("a").listed("b").drawn("c", 3).drawn("d", 4).write(dir.resolve("2"));
        merge(dir.resolve("one"), s0, s1, s2);
        // Handed over in the order they might have finished in.
        merge(dir.resolve("two"), s2, s0, s1);

        for (String file : List.of("recipes.json", "stills.pak", "stills.json", StillTable.USES, "render.tsv", "meta.json",
                "categories.tsv", VerifyArtifact.LANG, VerifyArtifact.MATTER_NAMES, VerifyArtifact.TAGS)) {
            assertArrayEquals(Files.readAllBytes(dir.resolve("one").resolve(file)),
                    Files.readAllBytes(dir.resolve("two").resolve(file)), file);
        }
        assertEquals(List.of("a 0 1", "b 0 2", "c 0 3", "x 0 4", "d 0 5"), records(dir.resolve("one")));
    }

    @Test
    void aRecipeTwoShardsListedIsInTheArtifactOnce() throws Exception {
        Path s0 = new Shard(0, 2).listed("a").drawn("b", 2).write(dir.resolve("0"));
        Path s1 = new Shard(1, 2).drawn("a", 1).listed("b").write(dir.resolve("1"));
        MergeShards.Result result = merge(dir.resolve("out"), s0, s1);

        assertEquals(List.of("a 0 1", "b 0 2"), records(dir.resolve("out")));
        assertEquals(0, result.drift());
    }

    @Test
    void driftIsPlacedByTheOwnersListAndCounted() throws Exception {
        // Shard 1's boot has x and y, which shard 0's does not; shard 0's has z, which nobody drew: its owner, shard 1,
        // did not list it.
        Path s0 = new Shard(0, 2).drawn("a", 1).listed("z").drawn("b", 2).write(dir.resolve("0"));
        Path s1 = new Shard(1, 2).drawn("y", 5).listed("a").drawn("x", 4).listed("b").write(dir.resolve("1"));
        MergeShards.Result result = merge(dir.resolve("out"), s0, s1);

        assertEquals(List.of("y 0 1", "a 0 2", "x 0 3", "b 0 4"), records(dir.resolve("out")));
        assertEquals(3, result.drift());
        assertEquals(2, result.inserted());
        assertEquals(1, result.lost());
        assertEquals(3, meta(dir.resolve("out")).get("drift").getAsInt());
    }

    @Test
    void recipesSharingAKeyAreToldApartByOccurrence() throws Exception {
        // Three recipes with key k; shard 1's boot has a fourth.
        Path s0 = new Shard(0, 2).listed("k").drawn("a", 1).listed("k").listed("k").write(dir.resolve("0"));
        Path s1 = new Shard(1, 2).drawn("k", 2).listed("a").drawn("k", 3).drawn("k", 4).drawn("k", 5)
                .write(dir.resolve("1"));
        merge(dir.resolve("out"), s0, s1);

        assertEquals(List.of("k 0 1", "a 0 2", "k 0 3", "k 0 4", "k 0 5"), records(dir.resolve("out")));
    }

    @Test
    void differentPacksAreRefused() throws Exception {
        Path s0 = new Shard(0, 2).drawn("a", 1).write(dir.resolve("0"));
        Shard other = new Shard(1, 2).drawn("b", 2);
        other.artifact.meta.put("pack", "{\"name\":\"Test\",\"version\":\"2\",\"mode\":\"Normal\"}");
        Path s1 = other.write(dir.resolve("1"));

        IOException e = assertThrows(IOException.class, () -> MergeShards.merge(List.of(s0, s1), dir.resolve("out")));
        assertTrue(e.getMessage().contains("\"pack\""), e.getMessage());
        assertFalse(Files.exists(dir.resolve("out")));
    }

    @Test
    void sharedFilesAreShardZerosAndADifferenceIsNamed() throws Exception {
        Path s0 = new Shard(0, 3).drawn("a", 1).write(dir.resolve("0"));
        Path s1 = new Shard(1, 3).drawn("b", 2).write(dir.resolve("1"));
        Path s2 = new Shard(2, 3).drawn("c", 3).write(dir.resolve("2"));
        Files.writeString(s1.resolve(VerifyArtifact.TAGS), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(s2.resolve(VerifyArtifact.LANG), "{}\n", StandardCharsets.UTF_8);

        MergeShards.Result result = merge(dir.resolve("out"), s0, s1, s2);
        assertEquals(List.of("lang.json of shard 2", "tags.json of shard 1"), result.unlike());
        for (String file : VerifyArtifact.SHARED) {
            assertArrayEquals(Files.readAllBytes(s0.resolve(file)), Files.readAllBytes(dir.resolve("out").resolve(file)),
                    file);
        }
    }

    @Test
    void aMissingOrRepeatedShardIsRefused() throws Exception {
        Path s0 = new Shard(0, 3).drawn("a", 1).write(dir.resolve("0"));
        Path s1 = new Shard(1, 3).drawn("b", 2).write(dir.resolve("1"));
        assertThrows(IOException.class, () -> MergeShards.merge(List.of(s0, s1), dir.resolve("out")));
        assertThrows(IOException.class, () -> MergeShards.merge(List.of(s0, s1, s1), dir.resolve("out")));
    }

    @Test
    void twoShardsDrawingOneRecipeAreRefused() throws Exception {
        Path s0 = new Shard(0, 2).drawn("a", 1).write(dir.resolve("0"));
        Path s1 = new Shard(1, 2).drawn("a", 1).write(dir.resolve("1"));
        IOException e = assertThrows(IOException.class, () -> MergeShards.merge(List.of(s0, s1), dir.resolve("out")));
        assertTrue(e.getMessage().contains("both drew"), e.getMessage());
    }
}
