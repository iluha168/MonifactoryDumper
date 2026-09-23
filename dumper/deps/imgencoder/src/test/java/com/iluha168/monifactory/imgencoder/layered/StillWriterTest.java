package com.iluha168.monifactory.imgencoder.layered;

import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.PakReader;
import com.iluha168.monifactory.imgencoder.WebpEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class StillWriterTest {
    @TempDir
    Path dir;

    /** An executor that runs nothing until told to, in whatever order the test picks. */
    private static final class Held implements java.util.concurrent.Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }
    }

    /** A stand-in encoder: the still's size and first pixel, then a length that differs from still to still. */
    private static byte[] fake(Frame still) {
        var buffer = ByteBuffer.allocate(12 + still.width());
        buffer.putInt(still.width()).putInt(still.height()).putInt(still.argb()[0]);
        return buffer.array();
    }

    private static Frame still(int width, int height, long seed) {
        var random = new SplittableRandom(seed);
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++)
            argb[i] = random.nextInt();
        return new Frame(width, height, argb);
    }

    @Test
    void idsByFirstSightAndThePakInIdOrder() throws Exception {
        Frame a = still(5, 4, 1), b = still(7, 2, 2), c = still(3, 3, 3);
        Frame aAgain = new Frame(5, 4, a.argb().clone());
        var held = new Held();
        AtomicInteger encodes = new AtomicInteger();
        Function<Frame, byte[]> encoder = frame -> {
            encodes.incrementAndGet();
            return fake(frame);
        };
        try (var writer = StillWriter.create(dir, held, encoder)) {
            assertEquals(0, writer.add(a));
            assertEquals(1, writer.add(b));
            assertEquals(0, writer.add(aAgain), "same content, another array");
            assertEquals(2, writer.add(c));
            assertEquals(1, writer.add(b));
            assertEquals(3, writer.count());
            assertEquals(3, held.tasks.size(), "each still goes to an encoder once");
            assertEquals(4L * (20 + 14 + 9), writer.waitingBytes());
            assertFalse(writer.settled());

            Path pak = dir.resolve(StillTable.PAK);
            // Finish them backwards: nothing can go into the pak until still 0 is done, then all of it does.
            held.tasks.get(2).run();
            held.tasks.get(1).run();
            assertEquals(0, Files.size(pak));
            assertEquals(4L * 20, writer.waitingBytes());
            held.tasks.get(0).run();
            assertEquals(0, writer.waitingBytes());
            assertTrue(writer.settled());

            StillTable table = writer.finish();
            assertEquals(3, encodes.get());
            byte[] expected = concat(fake(a), fake(b), fake(c));
            assertArrayEquals(expected, Files.readAllBytes(pak));
            assertEquals(List.of(
                    new StillEntry(0, fake(a).length, 5, 4),
                    new StillEntry(fake(a).length, fake(b).length, 7, 2),
                    new StillEntry(fake(a).length + fake(b).length, fake(c).length, 3, 3)), table.stills());
            assertEquals(Files.size(pak), table.bytes());
            assertEquals("[\n[0,17,5,4],\n[17,19,7,2],\n[36,15,3,3]\n]\n",
                    Files.readString(dir.resolve(StillTable.JSON), StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class, () -> writer.add(still(1, 1, 9)), "finished");
        }
    }

    @Test
    void finishWaitsForTheEncoders() throws Exception {
        var held = new Held();
        var writer = StillWriter.create(dir, held, StillWriterTest::fake);
        writer.add(still(2, 2, 1));
        writer.add(still(2, 2, 2));
        Thread late = new Thread(() -> {
            for (int i = held.tasks.size() - 1; i >= 0; i--) held.tasks.get(i).run();
        });
        late.start();
        assertEquals(2, writer.finish().size());
        late.join();
    }

    @Test
    void aFailedEncodeFailsTheWriter() throws Exception {
        var held = new Held();
        var writer = StillWriter.create(dir, held, frame -> {
            if (frame.width() == 2) throw new IllegalStateException("boom");
            return fake(frame);
        });
        writer.add(still(1, 1, 1));
        writer.add(still(2, 1, 2));
        writer.add(still(3, 1, 3));
        held.tasks.forEach(Runnable::run);
        assertTrue(writer.settled());
        var e = assertThrows(IllegalStateException.class, () -> writer.add(still(4, 1, 4)));
        assertEquals("boom", e.getCause().getMessage());
        assertThrows(IOException.class, writer::finish);
        assertFalse(Files.exists(dir.resolve(StillTable.JSON)), "no table with a hole in it");
    }

    /** Real encodes on a real pool: every still comes back out of the pak by its id, pixel for pixel. */
    @Test
    void realEncodersOnAPool() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ThreadLocal<WebpEncoder> encoders = ThreadLocal.withInitial(WebpEncoder::new);
        List<Frame> distinct = new ArrayList<>();
        for (int i = 0; i < 40; i++)
            distinct.add(still(8 + i % 13, 5 + i % 7, i));
        var random = new SplittableRandom(99);
        List<Frame> added = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        StillTable table;
        try (var writer = StillWriter.create(dir, pool, frame -> encoders.get().encode(frame))) {
            for (int i = 0; i < 200; i++) {
                // Every still once in order first, then repeats, some as fresh copies of the same pixels.
                Frame still = i < distinct.size() ? distinct.get(i) : distinct.get(random.nextInt(distinct.size()));
                if (random.nextBoolean()) still = new Frame(still.width(), still.height(), still.argb().clone());
                added.add(still);
                ids.add(writer.add(still));
            }
            table = writer.finish();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(distinct.size(), table.size());
        for (int i = 0; i < distinct.size(); i++)
            assertEquals(i, ids.get(i), "ids in first-sight order");
        try (var pak = PakReader.open(dir.resolve(StillTable.PAK))) {
            assertEquals(pak.size(), table.bytes());
            for (int i = 0; i < added.size(); i++) {
                StillEntry entry = table.get(ids.get(i));
                Frame decoded = decode(pak.read(entry.payload()));
                assertEquals(entry.width(), decoded.width());
                assertEquals(entry.height(), decoded.height());
                assertTrue(added.get(i).samePixels(decoded), "still " + ids.get(i));
            }
        }
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] part : parts) n += part.length;
        var buffer = ByteBuffer.allocate(n);
        for (byte[] part : parts) buffer.put(part);
        return buffer.array();
    }

    private static Frame decode(byte[] webp) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(webp));
        if (image == null) throw new IOException("no ImageIO reader took the file");
        int w = image.getWidth(), h = image.getHeight();
        return new Frame(w, h, image.getRGB(0, 0, w, h, null, 0, w));
    }
}
