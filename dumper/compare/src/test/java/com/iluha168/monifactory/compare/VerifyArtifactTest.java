package com.iluha168.monifactory.compare;

import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.WebpEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.iluha168.monifactory.compare.TestArtifact.canvas;
import static com.iluha168.monifactory.compare.TestArtifact.card;
import static com.iluha168.monifactory.compare.TestArtifact.image;
import static com.iluha168.monifactory.compare.TestArtifact.layer;
import static org.junit.jupiter.api.Assertions.*;

class VerifyArtifactTest {
    /** Display size of the test recipes: a 40x24 canvas at scale 2. */
    private static final int W = 12, H = 4;

    @TempDir
    Path dir;

    /** A layered recipe with an animated widget, a fallback recipe sharing its card, and a failed one. */
    private static TestArtifact valid() {
        TestArtifact artifact = TestArtifact.withImages();
        int card = artifact.still(card(canvas(W), canvas(H), 1));
        int widgetA = artifact.still(card(8, 6, 2)), widgetB = artifact.still(card(8, 6, 3));
        artifact.recipe("layered", W, H, image(canvas(W), canvas(H), layer(0, 0, card),
                layer(10, 4, new int[]{widgetA, widgetB, widgetA}, new int[]{3, 2, 1})));
        artifact.recipe("fallback", W, H, image(canvas(W), canvas(H), layer(0, 0, card)));
        artifact.recipe("failed", W, H, null);
        return artifact;
    }

    private List<String> verify(Path artifact) throws Exception {
        return VerifyArtifact.verify(artifact, 2);
    }

    private static void assertFailure(List<String> failures, String... parts) {
        for (String failure : failures) {
            boolean all = true;
            for (String part : parts) all &= failure.contains(part);
            if (all) return;
        }
        fail("no failure mentions " + List.of(parts) + " in " + failures);
    }

    @Test
    void wholeArtifactPasses() throws Exception {
        assertEquals(List.of(), verify(valid().write(dir)));
    }

    @Test
    void dataOnlyPasses() throws Exception {
        TestArtifact artifact = TestArtifact.dataOnly();
        artifact.recipe("one", W, H, null).recipe("two", W, H, null);
        assertEquals(List.of(), verify(artifact.write(dir)));
    }

    @Test
    void dataOnlyWithStillsFails() throws Exception {
        TestArtifact artifact = TestArtifact.dataOnly();
        artifact.recipe("one", W, H, null);
        Path written = artifact.write(dir);
        Files.write(written.resolve("stills.pak"), new byte[0]);
        assertFailure(verify(written), "no images", "stills.pak");
    }

    @Test
    void missingLangFails() throws Exception {
        Path written = valid().write(dir);
        Files.delete(written.resolve(VerifyArtifact.LANG));
        assertFailure(verify(written), "no " + VerifyArtifact.LANG);
    }

    @Test
    void langValueThatIsNotAStringFails() throws Exception {
        Path written = valid().write(dir);
        Files.writeString(written.resolve(VerifyArtifact.LANG), "{\"a\":\"A\",\"b\":{\"c\":\"C\"}}\n",
                StandardCharsets.UTF_8);
        assertFailure(verify(written), VerifyArtifact.LANG, "b is", "not a string");
    }

    @Test
    void missingMatterNamesFails() throws Exception {
        Path written = valid().write(dir);
        Files.delete(written.resolve(VerifyArtifact.MATTER_NAMES));
        assertFailure(verify(written), "no " + VerifyArtifact.MATTER_NAMES);
    }

    @Test
    void matterNamesWithoutFluidsFail() throws Exception {
        Path written = valid().write(dir);
        Files.writeString(written.resolve(VerifyArtifact.MATTER_NAMES), "{\"item\":{\"minecraft:stone\":\"Stone\"},"
                + "\"fluid\":{},\"gas\":{}}\n", StandardCharsets.UTF_8);
        List<String> failures = verify(written);
        assertFailure(failures, VerifyArtifact.MATTER_NAMES, "fluid is empty");
        assertFailure(failures, VerifyArtifact.MATTER_NAMES, "\"gas\"");
    }

    @Test
    void missingTagsFail() throws Exception {
        Path written = valid().write(dir);
        Files.delete(written.resolve(VerifyArtifact.TAGS));
        assertFailure(verify(written), "no " + VerifyArtifact.TAGS);
    }

    @Test
    void malformedTagsFail() throws Exception {
        Path written = valid().write(dir);
        Files.writeString(written.resolve(VerifyArtifact.TAGS), "{\"minecraft:block\":{\"test:a\":[1],\"test:b\":\"x\"},"
                + "\"minecraft:fluid\":[]}\n", StandardCharsets.UTF_8);
        List<String> failures = verify(written);
        assertFailure(failures, VerifyArtifact.TAGS, "test:a holds 1");
        assertFailure(failures, VerifyArtifact.TAGS, "test:b is not an array");
        assertFailure(failures, VerifyArtifact.TAGS, "minecraft:fluid is not an object");
        assertFailure(failures, VerifyArtifact.TAGS, "no item tags");
    }

    @Test
    void unknownStillIdFails() throws Exception {
        TestArtifact artifact = valid();
        artifact.recipe("stray", W, H, image(canvas(W), canvas(H), layer(0, 0, 0), layer(2, 2, 99)));
        assertFailure(verify(artifact.write(dir)), "#3 test:stray", "layer 1", "no still 99");
    }

    @Test
    void stillsOfDifferentSizesInOneLayerFail() throws Exception {
        TestArtifact artifact = valid();
        int small = artifact.still(card(4, 4, 7));
        artifact.recipe("mixed", W, H, image(canvas(W), canvas(H), layer(0, 0, 0),
                layer(2, 2, new int[]{1, small}, new int[]{1, 1})));
        assertFailure(verify(artifact.write(dir)), "#3 test:mixed", "still " + small + " is 4x4",
                "same layer is 8x6");
    }

    @Test
    void boxOutsideTheCanvasFails() throws Exception {
        TestArtifact artifact = valid();
        artifact.recipe("outside", W, H, image(canvas(W), canvas(H), layer(0, 0, 0), layer(canvas(W) - 4, 0, 1)));
        assertFailure(verify(artifact.write(dir)), "#3 test:outside", "layer 1", "leaves the 40x24 canvas");
    }

    @Test
    void canvasOfTheWrongSizeFails() throws Exception {
        TestArtifact artifact = valid();
        artifact.recipe("small", W - 2, H, image(canvas(W), canvas(H), layer(0, 0, 0)));
        assertFailure(verify(artifact.write(dir)), "#3 test:small", "40x24 canvas, expected 36x24");
    }

    @Test
    void zeroDurationFails() throws Exception {
        TestArtifact artifact = valid();
        artifact.recipe("frozen", W, H, image(canvas(W), canvas(H), layer(0, 0, 0),
                layer(10, 4, new int[]{1, 2}, new int[]{1, 0})));
        assertFailure(verify(artifact.write(dir)), "#3 test:frozen", "entry 1 lasts 0 ticks");
    }

    @Test
    void imageWithNoLayersFails() throws Exception {
        TestArtifact artifact = valid();
        artifact.recipe("empty", W, H, image(canvas(W), canvas(H)));
        assertFailure(verify(artifact.write(dir)), "#3 test:empty", "layer 0");
    }

    @Test
    void truncatedPakFails() throws Exception {
        Path written = valid().write(dir);
        Path pak = written.resolve("stills.pak");
        long size = Files.size(pak);
        try (RandomAccessFile file = new RandomAccessFile(pak.toFile(), "rw")) {
            file.setLength(size - 10);
        }
        List<String> failures = verify(written);
        assertFailure(failures, "stills.pak is " + (size - 10) + " B", "covers " + size + " B");
        assertFailure(failures, "still 2", "runs past the end");
        assertFailure(failures, "stillsBytes");
    }

    @Test
    void stillOfTheWrongSizeFails() throws Exception {
        TestArtifact artifact = valid();
        int liar = artifact.still(new WebpEncoder().encode(card(8, 8, 5)), 6, 6);
        artifact.recipe("liar", W, H, image(canvas(W), canvas(H), layer(0, 0, 0), layer(2, 2, liar)));
        assertFailure(verify(artifact.write(dir)), "still " + liar, "decodes to 8x8, stills.json says 6x6");
    }

    @Test
    void animatedStillFails() throws Exception {
        TestArtifact artifact = valid();
        byte[] animation = new WebpEncoder().encode(List.of(card(8, 6, 1), card(8, 6, 2)), 50);
        int moving = artifact.still(animation, 8, 6);
        artifact.recipe("moving", W, H, image(canvas(W), canvas(H), layer(0, 0, 0), layer(2, 2, moving)));
        assertFailure(verify(artifact.write(dir)), "still " + moving, "an animation");
    }

    @Test
    void undecodableStillFails() throws Exception {
        TestArtifact artifact = valid();
        byte[] junk = "RIFF\u0010\0\0\0WEBPVP8L\u0004\0\0\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
        int bad = artifact.still(junk, 8, 6);
        assertFailure(verify(artifact.write(dir)), "still " + bad, "libwebp");
    }

    @Test
    void stillTableWithAGapFails() throws Exception {
        Path written = valid().write(dir);
        Path table = written.resolve("stills.json");
        String rows = Files.readString(table);
        // Still 1 moved one byte on: a gap before it, and an overlap after.
        String second = rows.split("\n")[2];
        String[] numbers = second.replaceAll("[\\[\\],]+", " ").trim().split(" ");
        long offset = Long.parseLong(numbers[0]);
        Files.writeString(table, rows.replace(second, second.replaceFirst("\\[" + offset + ",", "[" + (offset + 1)
                + ",")));
        assertFailure(verify(written), "stills.json", "still 1 starts at " + (offset + 1));
    }

    @Test
    void wrongMetaCountsFail() throws Exception {
        TestArtifact artifact = valid();
        artifact.meta.put("recipes", "4");
        artifact.meta.put("stills", "2");
        artifact.meta.put("layered", "2");
        artifact.meta.put("failed", "0");
        List<String> failures = verify(artifact.write(dir));
        assertFailure(failures, "says recipes 4", "holds 3 records");
        assertFailure(failures, "says stills 2", "lists 3 stills");
        assertFailure(failures, "2 layered and 1 fallback", "2 records have a picture");
        assertFailure(failures, "says failed 0", "1 records have no picture");
        assertEquals(4, failures.size(), failures.toString());
    }

    @Test
    void formatOneIsRefused() throws Exception {
        Files.writeString(dir.resolve("meta.json"), "{\"recipes\": 1, \"images\": true, \"imagesBytes\": 20}");
        Files.writeString(dir.resolve("recipes.json"), "[\n{\"w\":1,\"h\":1,\"frames\":1,\"bytes\":20,\"offset\":0}\n]\n");
        Files.write(dir.resolve("images.pak"), new byte[20]);
        List<String> failures = verify(dir);
        assertEquals(1, failures.size(), failures.toString());
        assertFailure(failures, "format 1", "no format");
    }

    @Test
    void formatOneRecordIsNamed() throws Exception {
        Path written = valid().write(dir);
        Path recipes = written.resolve("recipes.json");
        List<String> lines = Files.readAllLines(recipes);
        lines.set(1, lines.get(1).replaceFirst(",\"image\":.*}", ",\"frames\":1,\"bytes\":20,\"offset\":0}"));
        Files.write(recipes, lines);
        assertFailure(verify(written), "#0 test:layered", "a format 1 record");
    }

    @Test
    void decodedStillIsStraightArgbTopRowFirst() throws Exception {
        int[] argb = {0xFF102030, 0x80FF0000, 0x00000000, 0x4000FF00, 0xFFFFFFFF, 0x01020304};
        Frame still = new Frame(2, 3, argb);
        Frame decoded = WebpFile.decode(new WebpEncoder().encode(still));
        assertTrue(still.samePixels(decoded), () -> Arrays.toString(decoded.argb()));
    }
}
