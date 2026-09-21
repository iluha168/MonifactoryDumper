package com.iluha168.monifactory.imgencoder;

import java.util.Arrays;

/**
 * One rendered picture: packed, non-premultiplied ARGB ({@code 0xAARRGGBB}), row-major, top row first.
 * <p>
 * The array is not copied. Hand over a buffer you will not write to again, because an animation keeps earlier frames
 * around to diff against.
 */
public record Frame(int width, int height, int[] argb) {
    /** The WebP format stores each dimension minus one in 14 bits. */
    public static final int MAX_SIDE = 16383;

    public Frame {
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE)
            throw new IllegalArgumentException("WebP cannot hold a " + width + "x" + height + " image");
        if (argb.length != width * height)
            throw new IllegalArgumentException(argb.length + " pixels for a " + width + "x" + height + " image");
    }

    /**
     * Converts from {@code 0xAABBGGRR}, the layout of Minecraft's {@code NativeImage.getPixelsRGBA()}
     * (RGBA bytes read as a little-endian int). The input array is left untouched.
     */
    public static Frame fromAbgr(int width, int height, int[] abgr) {
        int[] argb = new int[abgr.length];
        for (int i = 0; i < abgr.length; i++) {
            int p = abgr[i];
            argb[i] = p & 0xFF00FF00 | (p & 0xFF) << 16 | (p >>> 16) & 0xFF;
        }
        return new Frame(width, height, argb);
    }

    /** True when every pixel has alpha 255, so the encoder can drop the alpha plane. */
    public boolean opaque() {
        for (int p : argb)
            if (p >>> 24 != 0xFF) return false;
        return true;
    }

    /** Same size and the same pixels. The record's own equals compares the array by identity. */
    public boolean samePixels(Frame other) {
        return width == other.width && height == other.height && Arrays.equals(argb, other.argb);
    }
}
