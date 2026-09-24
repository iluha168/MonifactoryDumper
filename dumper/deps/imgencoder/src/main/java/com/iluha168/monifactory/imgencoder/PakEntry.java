package com.iluha168.monifactory.imgencoder;

/**
 * Where one payload sits in a pack such as {@code stills.pak}: the first two numbers of the still's row in
 * {@code stills.json}.
 */
public record PakEntry(long offset, int length) {
    public PakEntry {
        if (offset < 0 || length < 1)
            throw new IllegalArgumentException("offset " + offset + ", length " + length);
    }

    /** The first byte after this payload. */
    public long end() {
        return offset + length;
    }
}
