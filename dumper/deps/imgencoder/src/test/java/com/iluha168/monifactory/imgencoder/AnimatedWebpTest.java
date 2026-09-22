package com.iluha168.monifactory.imgencoder;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The muxer's contract, which is {@code ignored/enc/verify.py}'s: every source frame decodes back pixel-exact at the
 * time it was shown, the durations add up to frames times the step, and the animation loops forever.
 */
class AnimatedWebpTest {
    private final WebpEncoder encoder = new WebpEncoder();

    /** A GT-style card: a progress arrow filling over 40 frames in steps, so it plateaus, plus a slower blinker. */
    static List<Frame> arrow(int width, int height, int frames, int blinkPeriod) {
        return arrow(width, height, frames, blinkPeriod, false);
    }

    /** {@link #arrow}, optionally with the corners cut transparent the way the renderer's cards come out. */
    static List<Frame> arrow(int width, int height, int frames, int blinkPeriod, boolean corners) {
        Frame base = Pictures.card(width, height, 42);
        if (corners)
            for (int y = 0; y < 3; y++)
                for (int x = 0; x < 3; x++) {
                    base.argb()[y * width + x] = 0;
                    base.argb()[(height - 1 - y) * width + width - 1 - x] = 0;
                }
        List<Frame> out = new ArrayList<>();
        for (int f = 0; f < frames; f++) {
            int[] argb = base.argb().clone();
            int fill = (f % 40) / 4 * 4; // holds each step for 4 frames
            for (int y = height / 2 - 4; y < height / 2 + 4; y++)
                for (int x = 20; x < 20 + fill; x++)
                    argb[y * width + x] = 0xFFFFFFFF;
            if (blinkPeriod > 0 && f % blinkPeriod < blinkPeriod / 2)
                for (int y = 6; y < 12; y++)
                    for (int x = width - 13; x < width - 7; x++)
                        argb[y * width + x] = 0xFFFF2020;
            out.add(new Frame(width, height, argb));
        }
        return out;
    }

    @Test
    void animationDecodesBackExactly() throws IOException {
        for (List<Frame> frames : List.of(
                arrow(168, 52, 40, 0),
                arrow(256, 124, 40, 14),
                arrow(251, 97, 200, 0),
                arrow(64, 64, 2, 0),
                arrow(168, 52, 40, 14, true))) {
            for (AnimatedWebp.Effort effort : AnimatedWebp.Effort.values()) {
                byte[] webp = encoder.encode(frames, 50, effort);
                Decoded decoded = Decoded.of(webp);
                decoded.assertTimeline(frames, 50);
            }
        }
    }

    @Test
    void stillForAnUnchangingSequence() {
        Frame picture = Pictures.card(168, 52, 10);
        byte[] still = encoder.encode(picture);
        assertArrayEquals(still, encoder.encode(List.of(picture, picture, picture), 50));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(List.of(), 50));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(List.of(picture), 0));
    }

    @Test
    void translucentChangesAreNotBlended() throws IOException {
        Frame a = Pictures.translucent(123, 45, 7);
        int[] changed = a.argb().clone();
        for (int i = 300; i < 900; i++) changed[i] = (i & 1) == 0 ? 0x00123456 : 0x80FFEEDD;
        List<Frame> frames = List.of(a, new Frame(123, 45, changed), a);
        Decoded.of(encoder.encode(frames, 50, AnimatedWebp.Effort.MASKED)).assertTimeline(frames, 50);
    }

    @Test
    void effortGate() {
        assertEquals(AnimatedWebp.Effort.SEARCH, AnimatedWebp.effortFor(arrow(368, 676, 32, 0)));
        assertEquals(AnimatedWebp.Effort.MASKED, AnimatedWebp.effortFor(arrow(368, 676, 33, 0)));
    }

    /** libwebp's own animation decoder, where it is installed, composites every frame to the same pixels. */
    @Test
    void animDumpAgrees() throws Exception {
        assumeTrue(onPath("anim_dump"), "anim_dump not installed");
        for (boolean corners : new boolean[]{false, true})
            animDumpAgrees(arrow(256, 124, 80, 14, corners));
    }

    private void animDumpAgrees(List<Frame> frames) throws Exception {
        byte[] webp = encoder.encode(frames, 50);
        Decoded mine = Decoded.of(webp);
        Path dir = Files.createTempDirectory("animdump");
        try {
            Path in = dir.resolve("in.webp");
            Files.write(in, webp);
            var process = new ProcessBuilder("anim_dump", "-folder", dir.toString(), "-prefix", "f_", "-pam",
                    in.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
            for (int i = 0; i < mine.canvases.size(); i++) {
                Frame dumped = WebpEncoderTest.readPam(Files.readAllBytes(dir.resolve(String.format("f_%04d.pam", i))));
                WebpEncoderTest.assertPixels(mine.canvases.get(i), dumped);
            }
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * The prototype's 41 test sets ({@code ignored/enc/work*}), opt in with {@code -Pmonifactory.enc.corpus=<dir>}.
     * Each set is encoded the way the prototype did it (both candidates on every frame) and must match its 0.11.0
     * output byte for byte. The port's files go to {@code -Pmonifactory.enc.out=<dir>} for {@code verify.py}, once as
     * the prototype encoded and once through the public entry point, effort gate included.
     */
    @Test
    void matchesThePrototype() throws IOException {
        String corpus = System.getProperty("monifactory.enc.corpus");
        assumeTrue(corpus != null && !corpus.isBlank(), "-Pmonifactory.enc.corpus not set");
        Path out = Path.of(System.getProperty("monifactory.enc.out", "build/enc-corpus"));
        Files.createDirectories(out);
        List<String> mismatches = new ArrayList<>();
        int sets = 0;
        StringBuilder index = new StringBuilder("label\tsrc\tstep\tframes\tbytes\tgated_bytes\tprototype_bytes\n");
        for (String work : List.of("work", "work2")) {
            Path root = Path.of(corpus, work);
            if (!Files.isDirectory(root)) continue;
            try (var labels = Files.list(root)) {
                for (Path set : labels.sorted().toList()) {
                    Path src = set.resolve("src"), golden = set.resolve("javamux_uf011.webp");
                    if (!Files.isDirectory(src) || !Files.isRegularFile(golden)) continue;
                    String label = set.getFileName().toString();
                    // corpus.py's steps: anim_item was captured at 31 ms and item_stack_probe at 100 ms.
                    int step = label.equals("anim_item") ? 31 : label.equals("item_stack_probe") ? 100 : 50;
                    List<Frame> frames = readPngs(src);
                    byte[] mine = encoder.encode(frames, step, AnimatedWebp.Effort.SEARCH);
                    byte[] gated = encoder.encode(frames, step);
                    Files.write(out.resolve(label + ".webp"), mine);
                    Files.write(out.resolve(label + ".gated.webp"), gated);
                    byte[] theirs = Files.readAllBytes(golden);
                    index.append(String.join("\t", label, src.toString(), Integer.toString(step),
                            Integer.toString(frames.size()), Integer.toString(mine.length),
                            Integer.toString(gated.length), Integer.toString(theirs.length))).append('\n');
                    if (!Arrays.equals(mine, theirs))
                        mismatches.add(label + ": " + mine.length + " B, prototype " + theirs.length + " B");
                    Decoded.of(mine).assertTimeline(frames, step);
                    Decoded.of(gated).assertTimeline(frames, step);
                    sets++;
                }
            }
        }
        Files.writeString(out.resolve("index.tsv"), index);
        assertTrue(sets > 0, "no sets under " + corpus);
        assertEquals(List.of(), mismatches, "port differs from the prototype on " + mismatches.size() + " of " + sets);
    }

    private static List<Frame> readPngs(Path dir) throws IOException {
        List<Frame> frames = new ArrayList<>();
        try (var pngs = Files.list(dir)) {
            for (Path png : pngs.filter(p -> p.toString().endsWith(".png")).sorted().toList()) {
                BufferedImage image = ImageIO.read(png.toFile());
                int w = image.getWidth(), h = image.getHeight();
                BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                argb.getGraphics().drawImage(image, 0, 0, null);
                frames.add(new Frame(w, h, argb.getRGB(0, 0, w, h, null, 0, w)));
            }
        }
        return frames;
    }

    /**
     * An animated WebP decoded by hand: the container parsed here, each ANMF's still decoded by the fork's reader and
     * composited onto the canvas the way the WebP container spec says. Independent of the muxer's code, not of libwebp.
     */
    record Decoded(int width, int height, int loops, List<Frame> canvases, List<Integer> durations) {
        static Decoded of(byte[] webp) throws IOException {
            assertEquals("RIFF", ascii(webp, 0));
            assertEquals(webp.length - 8, u32(webp, 4), "RIFF size");
            assertEquals("WEBP", ascii(webp, 8));
            if (ascii(webp, 12).equals("VP8L")) {
                Frame still = Pictures.decode(webp);
                return new Decoded(still.width(), still.height(), 0, List.of(still), List.of(0));
            }
            assertEquals("VP8X", ascii(webp, 12));
            int flags = webp[20] & 0xFF;
            assertTrue((flags & 0x02) != 0, "VP8X animation flag");
            boolean alphaFlag = (flags & 0x10) != 0;
            int width = u24(webp, 24) + 1, height = u24(webp, 27) + 1;
            int[] canvas = null;
            int loops = -1;
            List<Frame> canvases = new ArrayList<>();
            List<Integer> durations = new ArrayList<>();
            int p = 30;
            while (p + 8 <= webp.length) {
                String id = ascii(webp, p);
                int len = u32(webp, p + 4);
                int body = p + 8;
                switch (id) {
                    case "ANIM" -> {
                        loops = (webp[body + 4] & 0xFF) | (webp[body + 5] & 0xFF) << 8;
                        canvas = new int[width * height];
                        Arrays.fill(canvas, u32(webp, body)); // background colour; the first frame covers it
                    }
                    case "ANMF" -> {
                        assertNotNull(canvas, "ANMF before ANIM");
                        int x = u24(webp, body) * 2, y = u24(webp, body + 3) * 2;
                        int w = u24(webp, body + 6) + 1, h = u24(webp, body + 9) + 1;
                        int duration = u24(webp, body + 12);
                        int frameFlags = webp[body + 15] & 0xFF;
                        assertEquals(0, frameFlags & 0x01, "no frame disposes");
                        byte[] payload = Arrays.copyOfRange(webp, body + 16, body + len);
                        byte[] still = new byte[12 + payload.length];
                        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, still, 0, 4);
                        int size = 4 + payload.length;
                        still[4] = (byte) size;
                        still[5] = (byte) (size >>> 8);
                        still[6] = (byte) (size >>> 16);
                        still[7] = (byte) (size >>> 24);
                        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, still, 8, 4);
                        System.arraycopy(payload, 0, still, 12, payload.length);
                        Frame sub = Pictures.decode(still);
                        assertEquals(w, sub.width());
                        assertEquals(h, sub.height());
                        // Without the VP8X ALPHA flag a decoder shows every pixel opaque, whatever the frame holds.
                        if (!alphaFlag) assertTrue(sub.opaque(), "a frame with alpha in a file not flagged ALPHA");
                        boolean blend = (frameFlags & 0x02) == 0;
                        for (int j = 0; j < h; j++)
                            for (int i = 0; i < w; i++) {
                                int src = sub.argb()[j * w + i];
                                int at = (y + j) * width + x + i;
                                if (!blend || src >>> 24 == 0xFF) canvas[at] = src;
                                else if (src >>> 24 != 0) fail("blended a translucent pixel, which no decoder does exactly");
                            }
                        canvases.add(new Frame(width, height, canvas.clone()));
                        durations.add(duration);
                    }
                    default -> {
                    }
                }
                p = body + len + (len & 1);
            }
            assertEquals(webp.length, p, "trailing bytes");
            return new Decoded(width, height, loops, canvases, durations);
        }

        /** verify.py's check: each source frame is what shows at the middle of its slot. */
        void assertTimeline(List<Frame> source, int step) {
            if (canvases.size() == 1 && durations.get(0) == 0) {
                for (Frame frame : source) WebpEncoderTest.assertPixels(frame, canvases.get(0));
                return;
            }
            assertEquals(0, loops, "loop count");
            assertEquals(source.size() * step, durations.stream().mapToInt(Integer::intValue).sum(), "total duration");
            int shown = 0, until = durations.get(0);
            for (int i = 0; i < source.size(); i++) {
                int middle = i * step + step / 2;
                while (middle >= until) until += durations.get(++shown);
                WebpEncoderTest.assertPixels(source.get(i), canvases.get(shown));
            }
        }
    }

    private static boolean onPath(String tool) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (Files.isExecutable(Path.of(dir, tool))) return true;
        return false;
    }

    private static String ascii(byte[] b, int at) {
        return new String(b, at, 4, StandardCharsets.US_ASCII);
    }

    private static int u24(byte[] b, int at) {
        return b[at] & 0xFF | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16;
    }

    private static int u32(byte[] b, int at) {
        return u24(b, at) | (b[at + 3] & 0xFF) << 24;
    }
}
