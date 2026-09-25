package com.iluha168.monifactory.imgencoder.layered;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * {@code stills.json}: a JSON array whose element {@code id} is {@code [offset, length, width, height]} for still
 * {@code id}. One row per line, like {@code recipes.json}.
 * <p>
 * The stills tile {@code stills.pak} in id order, each starting where the one before ended and the first at 0. A table
 * that does not is refused here, so whoever holds one also knows that {@link #bytes()} is the size its pak must have.
 */
public record StillTable(List<StillEntry> stills) {
    /** The table's file name in an artifact. */
    public static final String JSON = "stills.json";
    /** The pack's file name in an artifact. */
    public static final String PAK = "stills.pak";
    /** What drew each still, a JSON object per still in id order, one row per line like {@link #JSON}. */
    public static final String USES = "still_uses.json";

    public StillTable {
        stills = List.copyOf(stills);
        long next = 0;
        for (int id = 0; id < stills.size(); id++) {
            StillEntry still = stills.get(id);
            if (still.payload().offset() != next)
                throw new IllegalArgumentException("still " + id + " starts at " + still.payload().offset()
                        + ", the one before it ends at " + next);
            next = still.payload().end();
        }
    }

    public int size() {
        return stills.size();
    }

    public StillEntry get(int id) {
        if (id < 0 || id >= stills.size())
            throw new IllegalArgumentException("no still " + id + " in a table of " + stills.size());
        return stills.get(id);
    }

    /** The end of the last still: the size of {@code stills.pak}. */
    public long bytes() {
        return stills.isEmpty() ? 0 : stills.get(stills.size() - 1).payload().end();
    }

    /** Writes the table to {@code file}, whole or not at all. */
    public void write(Path file) throws IOException {
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        try (BufferedWriter out = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
            out.write("[\n");
            for (int id = 0; id < stills.size(); id++) {
                StillEntry still = stills.get(id);
                out.write("[" + still.payload().offset() + "," + still.payload().length() + "," + still.width() + ","
                        + still.height() + "]");
                out.write(id + 1 < stills.size() ? ",\n" : "\n");
            }
            out.write("]\n");
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Writes {@link #USES} to {@code file}, whole or not at all: {@code rows}, each already a JSON object, as the
     * elements of one array. Row {@code id} is still {@code id}'s.
     */
    public static void writeUses(Path file, List<String> rows) throws IOException {
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        try (BufferedWriter out = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
            out.write("[\n");
            for (int id = 0; id < rows.size(); id++) {
                out.write(rows.get(id));
                out.write(id + 1 < rows.size() ? ",\n" : "\n");
            }
            out.write("]\n");
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
