package com.iluha168.monifactory.compare;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.layered.Layer;
import com.iluha168.monifactory.imgencoder.layered.LayeredImage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Compares two artifact directories the way PLAN section 7 says rebuilds compare: as sets, never byte for byte.
 * <p>
 * Two builds of one pack version differ, because GregTech does not boot the same way twice and the build runs it as
 * shipped. What is allowed to differ is written down, and anything else is a regression:
 * <ul>
 *     <li>The recipe set may differ only by GregTech's recycling flicker ({@link #FLICKER}): recipe conflicts that
 *     GregTech resolves by identity-hash iteration order, and a recursively resolved fluid amount that varies per boot.
 *     </li>
 *     <li>The recipe set may also differ in recipes that come through TooManyRecipeViewers, and in EMI's info pages.
 *     Their counts move in every production boot measured since M1 (for example emi:info 1,052 to 1,073 and
 *     chipped:botanist_workbench 1,046 to 1,060 over eight boots), which PLAN section 5 never saw because its drift
 *     boots ran without TMRV. The cause is not diagnosed.</li>
 *     <li>A recipe's image may differ only if the recipe has a slot whose content the pack picks per boot: a tag (its
 *     member order is not stable), a list of options, a stack with NBT (the variant), or a GregTech multiblock's
 *     representative part. Each changed region must fit in one GUI slot. Recipes with neither id are matched by their
 *     content, so these are also what may make two such records fail to match.</li>
 *     <li>Or the recipe lists the same stacks in another order (NuclearCraft's placement rules come out of a hash set),
 *     so the same items sit in different slots.</li>
 * </ul>
 * <p>
 * Records are paired by category, class and ids where a recipe has an id; recipes with neither id are paired by
 * category, class, size and content. Within a key, records with equal content pair first.
 * <p>
 * A picture is compared as its frame 0, composited from its layers (DESIGN 2.2), and it counts as animated when any
 * layer shows more than one still. Still ids differ between two builds, since they follow what each build drew first,
 * so two pictures are the same without decoding anything when their layers match box for box and timeline for
 * timeline and every still they name has the same bytes on both sides. The encoder is deterministic, so equal pixels
 * give equal bytes; only pictures that fail that are composited. Many recipes share stills (every card of a category
 * is one), so each side keeps decoded stills in a bounded cache.
 * <p>
 * Usage: {@code --a <dir> --b <dir> [--out <tsv>]}. Exits 1 if anything is unexplained.
 */
public final class CompareDumps {
    /**
     * GregTech's recycling flicker, from the drift investigation behind PLAN section 5: the arc furnace and macerator
     * recycling recipes GregTech generates, two pack recycling recipes they collide with, and two recipes that lose a
     * first-wins conflict in some boots.
     */
    static final Pattern FLICKER = Pattern.compile("gtceu:(?:arc_furnace/arc_.*|macerator/macerate_.*"
            + "|arc_furnace/(?:bbc|rhf)_recycling|large_boiler/gtceu_charcoal_block"
            + "|forge_hammer/hammer_nether_star_block_to_gem)");

    /**
     * Categories whose cards show a representative part the pack picks per boot (GregTech's multiblock previews pick one
     * of several hatches), from the same drift investigation.
     */
    static final java.util.Set<String> REPRESENTATIVE = java.util.Set.of("gtceu:multiblock_info");

    /** The classes TooManyRecipeViewers makes EMI recipes of. */
    static final String TMRV = "dev.nolij.toomanyrecipeviewers.";

    /** A recipe's slot is 18 GUI pixels square. */
    static final int SLOT = 18;
    /** EMI's screenshot padding, in GUI pixels, which the image adds to the display size. */
    static final int PADDING = 8;
    /** Decoded pixels each side keeps: 128 MiB, so both sides fit beside two corpora of records in the task's heap. */
    static final long CACHED_PIXELS = 32L << 20;

    record Recipe(int index, String emiId, String underlyingId, String category, String cls, int width, int height,
                  String content, String unordered, String looseContent, boolean tag, boolean list, boolean nbt,
                  LayeredImage image) {
        /** Drawn, and nothing in it moves. */
        boolean still() {
            return image != null && !image.animated();
        }

        boolean flicker() {
            return (emiId != null && FLICKER.matcher(emiId).matches())
                    || (underlyingId != null && FLICKER.matcher(underlyingId).matches());
        }

        /** Whether some slot shows content the pack picks per boot. */
        boolean varying() {
            return tag || list || nbt || REPRESENTATIVE.contains(category);
        }

        /**
         * Whether the recipe comes through a bridge whose recipe count moves from boot to boot: TooManyRecipeViewers'
         * JEI recipes, and EMI's info pages.
         */
        boolean bridged() {
            return cls.startsWith(TMRV) || category.equals("emi:info");
        }

        String key() {
            if (emiId != null || underlyingId != null) {
                return "id\t" + category + "\t" + cls + "\t" + emiId + "\t" + underlyingId;
            }
            return "content\t" + category + "\t" + cls + "\t" + width + "x" + height + "\t" + content;
        }

        String looseKey() {
            return category + "\t" + cls + "\t" + width + "x" + height + "\t" + looseContent;
        }

        String name() {
            return (emiId != null ? emiId : underlyingId != null ? underlyingId : "#" + index) + " (" + category + ")";
        }
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        Path a = Path.of(Objects.requireNonNull(options.get("a"), "--a <artifact dir>"));
        Path b = Path.of(Objects.requireNonNull(options.get("b"), "--b <artifact dir>"));
        Path out = options.containsKey("out") ? Path.of(options.get("out")) : null;
        int unexplained = new CompareDumps(a, b, out).run();
        System.exit(unexplained == 0 ? 0 : 1);
    }

    private final Path a, b, out;
    /** kind -> count, in the order they are first seen. */
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> byCategory = new TreeMap<>();
    private final List<String> rows = new ArrayList<>();
    private int unexplained;

    CompareDumps(Path a, Path b, Path out) {
        this.a = a;
        this.b = b;
        this.out = out;
    }

    /** How many pairs of each kind, in the order the kinds were first seen. */
    Map<String, Integer> counts() {
        return counts;
    }

    /**
     * Compares the two artifacts, prints what it found, and returns how many differences are not explained. Throws if
     * either is not a readable format 2 artifact.
     */
    int run() throws IOException {
        try (Artifact artifactA = Artifact.open(a); Artifact artifactB = Artifact.open(b)) {
            return run(new Stills(artifactA), new Stills(artifactB));
        }
    }

    private int run(Stills stillsA, Stills stillsB) throws IOException {
        // Two samples drawn differently share almost no recipes, and every one of them would read as a regression.
        String sampleA = sampling(stillsA.artifact), sampleB = sampling(stillsB.artifact);
        if (!sampleA.equals(sampleB) || sampleA.matches("every=(?!1 ).*")) {
            // every=N picks by position, and EMI's list drifts between boots, so two such samples hold different
            // recipes even when drawn alike. sample=N picks by what the recipe is.
            System.out.println("DISAGREE: the artifacts cannot be compared recipe for recipe (A " + sampleA + ", B "
                    + sampleB + "); build both whole, or both with the same -Pmonifactory.dumper=sample=N");
            return 1;
        }
        List<Recipe> left = load(stillsA.artifact);
        List<Recipe> right = load(stillsB.artifact);
        System.out.printf("A %s: %,d recipes, %s%n", a, left.size(), summary(left, stillsA.artifact));
        System.out.printf("B %s: %,d recipes, %s%n", b, right.size(), summary(right, stillsB.artifact));

        Map<String, ArrayDeque<Recipe>> byKey = new LinkedHashMap<>();
        for (Recipe recipe : right) byKey.computeIfAbsent(recipe.key(), k -> new ArrayDeque<>()).add(recipe);
        Map<String, List<Recipe>> leftByKey = new LinkedHashMap<>();
        for (Recipe recipe : left) leftByKey.computeIfAbsent(recipe.key(), k -> new ArrayList<>()).add(recipe);

        List<Recipe[]> pairs = new ArrayList<>();
        List<Recipe> onlyA = new ArrayList<>(), onlyB = new ArrayList<>();
        for (Map.Entry<String, List<Recipe>> group : leftByKey.entrySet()) {
            ArrayDeque<Recipe> candidates = byKey.getOrDefault(group.getKey(), new ArrayDeque<>());
            List<Recipe> unpaired = new ArrayList<>();
            // Equal content first, so a key shared by several recipes pairs each with its own counterpart.
            for (Recipe mine : group.getValue()) {
                Recipe match = null;
                for (Iterator<Recipe> it = candidates.iterator(); it.hasNext(); ) {
                    Recipe theirs = it.next();
                    if (theirs.content().equals(mine.content())) {
                        match = theirs;
                        it.remove();
                        break;
                    }
                }
                if (match != null) pairs.add(new Recipe[]{mine, match});
                else unpaired.add(mine);
            }
            for (Recipe mine : unpaired) {
                Recipe theirs = candidates.poll();
                if (theirs != null) pairs.add(new Recipe[]{mine, theirs});
                else onlyA.add(mine);
            }
        }
        for (ArrayDeque<Recipe> rest : byKey.values()) onlyB.addAll(rest);

        // A recipe with no id is keyed by its content, so a slot that varies per boot keeps it from pairing. Such
        // records pair a second time on their content without what varies: NBT hashes and keys, enchantments, list
        // members, and the order of each stack list.
        Map<String, ArrayDeque<Recipe>> loose = new LinkedHashMap<>();
        for (Recipe theirs : onlyB) {
            loose.computeIfAbsent(theirs.looseKey(), k -> new ArrayDeque<>()).add(theirs);
        }
        List<Recipe> strayA = new ArrayList<>();
        for (Recipe mine : onlyA) {
            ArrayDeque<Recipe> candidates = loose.get(mine.looseKey());
            Recipe theirs = candidates == null ? null : candidates.poll();
            if (theirs != null) {
                pairs.add(new Recipe[]{mine, theirs});
                onlyB.remove(theirs);
            } else {
                strayA.add(mine);
            }
        }
        strayA.forEach(recipe -> onlyIn("A", recipe));
        onlyB.forEach(recipe -> onlyIn("B", recipe));

        for (Recipe[] pair : pairs) compare(pair[0], pair[1], stillsA, stillsB);

        System.out.printf("paired %,d recipes%n", pairs.size());
        counts.forEach((kind, n) -> System.out.printf("  %-34s %,d%n", kind, n));
        System.out.println("by category:");
        byCategory.forEach((category, kinds) -> System.out.printf("  %-44s %s%n", category, kinds));
        if (out != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                writer.write("kind\trecipe\tdetail\n");
                for (String row : rows) writer.write(row + "\n");
            }
            System.out.println("every difference: " + out);
        }
        System.out.println(unexplained == 0 ? "AGREE: every difference is a documented one"
                : "DISAGREE: " + unexplained + " differences are not explained");
        return unexplained;
    }

    /** How meta.json says the artifact was sampled. */
    static String sampling(Artifact artifact) {
        JsonObject meta = artifact.meta;
        return "every=" + meta.get("every") + " sample=" + meta.get("sample") + " limit=" + meta.get("limit");
    }

    private static String summary(List<Recipe> recipes, Artifact artifact) {
        long still = 0, animated = 0;
        for (Recipe recipe : recipes) {
            if (recipe.still()) still++;
            else if (recipe.image() != null) animated++;
        }
        String stills = artifact.images() ? String.format("; %,d stills in %,d B", artifact.stills.size(),
                artifact.stills.bytes()) : "; data only";
        return String.format("%,d static, %,d animated, %,d without a picture%s", still, animated,
                recipes.size() - still - animated, stills);
    }

    private void onlyIn(String side, Recipe recipe) {
        String why = recipe.flicker() ? "GT flicker" : recipe.bridged() ? "TMRV/info drift" : "UNEXPLAINED";
        record("only in " + side + ", " + why, recipe, "", why.equals("UNEXPLAINED"));
    }

    private void compare(Recipe x, Recipe y, Stills stillsA, Stills stillsB) throws IOException {
        boolean sameContent = x.content().equals(y.content());
        // The same stacks in another order: a mod listed them out of a hash set, so which slot shows which moves.
        boolean reordered = !sameContent && x.unordered().equals(y.unordered());
        if (reordered) {
            record("content differs, slot order", x, "", false);
        } else if (!sameContent) {
            // The boot-varying kinds of slot, or the flicker's fluid amounts.
            boolean explained = x.flicker() || (x.varying() && y.varying());
            record(explained ? "content differs, varying slot" : "content differs, UNEXPLAINED", x, "", !explained);
        }
        boolean staticX = x.still(), staticY = y.still();
        if (!staticX && !staticY) {
            count(x.image() == null && y.image() == null ? "both not rendered" : "both animated", x);
            return;
        }
        if (staticX != staticY) {
            // Static in one build and animated in the other: what one slot shows decided whether anything moved.
            boolean explained = x.varying() || y.varying();
            record(explained ? "static in one only, varying slot" : "static in one only, UNEXPLAINED", x,
                    "A " + describe(x) + ", B " + describe(y), !explained);
            return;
        }
        if (sameStills(x.image(), stillsA, y.image(), stillsB)) {
            count("static, identical", x);
            return;
        }
        Frame pictureX, pictureY;
        try {
            pictureX = x.image().frameAt(0, stillsA::frame);
            pictureY = y.image().frameAt(0, stillsB::frame);
        } catch (UncheckedIOException | IllegalStateException e) {
            record("static, UNDECODABLE", x, e.getMessage(), true);
            return;
        }
        if (pictureX.samePixels(pictureY)) {
            // Layered in one build and drawn whole in the other, or cut into layers another way, to the same picture.
            count("static, same picture from other layers", x);
            return;
        }
        if (pictureX.width() != pictureY.width() || pictureX.height() != pictureY.height()) {
            record("static, size differs, UNEXPLAINED", x, pictureX.width() + "x" + pictureX.height() + " vs "
                    + pictureY.width() + "x" + pictureY.height(), true);
            return;
        }
        int width = pictureX.width(), height = pictureX.height();
        int[] px = pictureX.argb();
        int[] py = pictureY.argb();
        int scale = Math.max(1, width / (x.width() + PADDING));
        List<int[]> regions = regions(px, py, width, height);
        int slot = SLOT * scale;
        int largest = 0;
        boolean fits = true;
        StringBuilder detail = new StringBuilder();
        for (int[] region : regions) {
            int w = region[2] - region[0] + 1, h = region[3] - region[1] + 1;
            largest = Math.max(largest, Math.max(w, h));
            fits &= w <= slot && h <= slot;
            if (detail.length() < 200) {
                detail.append(w).append('x').append(h).append('@').append(region[0]).append(',').append(region[1])
                        .append(' ');
            }
        }
        String kind;
        boolean bad;
        if (reordered) {
            // Nothing to size: the stacks are the same ones, and every slot that moved shows a difference.
            kind = "static, pixels differ, slot order";
            bad = false;
        } else if (!x.varying()) {
            kind = "static, pixels differ, UNEXPLAINED";
            bad = true;
        } else if (fits) {
            kind = "static, pixels differ in varying slots";
            bad = false;
        } else {
            kind = "static, pixels differ past a slot, UNEXPLAINED";
            bad = true;
        }
        record(kind, x, regions.size() + " regions, largest " + largest + " px: " + detail.toString().trim(), bad);
    }

    private static String describe(Recipe recipe) {
        if (recipe.image() == null) return "no picture";
        int animated = 0;
        for (Layer layer : recipe.image().layers()) if (layer.loop().animated()) animated++;
        return recipe.image().layers().size() + " layers, " + animated + " animated";
    }

    /**
     * Whether {@code x} and {@code y} are the same picture by construction: the same canvas, the same boxes and
     * timelines layer for layer, and stills with the same bytes wherever their ids differ.
     */
    private static boolean sameStills(LayeredImage x, Stills stillsX, LayeredImage y, Stills stillsY)
            throws IOException {
        if (x.width() != y.width() || x.height() != y.height() || x.layers().size() != y.layers().size()) return false;
        for (int i = 0; i < x.layers().size(); i++) {
            Layer lx = x.layers().get(i), ly = y.layers().get(i);
            if (lx.x() != ly.x() || lx.y() != ly.y() || lx.width() != ly.width() || lx.height() != ly.height()
                    || lx.loop().size() != ly.loop().size()) return false;
            for (int k = 0; k < lx.loop().size(); k++) {
                if (lx.loop().ticks(k) != ly.loop().ticks(k)) return false;
                if (!Arrays.equals(stillsX.digest(lx.loop().still(k)), stillsY.digest(ly.loop().still(k)))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * One artifact's stills as the comparison needs them: a digest of each one's bytes, worked out once, and decoded
     * pixels, kept while they fit in {@link #CACHED_PIXELS} and dropped least recently used first.
     */
    private static final class Stills {
        final Artifact artifact;
        private final byte[][] digests;
        private final LinkedHashMap<Integer, Frame> decoded = new LinkedHashMap<>(1024, 0.75f, true);
        private final MessageDigest sha;
        private long pixels;

        Stills(Artifact artifact) {
            this.artifact = artifact;
            this.digests = new byte[artifact.images() ? artifact.stills.size() : 0][];
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("every JVM has SHA-256", e);
            }
        }

        byte[] digest(int id) throws IOException {
            if (digests[id] == null) digests[id] = sha.digest(artifact.stillBytes(id));
            return digests[id];
        }

        /** Still {@code id}, decoded; unchecked, since {@link LayeredImage#frameAt} takes a plain function. */
        Frame frame(int id) {
            Frame still = decoded.get(id);
            if (still != null) return still;
            try {
                still = artifact.still(id);
            } catch (IOException e) {
                throw new UncheckedIOException("still " + id + " of " + artifact.directory + ": " + e.getMessage(), e);
            }
            decoded.put(id, still);
            pixels += still.argb().length;
            for (Iterator<Frame> it = decoded.values().iterator(); pixels > CACHED_PIXELS && it.hasNext(); ) {
                Frame eldest = it.next();
                if (eldest == still) break;
                pixels -= eldest.argb().length;
                it.remove();
            }
            return still;
        }
    }

    /**
     * The 8-connected regions of differing pixels, as {@code {minX, minY, maxX, maxY}}. Two pixels closer than
     * {@code 2} apart join, so one changed item does not come apart at a pixel of its outline that happens to match.
     */
    static List<int[]> regions(int[] px, int[] py, int width, int height) {
        boolean[] diff = new boolean[px.length];
        for (int i = 0; i < px.length; i++) diff[i] = px[i] != py[i];
        boolean[] seen = new boolean[px.length];
        List<int[]> regions = new ArrayList<>();
        int[] queue = new int[px.length];
        for (int start = 0; start < px.length; start++) {
            if (!diff[start] || seen[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int[] box = {start % width, start / width, start % width, start / width};
            while (head < tail) {
                int at = queue[head++];
                int cx = at % width, cy = at / width;
                box[0] = Math.min(box[0], cx);
                box[1] = Math.min(box[1], cy);
                box[2] = Math.max(box[2], cx);
                box[3] = Math.max(box[3], cy);
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                        int next = ny * width + nx;
                        if (diff[next] && !seen[next]) {
                            seen[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
            }
            regions.add(box);
        }
        return regions;
    }

    private void count(String kind, Recipe recipe) {
        counts.merge(kind, 1, Integer::sum);
        byCategory.computeIfAbsent(recipe.category(), k -> new TreeMap<>()).merge(kind, 1, Integer::sum);
    }

    private void record(String kind, Recipe recipe, String detail, boolean bad) {
        count(kind, recipe);
        rows.add(kind + "\t" + recipe.name() + "\t" + detail);
        if (bad) unexplained++;
    }

    static List<Recipe> load(Artifact artifact) throws IOException {
        List<Recipe> recipes = new ArrayList<>();
        artifact.records((index, json) -> {
            boolean[] flags = new boolean[3];
            StringBuilder content = new StringBuilder(), unordered = new StringBuilder(), loose = new StringBuilder();
            for (String list : new String[]{"in", "cats", "out"}) {
                JsonElement stacks = json.get(list);
                content.append(list).append('=').append(canonical(stacks, flags, false)).append(';');
                unordered.append(list).append('=').append(sorted(stacks, false)).append(';');
                loose.append(list).append('=').append(sorted(stacks, true)).append(';');
            }
            LayeredImage image;
            try {
                image = artifact.image(json);
            } catch (IllegalArgumentException e) {
                throw new IOException(artifact.directory + " " + Artifact.name(index, json) + ": " + e.getMessage()
                        + "; run verifyArtifact on it", e);
            }
            recipes.add(new Recipe(index, Artifact.string(json, "emiRecipeId"),
                    Artifact.string(json, "underlyingRecipeId"), Artifact.string(json, "cat"),
                    Artifact.string(json, "cls"), json.get("w").getAsInt(), json.get("h").getAsInt(),
                    content.toString(), unordered.toString(), loose.toString(), flags[0], flags[1], flags[2], image));
        });
        return recipes;
    }

    /** A stack list as {@link #canonical} text, its stacks sorted, so only which stacks are in it counts. */
    static String sorted(JsonElement stacks, boolean loose) {
        if (stacks == null || !stacks.isJsonArray()) return canonical(stacks, new boolean[3], loose);
        List<String> items = new ArrayList<>();
        for (JsonElement stack : stacks.getAsJsonArray()) items.add(canonical(stack, new boolean[3], loose));
        items.sort(null);
        return String.join(",", items);
    }

    /**
     * A stack list as text with object keys sorted and a list ingredient's member ids sorted, since their order is
     * one of the things that varies per boot. Notes in {@code flags} whether it holds a tag, a list or an NBT stack.
     * {@code loose} leaves out what varies: NBT hashes and keys, enchantments and list members.
     */
    static String canonical(JsonElement element, boolean[] flags, boolean loose) {
        if (element == null || element.isJsonNull()) return "null";
        if (element.isJsonArray()) {
            StringBuilder out = new StringBuilder("[");
            for (JsonElement item : element.getAsJsonArray()) out.append(canonical(item, flags, loose)).append(',');
            return out.append(']').toString();
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String kind = Artifact.string(object, "k");
            if ("t".equals(kind)) flags[0] = true;
            if ("m".equals(kind)) flags[1] = true;
            if (object.has("nbt") && object.get("nbt").isJsonPrimitive() && object.get("nbt").getAsInt() == 1) {
                flags[2] = true;
            }
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            object.entrySet().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                JsonElement value = entry.getValue();
                if (loose && (entry.getKey().equals("nbth") || entry.getKey().equals("nbtk")
                        || entry.getKey().equals("ench") || entry.getKey().equals("ids"))) continue;
                if ("m".equals(kind) && entry.getKey().equals("ids") && value.isJsonArray()) {
                    List<String> ids = new ArrayList<>();
                    for (JsonElement id : value.getAsJsonArray()) ids.add(id.toString());
                    ids.sort(null);
                    JsonArray ordered = new JsonArray();
                    ids.forEach(id -> ordered.add(JsonParser.parseString(id)));
                    value = ordered;
                }
                out.append(entry.getKey()).append(':').append(canonical(value, flags, loose)).append(',');
            }
            return out.append('}').toString();
        }
        return element.toString();
    }
}
