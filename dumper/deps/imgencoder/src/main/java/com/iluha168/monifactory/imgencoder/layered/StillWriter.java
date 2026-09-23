package com.iluha168.monifactory.imgencoder.layered;

import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.PakEntry;
import com.iluha168.monifactory.imgencoder.PakWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Writes {@code stills.pak} and {@code stills.json}: gives every distinct still an id the first time it is seen, and
 * stores it once.
 * <p>
 * Ids go out in the order {@link #add} sees stills, so the renderer calls it from one thread in record order and the
 * same corpus gets the same ids. A new still is handed to the caller's executor to encode, and {@link #add} returns at
 * once. Encodes finish in any order; whichever worker finishes the still the pak is waiting for appends it, and every
 * finished one after it, so the pak is always in id order and never holds more than the stills that could be written.
 * Nobody has to poll. {@link #waitingBytes} is what the renderer holds its drawing back on.
 * <p>
 * A still that fails to encode leaves a hole no record can do without, since other records may already point at its
 * id. So one failure fails the whole writer: the next {@link #add} throws, and so does {@link #finish}.
 */
public final class StillWriter implements AutoCloseable {
    private final Path directory;
    private final PakWriter pak;
    private final Executor encoders;
    private final Function<Frame, byte[]> encoder;
    private final Map<StillHash, Integer> ids = new HashMap<>();
    /** Encoded stills that wait for an earlier id before they can go into the pak. */
    private final Map<Integer, Encoded> finished = new HashMap<>();
    /** One per still in the pak, in id order. */
    private final List<StillEntry> written = new ArrayList<>();
    private final AtomicLong waitingBytes = new AtomicLong();
    private Throwable failure;
    private boolean closed;

    private record Encoded(byte[] payload, int width, int height) {
    }

    private StillWriter(Path directory, PakWriter pak, Executor encoders, Function<Frame, byte[]> encoder) {
        this.directory = directory;
        this.pak = pak;
        this.encoders = encoders;
        this.encoder = encoder;
    }

    /**
     * Starts {@link StillTable#PAK} in {@code directory}, emptying it if it exists. {@code encoder} runs on
     * {@code encoders}' threads, several at once, so it must be safe to share: one {@code WebpEncoder} per thread
     * behind it, for instance.
     */
    public static StillWriter create(Path directory, Executor encoders, Function<Frame, byte[]> encoder)
            throws IOException {
        return new StillWriter(directory, PakWriter.create(directory.resolve(StillTable.PAK)), encoders, encoder);
    }

    /** {@link #add(StillHash, Frame)} with the hash worked out here. */
    public int add(Frame still) {
        return add(StillHash.of(still), still);
    }

    /**
     * The id of {@code still}, whose hash is {@code hash}. A still not seen before gets the next id and goes to an
     * encoder; the writer keeps the frame until then, so do not write to its pixels again. The hash is taken as given,
     * so it can be worked out off the calling thread.
     */
    public int add(StillHash hash, Frame still) {
        int id;
        synchronized (this) {
            if (closed) throw new IllegalStateException("the still writer is closed");
            if (failure != null) throw new IllegalStateException("a still failed to encode", failure);
            Integer known = ids.get(hash);
            if (known != null) return known;
            id = ids.size();
            ids.put(hash, id);
        }
        long bytes = 4L * still.argb().length;
        waitingBytes.addAndGet(bytes);
        try {
            encoders.execute(() -> encode(id, still, bytes));
        } catch (RejectedExecutionException e) {
            waitingBytes.addAndGet(-bytes);
            fail(e);
            throw e;
        }
        return id;
    }

    private void encode(int id, Frame still, long bytes) {
        byte[] payload;
        try {
            payload = encoder.apply(still);
        } catch (Throwable t) {
            fail(t);
            return;
        } finally {
            waitingBytes.addAndGet(-bytes);
        }
        synchronized (this) {
            if (closed || failure != null) return;
            finished.put(id, new Encoded(payload, still.width(), still.height()));
            try {
                Encoded next;
                while ((next = finished.remove(written.size())) != null) {
                    PakEntry entry = pak.append(next.payload());
                    written.add(new StillEntry(entry, next.width(), next.height()));
                }
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
            notifyAll();
        }
    }

    private synchronized void fail(Throwable t) {
        if (failure == null) failure = t;
        finished.clear();
        notifyAll();
    }

    /** Bytes of pixels handed to an encoder and not encoded yet. */
    public long waitingBytes() {
        return waitingBytes.get();
    }

    /** Distinct stills seen so far, which is also the id the next new one gets. */
    public synchronized int count() {
        return ids.size();
    }

    /**
     * True once every still added so far is in the pak, or a still has failed. {@link #finish} would not wait then,
     * which is what a caller that must not block, like the game loop, checks first.
     */
    public synchronized boolean settled() {
        return written.size() == ids.size() || failure != null;
    }

    /**
     * Waits for every encode, closes the pak and writes {@link StillTable#JSON} next to it. Nothing may be added after
     * this. Throws if any still failed, and then writes no table: a table with a hole in it is no table.
     */
    public StillTable finish() throws IOException, InterruptedException {
        StillTable table;
        synchronized (this) {
            if (closed) throw new IllegalStateException("the still writer is closed");
            while (written.size() < ids.size() && failure == null)
                wait();
            closed = true;
            if (failure != null) {
                pak.close();
                throw new IOException("a still failed to encode", failure);
            }
            table = new StillTable(written);
        }
        pak.close();
        table.write(directory.resolve(StillTable.JSON));
        return table;
    }

    /**
     * Stops without a table, for a run that is being abandoned: encodes still running are dropped as they finish. The
     * executor is the caller's to shut down. Harmless after {@link #finish}.
     */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            closed = true;
            finished.clear();
        }
        pak.close();
    }
}
