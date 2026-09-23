package com.iluha168.monifactory.compare;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.PakReader;
import com.iluha168.monifactory.imgencoder.layered.Layer;
import com.iluha168.monifactory.imgencoder.layered.LayeredImage;
import com.iluha168.monifactory.imgencoder.layered.StillEntry;
import com.iluha168.monifactory.imgencoder.layered.StillTable;
import com.iluha168.monifactory.imgencoder.layered.Timeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * An artifact directory in format 2 (DESIGN section 1), opened for reading: its {@code meta.json}, its still table
 * and {@code stills.pak}, and a way through {@code recipes.json} one record at a time.
 * <p>
 * Opening checks only what the rest cannot do without: that this is format 2 at all, and, for an artifact with
 * images, that {@code stills.json} parses into a {@link StillTable}. Everything else is left for
 * {@link VerifyArtifact} to find and report, so it can list every problem instead of stopping at the first.
 * <p>
 * Pictures come back as the {@code imgencoder} format types, whose constructors hold every rule of DESIGN 2.1. A
 * record that breaks one does not parse, and the exception says which rule.
 */
final class Artifact implements AutoCloseable {
    /** The only format these tools read. */
    static final int FORMAT = 2;

    /** One record of {@code recipes.json}, with its position in the file. */
    interface RecordVisitor {
        void visit(int index, JsonObject record) throws IOException;
    }

    final Path directory;
    final JsonObject meta;
    /** Null in a data-only artifact. */
    final StillTable stills;
    /** Null in a data-only artifact. */
    final PakReader pak;

    private Artifact(Path directory, JsonObject meta, StillTable stills, PakReader pak) {
        this.directory = directory;
        this.meta = meta;
        this.stills = stills;
        this.pak = pak;
    }

    /** Opens {@code directory}, or throws saying why it is not a readable format 2 artifact. */
    static Artifact open(Path directory) throws IOException {
        Path metaFile = directory.resolve("meta.json");
        if (!Files.isRegularFile(metaFile)) throw new IOException(directory + " has no meta.json");
        JsonObject meta;
        try {
            meta = JsonParser.parseString(Files.readString(metaFile, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("meta.json does not parse: " + e.getMessage(), e);
        }
        JsonElement format = meta.get("format");
        if (format == null || format.isJsonNull() || Files.exists(directory.resolve("images.pak"))) {
            throw new IOException(directory + " is a format 1 artifact (" + (format == null || format.isJsonNull()
                    ? "meta.json has no format" : "it has an images.pak") + "); these tools read format " + FORMAT
                    + " only, so rebuild it with the current renderer");
        }
        if (!format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber() || format.getAsInt() != FORMAT) {
            throw new IOException(directory + " is format " + format + "; these tools read format " + FORMAT + " only");
        }
        JsonElement images = meta.get("images");
        if (images == null || !images.isJsonPrimitive() || !images.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("meta.json says neither \"images\": true nor false");
        }
        if (!images.getAsBoolean()) return new Artifact(directory, meta, null, null);

        Path table = directory.resolve(StillTable.JSON), pak = directory.resolve(StillTable.PAK);
        if (!Files.isRegularFile(table) || !Files.isRegularFile(pak)) {
            throw new IOException("meta.json says the artifact has images, but " + (Files.isRegularFile(table)
                    ? StillTable.PAK : StillTable.JSON) + " is missing");
        }
        return new Artifact(directory, meta, readTable(table), PakReader.open(pak));
    }

    private static StillTable readTable(Path file) throws IOException {
        JsonArray rows;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            rows = JsonParser.parseReader(reader).getAsJsonArray();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException(StillTable.JSON + " does not parse: " + e.getMessage(), e);
        }
        List<StillEntry> entries = new ArrayList<>(rows.size());
        for (int id = 0; id < rows.size(); id++) {
            try {
                JsonArray row = rows.get(id).getAsJsonArray();
                if (row.size() != 4) throw new IllegalArgumentException(row.size() + " numbers, not 4");
                entries.add(new StillEntry(number(row.get(0), "offset").longValueExact(), integer(row.get(1), "length"),
                        integer(row.get(2), "width"), integer(row.get(3), "height")));
            } catch (IllegalArgumentException | IllegalStateException | ArithmeticException e) {
                throw new IOException(StillTable.JSON + " row " + id + ": " + e.getMessage(), e);
            }
        }
        try {
            return new StillTable(entries);
        } catch (IllegalArgumentException e) {
            throw new IOException(StillTable.JSON + ": " + e.getMessage(), e);
        }
    }

    /** Whether the artifact has pictures, or is data only. */
    boolean images() {
        return stills != null;
    }

    /** A number of {@code meta.json}, or null where it is null or missing. */
    Long metaNumber(String key) {
        JsonElement value = meta.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsLong();
    }

    /** Walks {@code recipes.json}: a JSON array with one record per line. */
    void records(RecordVisitor visitor) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(directory.resolve("recipes.json"), StandardCharsets.UTF_8)) {
            int index = 0, lineNumber = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                lineNumber++;
                line = line.strip();
                if (line.equals("[") || line.equals("]") || line.isEmpty()) continue;
                if (line.endsWith(",")) line = line.substring(0, line.length() - 1);
                JsonObject record;
                try {
                    record = JsonParser.parseString(line).getAsJsonObject();
                } catch (JsonParseException | IllegalStateException e) {
                    throw new IOException("recipes.json line " + lineNumber + " does not parse: " + e.getMessage(), e);
                }
                visitor.visit(index++, record);
            }
        }
    }

    /** How failure messages name a record: its index, its id where it has one, and its category. */
    static String name(int index, JsonObject record) {
        String id = string(record, "emiRecipeId");
        if (id == null) id = string(record, "underlyingRecipeId");
        return "#" + index + " " + (id == null ? "" : id + " ") + "(" + string(record, "cat") + ")";
    }

    /**
     * The record's picture, or null for a record without one. Throws {@link IllegalArgumentException} saying what is
     * wrong if the {@code "image"} field is missing, malformed, or breaks a rule of DESIGN 2.1, such as a still id
     * the table does not have or two stills of different sizes in one layer.
     */
    LayeredImage image(JsonObject record) {
        JsonElement image = record.get("image");
        if (image == null) {
            throw new IllegalArgumentException("no \"image\" field" + (record.has("frames")
                    ? ", but format 1's \"frames\": a format 1 record" : ""));
        }
        if (image.isJsonNull()) return null;
        if (stills == null) throw new IllegalArgumentException("an image in an artifact without images");
        if (!image.isJsonObject()) throw new IllegalArgumentException("\"image\" is " + image + ", not an object");
        try {
            JsonObject json = image.getAsJsonObject();
            JsonArray layersJson = field(json, "layers").getAsJsonArray();
            List<Layer> layers = new ArrayList<>(layersJson.size());
            for (int i = 0; i < layersJson.size(); i++) {
                JsonObject layer = layersJson.get(i).getAsJsonObject();
                try {
                    Timeline loop = new Timeline(integers(field(layer, "f"), "f"), integers(field(layer, "d"), "d"));
                    layers.add(Layer.of(integer(field(layer, "x"), "x"), integer(field(layer, "y"), "y"), loop,
                            stills));
                } catch (IllegalArgumentException | IllegalStateException | ArithmeticException e) {
                    throw new IllegalArgumentException("layer " + i + ": " + e.getMessage(), e);
                }
            }
            return new LayeredImage(integer(field(json, "w"), "w"), integer(field(json, "h"), "h"), layers);
        } catch (IllegalStateException | ArithmeticException e) {
            // Gson's getAs* on the wrong kind of element, and a number that is not an int.
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** The WebP of still {@code id}, as {@code stills.pak} holds it. */
    byte[] stillBytes(int id) throws IOException {
        return pak.read(stills.get(id).payload());
    }

    /** Still {@code id}, decoded. Throws if it does not decode as a single still of its table size. */
    Frame still(int id) throws IOException {
        Frame still = WebpFile.decode(stillBytes(id));
        StillEntry entry = stills.get(id);
        if (still.width() != entry.width() || still.height() != entry.height()) {
            throw new IOException("decodes to " + still.width() + "x" + still.height() + ", " + StillTable.JSON
                    + " says " + entry.width() + "x" + entry.height());
        }
        return still;
    }

    @Override
    public void close() throws IOException {
        if (pak != null) pak.close();
    }

    static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static JsonElement field(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("no \"" + key + "\"");
        return value;
    }

    private static BigDecimal number(JsonElement value, String what) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("\"" + what + "\" is " + value + ", not a number");
        }
        return value.getAsBigDecimal();
    }

    private static int integer(JsonElement value, String what) {
        try {
            return number(value, what).intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("\"" + what + "\" is " + value + ", not an int", e);
        }
    }

    private static int[] integers(JsonElement value, String what) {
        if (!value.isJsonArray()) throw new IllegalArgumentException("\"" + what + "\" is " + value + ", not an array");
        JsonArray array = value.getAsJsonArray();
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = integer(array.get(i), what + "[" + i + "]");
        return out;
    }
}
