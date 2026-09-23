package com.iluha168.monifactory.imgencoder.layered;

import com.iluha168.monifactory.imgencoder.PakEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LayeredImageTest {
    @TempDir
    Path dir;

    @Test
    void writesTheRecordShape() {
        LayeredImage image = new LayeredImage(344, 230, List.of(
                new Layer(0, 0, 344, 230, Timeline.of(17)),
                new Layer(40, 36, 36, 36, Timeline.of(900, 900, 901, 902, 902, 902))));
        assertEquals("{\"w\":344,\"h\":230,\"layers\":["
                        + "{\"x\":0,\"y\":0,\"f\":[17],\"d\":[1]},"
                        + "{\"x\":40,\"y\":36,\"f\":[900,901,902],\"d\":[2,1,3]}]}",
                image.toJson());
        StringBuilder record = new StringBuilder("{\"image\":");
        image.appendJson(record);
        assertEquals("{\"image\":" + image.toJson(), record.toString());
    }

    @Test
    void refusesBrokenImages() {
        Timeline still = Timeline.of(0);
        assertThrows(IllegalArgumentException.class, () -> new LayeredImage(10, 10, List.of()), "no layer 0");
        assertThrows(IllegalArgumentException.class, () -> new LayeredImage(0, 10, List.of(new Layer(0, 0, 1, 1, still))));
        assertThrows(IllegalArgumentException.class,
                () -> new LayeredImage(10, 10, List.of(new Layer(0, 0, 10, 10, still), new Layer(5, 0, 6, 1, still))),
                "right edge off the canvas");
        assertThrows(IllegalArgumentException.class,
                () -> new LayeredImage(10, 10, List.of(new Layer(0, 9, 1, 2, still))), "bottom edge off the canvas");
        assertDoesNotThrow(() -> new LayeredImage(10, 10, List.of(new Layer(9, 9, 1, 1, still))));
        assertThrows(IllegalArgumentException.class, () -> new Layer(-1, 0, 1, 1, still));
        assertThrows(IllegalArgumentException.class, () -> new Layer(0, 0, 0, 1, still));
    }

    @Test
    void readerTakesLayerSizesFromTheTable() {
        StillTable table = new StillTable(List.of(
                new StillEntry(0, 10, 344, 230),
                new StillEntry(10, 5, 36, 36),
                new StillEntry(15, 7, 36, 36),
                new StillEntry(22, 3, 18, 18)));
        Layer layer = Layer.of(40, 36, Timeline.of(1, 2), table);
        assertEquals(new Layer(40, 36, 36, 36, Timeline.of(1, 2)), layer);
        var e = assertThrows(IllegalArgumentException.class, () -> Layer.of(0, 0, Timeline.of(1, 3), table));
        assertTrue(e.getMessage().contains("still 3 is 18x18"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Layer.of(0, 0, Timeline.of(4), table), "no such still");
    }

    @Test
    void tableTilesThePak() {
        assertThrows(IllegalArgumentException.class, () -> new StillTable(List.of(new StillEntry(1, 10, 1, 1))),
                "gap before the first still");
        assertThrows(IllegalArgumentException.class, () -> new StillTable(List.of(
                new StillEntry(0, 10, 1, 1), new StillEntry(9, 10, 1, 1))), "overlap");
        assertEquals(0, new StillTable(List.of()).bytes());
        assertEquals(new PakEntry(10, 4),
                new StillTable(List.of(new StillEntry(0, 10, 1, 1), new StillEntry(10, 4, 2, 3))).get(1).payload());
    }

    @Test
    void writesTheTableOneRowPerLine() throws IOException {
        Path file = dir.resolve(StillTable.JSON);
        new StillTable(List.of(new StillEntry(0, 10, 344, 230), new StillEntry(10, 4, 2, 3))).write(file);
        assertEquals("[\n[0,10,344,230],\n[10,4,2,3]\n]\n", Files.readString(file, StandardCharsets.UTF_8));
        new StillTable(List.of()).write(file);
        assertEquals("[\n]\n", Files.readString(file, StandardCharsets.UTF_8));
    }
}
