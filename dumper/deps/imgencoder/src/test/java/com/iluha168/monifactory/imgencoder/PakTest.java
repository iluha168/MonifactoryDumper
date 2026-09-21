package com.iluha168.monifactory.imgencoder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class PakTest {
    @TempDir
    Path dir;

    @Test
    void payloadsSitBackToBack() throws IOException {
        Path pak = dir.resolve("images.pak");
        var random = new SplittableRandom(1);
        List<byte[]> payloads = new ArrayList<>();
        List<PakEntry> entries = new ArrayList<>();
        try (var writer = PakWriter.create(pak)) {
            for (int i = 0; i < 200; i++) {
                byte[] payload = new byte[1 + random.nextInt(5000)];
                random.nextBytes(payload);
                payloads.add(payload);
                entries.add(writer.append(payload));
            }
            assertEquals(entries.get(entries.size() - 1).end(), writer.size());
        }

        long expectedOffset = 0;
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(expectedOffset, entries.get(i).offset(), "no header, no padding");
            assertEquals(payloads.get(i).length, entries.get(i).length());
            expectedOffset = entries.get(i).end();
        }
        assertEquals(expectedOffset, Files.size(pak));

        try (var reader = PakReader.open(pak)) {
            for (int i = 0; i < entries.size(); i++)
                assertArrayEquals(payloads.get(i), reader.read(entries.get(i)));
            assertThrows(EOFException.class, () -> reader.read(new PakEntry(reader.size() - 1, 2)));
        }
    }

    @Test
    void createStartsOver() throws IOException {
        Path pak = dir.resolve("images.pak");
        try (var writer = PakWriter.create(pak)) {
            writer.append(new byte[1000]);
        }
        try (var writer = PakWriter.create(pak)) {
            assertEquals(new PakEntry(0, 3), writer.append(new byte[]{1, 2, 3}));
        }
        assertEquals(3, Files.size(pak));
    }

    @Test
    void refusesEmptyPayloads() throws IOException {
        try (var writer = PakWriter.create(dir.resolve("images.pak"))) {
            assertThrows(IllegalArgumentException.class, () -> writer.append(new byte[0]));
            assertEquals(0, writer.size());
        }
    }

    /** Workers encode and append at once; every image must still come back whole and decode to its own pixels. */
    @Test
    void encodersShareOneWriter() throws Exception {
        Path pak = dir.resolve("images.pak");
        int threads = 4, perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        record Written(Frame picture, PakEntry entry) {
        }
        List<Future<List<Written>>> futures = new ArrayList<>();
        try (var writer = PakWriter.create(pak)) {
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    var encoder = new WebpEncoder(); // one per thread, as documented
                    List<Written> written = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        Frame picture = i % 5 == 0
                                ? Pictures.translucent(40 + i, 30, thread * 1000L + i)
                                : Pictures.card(168 + 2 * i, 52 + 2 * thread, thread * 1000L + i);
                        written.add(new Written(picture, writer.append(encoder.encode(picture))));
                    }
                    return written;
                }));
            }
            pool.shutdown();

            List<Written> all = new ArrayList<>();
            for (var future : futures)
                all.addAll(future.get());
            writer.close();

            all.sort(Comparator.comparingLong(w -> w.entry().offset()));
            long next = 0;
            for (Written w : all) {
                assertEquals(next, w.entry().offset(), "appends overlapped or left a gap");
                next = w.entry().end();
            }
            assertEquals(next, Files.size(pak));

            try (var reader = PakReader.open(pak)) {
                for (Written w : all)
                    WebpEncoderTest.assertPixels(w.picture(), Pictures.decode(reader.read(w.entry())));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
