package com.iluha168.monifactory.compare;

import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.layered.Compositor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.iluha168.monifactory.compare.TestArtifact.canvas;
import static com.iluha168.monifactory.compare.TestArtifact.card;
import static com.iluha168.monifactory.compare.TestArtifact.image;
import static com.iluha168.monifactory.compare.TestArtifact.layer;
import static com.iluha168.monifactory.compare.TestArtifact.touched;
import static org.junit.jupiter.api.Assertions.*;

class CompareDumpsTest {
    private static final int W = 40, H = 30;
    /** A tag input: the pack picks what it shows per boot. */
    private static final String TAG = "[{\"k\":\"t\",\"id\":\"forge:ingots/iron\",\"n\":1}]";

    private static final Frame CARD = card(canvas(W), canvas(H), 1);
    private static final Frame WIDGET = card(10, 8, 2);
    private static final Frame OTHER = card(10, 8, 3);

    @TempDir
    Path dir;

    private record Outcome(int unexplained, Map<String, Integer> counts) {
    }

    private Outcome compare(TestArtifact a, TestArtifact b) throws IOException {
        CompareDumps compare = new CompareDumps(a.write(dir.resolve("a")), b.write(dir.resolve("b")), null);
        return new Outcome(compare.run(), compare.counts());
    }

    @Test
    void sameStillsUnderOtherIdsAreIdentical() throws IOException {
        TestArtifact a = TestArtifact.withImages();
        int cardA = a.still(CARD), widgetA = a.still(WIDGET), otherA = a.still(OTHER);
        a.recipe("static", W, H, image(canvas(W), canvas(H), layer(0, 0, cardA), layer(20, 10, widgetA)));
        a.recipe("moving", W, H, image(canvas(W), canvas(H), layer(0, 0, cardA),
                layer(20, 10, new int[]{widgetA, otherA}, new int[]{4, 4})));

        // B drew the recipes in another order, so its ids differ, and it holds a still A never needed.
        TestArtifact b = TestArtifact.withImages();
        int otherB = b.still(OTHER);
        b.still(card(6, 6, 9));
        int widgetB = b.still(WIDGET), cardB = b.still(CARD);
        assertNotEquals(cardA, cardB);
        b.recipe("moving", W, H, image(canvas(W), canvas(H), layer(0, 0, cardB),
                layer(20, 10, new int[]{widgetB, otherB}, new int[]{4, 4})));
        b.recipe("static", W, H, image(canvas(W), canvas(H), layer(0, 0, cardB), layer(20, 10, widgetB)));

        assertEquals(new Outcome(0, Map.of("static, identical", 1, "both animated", 1)), compare(a, b));
    }

    @Test
    void samePictureFromOtherLayersIsExplained() throws IOException {
        TestArtifact a = TestArtifact.withImages();
        int card = a.still(CARD), widget = a.still(WIDGET);
        a.recipe("static", W, H, image(canvas(W), canvas(H), layer(0, 0, card), layer(20, 10, widget)));

        // B fell back and drew the recipe whole: one layer, the same pixels.
        TestArtifact b = TestArtifact.withImages();
        Frame whole = Compositor.composite(canvas(W), canvas(H),
                List.of(new Compositor.Placed(0, 0, CARD), new Compositor.Placed(20, 10, WIDGET)));
        b.recipe("static", W, H, image(canvas(W), canvas(H), layer(0, 0, b.still(whole))));

        assertEquals(new Outcome(0, Map.of("static, same picture from other layers", 1)), compare(a, b));
    }

    /** One recipe, its widget's still with one pixel changed in B. */
    private Outcome changedPixel(String in) throws IOException {
        TestArtifact a = TestArtifact.withImages();
        a.recipe("static", W, H, in, image(canvas(W), canvas(H), layer(0, 0, a.still(CARD)),
                layer(20, 10, a.still(WIDGET))));
        TestArtifact b = TestArtifact.withImages();
        b.recipe("static", W, H, in, image(canvas(W), canvas(H), layer(0, 0, b.still(CARD)),
                layer(20, 10, b.still(touched(WIDGET, 3, 4)))));
        return compare(a, b);
    }

    @Test
    void pixelDifferenceWithoutAVaryingSlotIsUnexplained() throws IOException {
        assertEquals(new Outcome(1, Map.of("static, pixels differ, UNEXPLAINED", 1)), changedPixel("[]"));
    }

    @Test
    void pixelDifferenceInAVaryingSlotIsExplained() throws IOException {
        assertEquals(new Outcome(0, Map.of("static, pixels differ in varying slots", 1)), changedPixel(TAG));
    }

    @Test
    void differenceLargerThanASlotIsUnexplainedEvenWithAVaryingSlot() throws IOException {
        TestArtifact a = TestArtifact.withImages();
        a.recipe("static", W, H, TAG, image(canvas(W), canvas(H), layer(0, 0, a.still(CARD))));
        TestArtifact b = TestArtifact.withImages();
        Frame another = card(canvas(W), canvas(H), 5);
        b.recipe("static", W, H, TAG, image(canvas(W), canvas(H), layer(0, 0, b.still(another))));
        assertEquals(new Outcome(1, Map.of("static, pixels differ past a slot, UNEXPLAINED", 1)), compare(a, b));
    }

    @Test
    void staticInOneBuildOnlyIsUnexplainedWithoutAVaryingSlot() throws IOException {
        TestArtifact a = TestArtifact.withImages();
        int card = a.still(CARD), widget = a.still(WIDGET), other = a.still(OTHER);
        a.recipe("r", W, H, image(canvas(W), canvas(H), layer(0, 0, card),
                layer(20, 10, new int[]{widget, other}, new int[]{1, 1})));
        TestArtifact b = TestArtifact.withImages();
        b.recipe("r", W, H, image(canvas(W), canvas(H), layer(0, 0, b.still(CARD)), layer(20, 10, b.still(WIDGET))));

        assertEquals(new Outcome(1, Map.of("static in one only, UNEXPLAINED", 1)), compare(a, b));
    }

    @Test
    void dataOnlyArtifactsCompare() throws IOException {
        TestArtifact a = TestArtifact.dataOnly().recipe("r", W, H, null);
        TestArtifact b = TestArtifact.dataOnly().recipe("r", W, H, null);
        assertEquals(new Outcome(0, Map.of("both not rendered", 1)), compare(a, b));
    }

    @Test
    void formatOneIsRefused() throws IOException {
        Path a = TestArtifact.withImages().recipe("r", W, H, null).write(dir.resolve("a"));
        Path b = dir.resolve("b");
        Files.createDirectories(b);
        Files.writeString(b.resolve("meta.json"), "{\"recipes\": 0, \"images\": true}");
        Files.writeString(b.resolve("recipes.json"), "[\n]\n");
        Files.write(b.resolve("images.pak"), new byte[0]);
        IOException e = assertThrows(IOException.class, () -> new CompareDumps(a, b, null).run());
        assertTrue(e.getMessage().contains("format 1"), e.getMessage());
    }
}
