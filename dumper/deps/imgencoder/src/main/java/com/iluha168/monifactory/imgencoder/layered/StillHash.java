package com.iluha168.monifactory.imgencoder.layered;

import com.iluha168.monifactory.imgencoder.Frame;

/**
 * A still's identity: 128 bits of hash over its width, height and pixels. Two stills with the same hash are taken to
 * be the same still and stored once, without comparing pixels, because the pixels of every still ever seen do not fit
 * in memory.
 * <p>
 * The two halves come from two unrelated 64-bit mixes over the same words, so an accidental collision needs both to
 * collide at once. Nobody picks the pixels to attack this; the renderer draws them. Each mix reads two pixels per step
 * as one long and is a single multiply deep per step, which keeps a 344x230 card well under a millisecond.
 */
public record StillHash(long high, long low) {
    private static final long SEED_HIGH = 0x6A09E667F3BCC908L;
    private static final long SEED_LOW = 0xBB67AE8584CAA73BL;
    private static final long K1 = 0x9E3779B97F4A7C15L;
    private static final long K2 = 0xC2B2AE3D27D4EB4FL;
    private static final long K3 = 0x87C37B91114253D5L;

    public static StillHash of(Frame still) {
        int[] argb = still.argb();
        // The size goes in first, so a 2x3 and a 3x2 still with the same six pixels differ.
        long size = (long) still.width() << 32 | still.height();
        long high = (SEED_HIGH ^ size) * K1;
        long low = Long.rotateLeft(SEED_LOW + size * K2, 31) * K3;
        int i = 0;
        for (; i + 1 < argb.length; i += 2) {
            long word = argb[i] & 0xFFFFFFFFL | (long) argb[i + 1] << 32;
            high = Long.rotateLeft((high ^ word) * K1, 29);
            low = Long.rotateLeft(low + word * K2, 31) * K3;
        }
        if (i < argb.length) {
            long word = argb[i] & 0xFFFFFFFFL;
            high = Long.rotateLeft((high ^ word) * K1, 29);
            low = Long.rotateLeft(low + word * K2, 31) * K3;
        }
        return new StillHash(finish(high ^ argb.length), finish(low + argb.length));
    }

    /** MurmurHash3's 64-bit finaliser: every input bit reaches every output bit. */
    private static long finish(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    @Override
    public String toString() {
        return String.format("%016x%016x", high, low);
    }
}
