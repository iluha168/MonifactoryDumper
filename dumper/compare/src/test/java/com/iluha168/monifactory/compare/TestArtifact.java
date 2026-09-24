package com.iluha168.monifactory.compare;

import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.WebpEncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

/**
 * A small format 2 artifact written by hand, file by file, the way {@code dumper/FORMAT.md} lays it out, so the tools
 * are tested against the format and not against the renderer's writer. Stills are real lossless WebP from
 * {@link WebpEncoder}.
 */
final class TestArtifact {
    static final int SCALE = 2;

    /** A record's {@code "image"} JSON and how many layers it has, which decides layered or fallback in meta.json. */
    record Image(String json, int layers) {
    }

    private final boolean images;
    private final WebpEncoder encoder = new WebpEncoder();
    private final ByteArrayOutputStream pak = new ByteArrayOutputStream();
    private final List<String> stillRows = new ArrayList<>();
    private final List<String> records = new ArrayList<>();
    /** meta.json fields to write instead of the ones worked out, as raw JSON. */
    final Map<String, String> meta = new LinkedHashMap<>();
    private int rendered, layered;

    private TestArtifact(boolean images) {
        this.images = images;
    }

    static TestArtifact withImages() {
        return new TestArtifact(true);
    }

    static TestArtifact dataOnly() {
        return new TestArtifact(false);
    }

    /** The canvas side for a display side. */
    static int canvas(int display) {
        return (display + VerifyArtifact.PADDING) * SCALE;
    }

    /** Opaque pixel art: flat 2x2 texels from a small palette. */
    static Frame card(int width, int height, long seed) {
        var random = new SplittableRandom(seed);
        int[] palette = new int[6];
        for (int i = 0; i < palette.length; i++) palette[i] = 0xFF000000 | random.nextInt(0x1000000);
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) argb[y * width + x] = palette[(x / 2 * 7 + y / 2 * 3) % palette.length];
        return new Frame(width, height, argb);
    }

    /** {@code picture} with one pixel changed. */
    static Frame touched(Frame picture, int x, int y) {
        int[] argb = picture.argb().clone();
        argb[y * picture.width() + x] ^= 0x00FFFFFF;
        return new Frame(picture.width(), picture.height(), argb);
    }

    int still(Frame still) {
        return still(encoder.encode(still), still.width(), still.height());
    }

    /** A still whose row in stills.json says {@code width} x {@code height}, whatever {@code webp} holds. */
    int still(byte[] webp, int width, int height) {
        stillRows.add("[" + pak.size() + "," + webp.length + "," + width + "," + height + "]");
        pak.writeBytes(webp);
        return stillRows.size() - 1;
    }

    static String layer(int x, int y, int[] stills, int[] ticks) {
        return "{\"x\":" + x + ",\"y\":" + y + ",\"f\":" + Arrays.toString(stills).replace(" ", "") + ",\"d\":"
                + Arrays.toString(ticks).replace(" ", "") + "}";
    }

    static String layer(int x, int y, int still) {
        return layer(x, y, new int[]{still}, new int[]{1});
    }

    static Image image(int width, int height, String... layers) {
        return new Image("{\"w\":" + width + ",\"h\":" + height + ",\"layers\":[" + String.join(",", layers) + "]}",
                layers.length);
    }

    /** A recipe with no varying slot. */
    TestArtifact recipe(String id, int width, int height, Image image) {
        return recipe(id, width, height, "[{\"k\":\"s\",\"t\":\"item\",\"id\":\"minecraft:stone\",\"n\":1,\"nbt\":0}]",
                image);
    }

    /** A recipe whose inputs are {@code in}, raw JSON; a null {@code image} is a failed recipe. */
    TestArtifact recipe(String id, int width, int height, String in, Image image) {
        records.add("{\"emiRecipeId\":\"test:" + id + "\",\"underlyingRecipeId\":\"test:" + id
                + "\",\"cat\":\"test:category\",\"cls\":\"test.Recipe\",\"w\":" + width + ",\"h\":" + height
                + ",\"in\":" + in + ",\"cats\":[],\"out\":[],\"image\":" + (image == null ? "null" : image.json())
                + "}");
        if (image != null) {
            rendered++;
            if (image.layers() > 1) layered++;
        }
        return this;
    }

    /** Writes the artifact into {@code directory}, which it creates. */
    Path write(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("recipes.json"), "[\n" + String.join(",\n", records) + "\n]\n",
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("categories.tsv"), "test:category\n", StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("pack", "{\"name\":\"Test\",\"version\":\"1\",\"mode\":\"Normal\"}");
        fields.put("format", "2");
        fields.put("recipes", Integer.toString(records.size()));
        fields.put("every", "1");
        fields.put("sample", "1");
        fields.put("limit", "null");
        fields.put("images", Boolean.toString(images));
        if (images) {
            Files.write(directory.resolve("stills.pak"), pak.toByteArray());
            Files.writeString(directory.resolve("stills.json"), "[\n" + String.join(",\n", stillRows) + "\n]\n",
                    StandardCharsets.UTF_8);
            fields.put("failed", Integer.toString(records.size() - rendered));
            fields.put("scale", Integer.toString(SCALE));
            fields.put("frameMillis", "50");
            fields.put("stills", Integer.toString(stillRows.size()));
            fields.put("stillsBytes", Integer.toString(pak.size()));
            fields.put("layered", Integer.toString(layered));
            fields.put("fallback", Integer.toString(rendered - layered));
        } else {
            fields.put("failed", "0");
            for (String key : new String[]{"scale", "frameMillis", "stills", "stillsBytes", "layered", "fallback"}) {
                fields.put(key, "null");
            }
        }
        fields.putAll(meta);
        Files.writeString(directory.resolve("meta.json"), fields.entrySet().stream()
                .map(field -> "  \"" + field.getKey() + "\": " + field.getValue())
                .collect(Collectors.joining(",\n", "{\n", "\n}\n")), StandardCharsets.UTF_8);
        return directory;
    }
}
