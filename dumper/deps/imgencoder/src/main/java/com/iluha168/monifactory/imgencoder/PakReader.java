package com.iluha168.monifactory.imgencoder;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Reads payloads back out of a pack by the entries {@link PakWriter} handed out. Thread-safe. */
public final class PakReader implements AutoCloseable {
    private final FileChannel channel;
    private final long size;

    private PakReader(FileChannel channel) throws IOException {
        this.channel = channel;
        this.size = channel.size();
    }

    public static PakReader open(Path file) throws IOException {
        return new PakReader(FileChannel.open(file, StandardOpenOption.READ));
    }

    public long size() {
        return size;
    }

    public byte[] read(PakEntry entry) throws IOException {
        if (entry.end() > size)
            throw new EOFException("entry " + entry + " runs past the end of a " + size + " B pack");
        var buffer = ByteBuffer.allocate(entry.length());
        while (buffer.hasRemaining()) {
            // Positional reads leave the channel's own position alone, which is what makes this safe to share.
            if (channel.read(buffer, entry.offset() + buffer.position()) < 0)
                throw new EOFException("pack shrank under the reader");
        }
        return buffer.array();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
