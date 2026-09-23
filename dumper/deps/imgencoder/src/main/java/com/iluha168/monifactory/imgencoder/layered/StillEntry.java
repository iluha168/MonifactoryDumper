package com.iluha168.monifactory.imgencoder.layered;

import com.iluha168.monifactory.imgencoder.Frame;
import com.iluha168.monifactory.imgencoder.PakEntry;

/**
 * One row of {@code stills.json}: where a still's WebP sits in {@code stills.pak}, and the still's size. The size is
 * here so a reader can check a layer's stills agree without decoding any of them.
 */
public record StillEntry(PakEntry payload, int width, int height) {
    public StillEntry {
        if (width < 1 || height < 1 || width > Frame.MAX_SIDE || height > Frame.MAX_SIDE)
            throw new IllegalArgumentException("a " + width + "x" + height + " still");
    }

    /** From the four numbers of a {@code stills.json} row, in its order. */
    public StillEntry(long offset, int length, int width, int height) {
        this(new PakEntry(offset, length), width, height);
    }
}
