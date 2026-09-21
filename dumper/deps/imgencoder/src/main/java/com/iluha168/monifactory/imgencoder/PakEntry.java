package com.iluha168.monifactory.imgencoder;

/**
 * Where one payload sits in {@code images.pak}. These two numbers are the {@code offset} and {@code bytes} fields of
 * the recipe's record in {@code recipes.json} (PLAN section 6): the record points at its image, nothing is keyed.
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
