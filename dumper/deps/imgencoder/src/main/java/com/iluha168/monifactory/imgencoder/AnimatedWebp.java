package com.iluha168.monifactory.imgencoder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Animated lossless WebP, the container written here in Java around libwebp's still encoder. Ported from the
 * prototype {@code ignored/enc/src/AnimWebp.java} with its logic unchanged, so its measurements hold: smaller than
 * {@code img2webp -min_size} on 40 of 41 test sets and pixel-exact on all of them.
 * <p>
 * The file is RIFF with VP8X, ANIM and one ANMF per stored frame. Per frame the muxer takes the bounding box of the
 * pixels that changed since the previous frame, snaps its origin down to even (ANMF stores x and y halved), and
 * encodes two candidates: the opaque crop with blending off, and the same crop with its unchanged pixels punched to
 * alpha 0 and blending on, so the decoder composites them away and the encoder gets long runs of one colour. The
 * smaller wins. That second candidate is the stand-in for libwebp's {@code -min_size} and is worth 46.6% on the test
 * corpus. Runs of identical frames become one ANMF with a longer duration, as {@code WebPAnimEncoder} does.
 * <p>
 * A sequence that never changes comes out as the bare still, so there is one path for every recipe.
 */
final class AnimatedWebp {
    private AnimatedWebp() {
    }

    enum Effort {
        /** Both candidates for every frame, keep the smaller. */
        SEARCH,
        /** Only the alpha-punched crop: half the encodes, +0.24% bytes on the census sets. */
        MASKED
    }

    /**
     * Above this many pixels over all frames the search is skipped (PLAN section 4's effort gate). The census's
     * largest Policy B set was 5.34 Mpx, so the gate is for outliers.
     */
    static final long SEARCH_LIMIT_PIXELS = 8_000_000L;

    static Effort effortFor(List<Frame> frames) {
        Frame first = frames.get(0);
        return (long) frames.size() * first.width() * first.height() > SEARCH_LIMIT_PIXELS ? Effort.MASKED : Effort.SEARCH;
    }

    private record Stored(byte[] payload, int x, int y, int w, int h, byte flags, int duration) {
    }

    /** ANMF flags: bit 1 is "do not blend", bit 0 "dispose to background". Neither frame kind disposes. */
    private static final byte NO_BLEND = 0x02, BLEND = 0x00;

    static byte[] encode(WebpEncoder encoder, List<Frame> frames, int frameMillis, Effort effort) {
        Frame first = frames.get(0);
        int cw = first.width(), ch = first.height();
        List<Stored> out = new ArrayList<>();
        int[] canvas = null;
        byte[] keyframe = null;
        boolean anyAlpha = false;

        for (Frame frame : frames) {
            if (frame.width() != cw || frame.height() != ch)
                throw new IllegalArgumentException("frame of " + frame.width() + "x" + frame.height() + " in a " + cw
                        + "x" + ch + " animation");
            int[] cur = frame.argb();
            if (canvas != null && Arrays.equals(canvas, cur)) {
                Stored last = out.remove(out.size() - 1);
                out.add(new Stored(last.payload, last.x, last.y, last.w, last.h, last.flags,
                        last.duration + frameMillis));
                continue;
            }
            if (canvas == null) {
                // The prototype only ever saw opaque frames and set the ALPHA flag for punched crops alone. A decoder
                // takes the flag at its word and shows every pixel opaque without it, so any frame with alpha sets it.
                anyAlpha |= !frame.opaque();
                keyframe = encoder.stillFile(cur, cw, ch);
                out.add(new Stored(imageChunks(keyframe), 0, 0, cw, ch, NO_BLEND, frameMillis));
                canvas = cur.clone();
                continue;
            }

            int x0 = cw, y0 = ch, x1 = -1, y1 = -1;
            for (int y = 0; y < ch; y++) {
                int row = y * cw;
                for (int x = 0; x < cw; x++) {
                    if (canvas[row + x] != cur[row + x]) {
                        if (x < x0) x0 = x;
                        if (x > x1) x1 = x;
                        if (y < y0) y0 = y;
                        if (y > y1) y1 = y;
                    }
                }
            }
            x0 &= ~1;
            y0 &= ~1;
            int fw = x1 - x0 + 1, fh = y1 - y0 + 1;

            int[] masked = new int[fw * fh];
            // Blending is only exact for opaque pixels: a translucent one would be composited over the old canvas,
            // and a changed pixel that is itself alpha 0 would read as "unchanged". The renderer's frames are opaque,
            // so this never fires on them; it keeps the muxer honest on anything else.
            boolean blendable = true;
            for (int y = 0; y < fh; y++) {
                int sr = (y0 + y) * cw + x0, dr = y * fw;
                for (int x = 0; x < fw; x++) {
                    int v = cur[sr + x];
                    if (canvas[sr + x] == v) {
                        masked[dr + x] = 0;
                    } else {
                        masked[dr + x] = v;
                        if (v >>> 24 != 0xFF) blendable = false;
                    }
                }
            }

            byte[] best = null;
            byte bestFlags = NO_BLEND;
            boolean cropAlpha = false;
            if (effort == Effort.SEARCH || !blendable) {
                int[] crop = new int[fw * fh];
                for (int y = 0; y < fh; y++)
                    System.arraycopy(cur, (y0 + y) * cw + x0, crop, y * fw, fw);
                best = imageChunks(encoder.stillFile(crop, fw, fh));
                cropAlpha = !new Frame(fw, fh, crop).opaque();
            }
            if (blendable) {
                byte[] b = imageChunks(encoder.hiddenFile(masked, fw, fh));
                if (best == null || b.length < best.length) {
                    best = b;
                    bestFlags = BLEND;
                    anyAlpha = true;
                }
            }

            if (bestFlags == NO_BLEND) anyAlpha |= cropAlpha;
            out.add(new Stored(best, x0, y0, fw, fh, bestFlags, frameMillis));
            canvas = cur.clone();
        }

        // Exactly one frame that never changes: the bare still the keyframe pass already produced, not an animation.
        if (out.size() == 1) return keyframe;

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteArrayOutputStream vp8x = new ByteArrayOutputStream();
        vp8x.write(anyAlpha ? 0x02 | 0x10 : 0x02); // ANIMATION [| ALPHA]
        u24(vp8x, 0);
        u24(vp8x, cw - 1);
        u24(vp8x, ch - 1);
        chunk(body, "VP8X", vp8x.toByteArray());

        ByteArrayOutputStream anim = new ByteArrayOutputStream();
        u32(anim, 0); // background colour, transparent
        u16(anim, 0); // loop count, 0 is forever
        chunk(body, "ANIM", anim.toByteArray());

        for (Stored s : out) {
            if (s.duration > 0xFFFFFF)
                throw new IllegalArgumentException("a frame shown for " + s.duration + " ms does not fit ANMF's 24 bits");
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            u24(f, s.x / 2);
            u24(f, s.y / 2);
            u24(f, s.w - 1);
            u24(f, s.h - 1);
            u24(f, s.duration);
            f.write(s.flags);
            f.write(s.payload, 0, s.payload.length);
            chunk(body, "ANMF", f.toByteArray());
        }

        ByteArrayOutputStream file = new ByteArrayOutputStream(body.size() + 12);
        fourcc(file, "RIFF");
        u32(file, 4 + body.size());
        fourcc(file, "WEBP");
        file.writeBytes(body.toByteArray());
        return file.toByteArray();
    }

    /**
     * The image sub-chunks (VP8, VP8L, ALPH) of a complete still file, without its RIFF header or anything else.
     * An ANMF payload carries exactly these.
     */
    static byte[] imageChunks(byte[] still) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(still.length);
        int p = 12; // "RIFF" <size> "WEBP"
        while (p + 8 <= still.length) {
            String id = new String(still, p, 4, StandardCharsets.US_ASCII);
            int len = (still[p + 4] & 0xFF) | (still[p + 5] & 0xFF) << 8 | (still[p + 6] & 0xFF) << 16
                    | (still[p + 7] & 0xFF) << 24;
            if (id.equals("VP8 ") || id.equals("VP8L") || id.equals("ALPH")) {
                out.write(still, p, 8 + len);
                if ((len & 1) == 1) out.write(0);
            }
            p += 8 + len + (len & 1);
        }
        return out.toByteArray();
    }

    private static void u16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    private static void u24(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
    }

    private static void u32(ByteArrayOutputStream o, int v) {
        u16(o, v);
        u16(o, v >>> 16);
    }

    private static void fourcc(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < 4; i++) o.write(s.charAt(i));
    }

    private static void chunk(ByteArrayOutputStream o, String id, byte[] payload) {
        fourcc(o, id);
        u32(o, payload.length);
        o.write(payload, 0, payload.length);
        if ((payload.length & 1) == 1) o.write(0); // RIFF chunks are padded to even
    }
}
