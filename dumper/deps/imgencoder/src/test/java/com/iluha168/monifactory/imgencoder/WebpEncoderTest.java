package com.iluha168.monifactory.imgencoder;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WebpEncoderTest {
    private final WebpEncoder encoder = new WebpEncoder();

    // The animation census's smallest, median and largest card, plus sizes that stress odd edges.
    private static final List<Frame> PICTURES = List.of(
            Pictures.card(168, 52, 1),
            Pictures.card(256, 124, 2),
            Pictures.card(368, 676, 3),
            Pictures.noise(251, 97, 4),
            Pictures.noise(1, 1, 5),
            Pictures.noise(1, 300, 6),
            Pictures.translucent(123, 45, 7),
            Pictures.translucent(2, 2, 8),
            new Frame(33, 17, new int[33 * 17]) // fully transparent black
    );

    @Test
    void decodesBackBitForBit() throws IOException {
        for (Frame picture : PICTURES) {
            Frame decoded = Pictures.decode(encoder.encode(picture));
            assertEquals(picture.width(), decoded.width());
            assertEquals(picture.height(), decoded.height());
            assertPixels(picture, decoded);
        }
    }

    @Test
    void writesABareLosslessStill() {
        for (Frame picture : PICTURES) {
            byte[] webp = encoder.encode(picture);
            assertEquals("RIFF", ascii(webp, 0));
            assertEquals(webp.length - 8, u32(webp, 4), "RIFF size");
            assertEquals("WEBP", ascii(webp, 8));
            // Simple format: the VP8L chunk straight after the header, no VP8X, so nothing reads it as animated.
            assertEquals("VP8L", ascii(webp, 12));
            assertEquals(webp.length - 20, u32(webp, 16) + (u32(webp, 16) & 1), "VP8L chunk size");

            assertEquals(0x2F, webp[20] & 0xFF, "VP8L signature");
            long bits = u32(webp, 21) & 0xFFFFFFFFL;
            assertEquals(picture.width(), (bits & 0x3FFF) + 1);
            assertEquals(picture.height(), (bits >>> 14 & 0x3FFF) + 1);
            // Opaque input must go down the 3-channel path, or every static card pays for an alpha plane.
            assertEquals(!picture.opaque(), (bits >>> 28 & 1) == 1, "alpha_is_used");
        }
    }

    @Test
    void isDeterministic() {
        Frame picture = Pictures.card(256, 124, 9);
        byte[] once = encoder.encode(picture);
        assertArrayEquals(once, encoder.encode(picture));
        assertArrayEquals(once, new WebpEncoder().encode(picture));
    }

    @Test
    void unchangingSequenceIsTheStill() {
        Frame picture = Pictures.card(168, 52, 10);
        Frame copy = new Frame(picture.width(), picture.height(), picture.argb().clone());
        byte[] still = encoder.encode(picture);
        assertArrayEquals(still, encoder.encode(List.of(picture), 50));
        assertArrayEquals(still, encoder.encode(List.of(picture, copy, picture), 50));
    }

    @Test
    void convertsNativeImageLayout() {
        Frame frame = Frame.fromAbgr(2, 1, new int[]{0x80332211, 0xFFCCBBAA});
        assertArrayEquals(new int[]{0x80112233, 0xFFAABBCC}, frame.argb());
    }

    @Test
    void rejectsWhatWebpCannotHold() {
        assertThrows(IllegalArgumentException.class, () -> new Frame(0, 1, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new Frame(Frame.MAX_SIDE + 1, 1, new int[Frame.MAX_SIDE + 1]));
        assertThrows(IllegalArgumentException.class, () -> new Frame(2, 2, new int[3]));
    }

    /**
     * A second opinion from a libwebp that is not the one we encode with. Skipped where dwebp is not installed.
     * dwebp -pam writes plain RGBA, so it also shows the colour under alpha 0 survived.
     */
    @Test
    void dwebpAgrees() throws Exception {
        assumeTrue(onPath("dwebp"), "dwebp not installed");
        Path dir = Files.createTempDirectory("imgencoder");
        try {
            for (Frame picture : PICTURES) {
                Path webp = dir.resolve("in.webp"), pam = dir.resolve("out.pam");
                Files.write(webp, encoder.encode(picture));
                var process = new ProcessBuilder("dwebp", "-quiet", "-pam", webp.toString(), "-o", pam.toString())
                        .redirectErrorStream(true).start();
                assertTrue(process.waitFor(60, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
                assertPixels(picture, readPam(Files.readAllBytes(pam)));
            }
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
            }
        }
    }

    static void assertPixels(Frame expected, Frame actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        int bad = Arrays.mismatch(expected.argb(), actual.argb());
        if (bad >= 0)
            fail(String.format("pixel (%d,%d) of %dx%d: expected %08x, got %08x", bad % expected.width(),
                    bad / expected.width(), expected.width(), expected.height(), expected.argb()[bad], actual.argb()[bad]));
    }

    static Frame readPam(byte[] pam) {
        String text = new String(pam, StandardCharsets.ISO_8859_1);
        int body = text.indexOf("ENDHDR\n") + "ENDHDR\n".length();
        int w = 0, h = 0;
        for (String line : text.substring(0, body).split("\n")) {
            if (line.startsWith("WIDTH ")) w = Integer.parseInt(line.substring(6).trim());
            if (line.startsWith("HEIGHT ")) h = Integer.parseInt(line.substring(7).trim());
        }
        assertTrue(text.substring(0, body).contains("DEPTH 4"), "dwebp -pam writes RGBA");
        int[] argb = new int[w * h];
        for (int i = 0, p = body; i < argb.length; i++, p += 4)
            argb[i] = (pam[p + 3] & 0xFF) << 24 | (pam[p] & 0xFF) << 16 | (pam[p + 1] & 0xFF) << 8 | pam[p + 2] & 0xFF;
        return new Frame(w, h, argb);
    }

    private static boolean onPath(String tool) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (Files.isExecutable(Path.of(dir, tool))) return true;
        return false;
    }

    private static String ascii(byte[] b, int at) {
        return new String(b, at, 4, StandardCharsets.US_ASCII);
    }

    private static int u32(byte[] b, int at) {
        return b[at] & 0xFF | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }
}
