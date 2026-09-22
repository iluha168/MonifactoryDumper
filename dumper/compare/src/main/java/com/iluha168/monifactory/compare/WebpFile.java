package com.iluha168.monifactory.compare;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * One WebP payload out of {@code images.pak}, read back the way a consumer would: a still goes through libwebp whole,
 * an animation has its container walked here and every frame's image decoded by libwebp on its own.
 * <p>
 * libwebp's demuxer is not in the fork, so the container is parsed by hand after the WebP container spec. This is the
 * reader side of the muxer in :dumper:deps:imgencoder, written against the spec rather than against the muxer.
 */
record WebpFile(int width, int height, boolean animated, int loops, int frames, long durationMillis) {
    private static final int ANIMATION = 0x02;

    /** The fork's reader, one per thread; ImageIO's service lookup costs more than a small card's decode. */
    private static final ThreadLocal<ImageReader> READER = ThreadLocal.withInitial(() -> {
        ImageIO.setUseCache(false);
        var readers = ImageIO.getImageReadersByFormatName("webp");
        if (!readers.hasNext()) throw new IllegalStateException("no WebP reader; webp-imageio is not on the classpath");
        return readers.next();
    });

    /** Decodes {@code webp} completely, or throws saying why it cannot be. */
    static WebpFile decode(byte[] webp) throws IOException {
        require(webp.length >= 20, "only " + webp.length + " B");
        require(ascii(webp, 0).equals("RIFF") && ascii(webp, 8).equals("WEBP"), "not a RIFF WEBP file");
        require(u32(webp, 4) == webp.length - 8, "RIFF size " + u32(webp, 4) + " but the payload has "
                + (webp.length - 8) + " B after the header");
        if (!ascii(webp, 12).equals("VP8X") || (webp[20] & ANIMATION) == 0) {
            BufferedImage still = still(webp);
            return new WebpFile(still.getWidth(), still.getHeight(), false, 0, 1, 0);
        }

        int width = u24(webp, 24) + 1, height = u24(webp, 27) + 1;
        int loops = -1, frames = 0;
        long duration = 0;
        int p = 30;
        while (p + 8 <= webp.length) {
            String id = ascii(webp, p);
            int length = u32(webp, p + 4);
            int body = p + 8;
            require(length >= 0 && body + length <= webp.length, id + " chunk at " + p + " runs past the end");
            switch (id) {
                case "ANIM" -> loops = (webp[body + 4] & 0xFF) | (webp[body + 5] & 0xFF) << 8;
                case "ANMF" -> {
                    require(loops >= 0, "ANMF before ANIM");
                    int x = u24(webp, body) * 2, y = u24(webp, body + 3) * 2;
                    int w = u24(webp, body + 6) + 1, h = u24(webp, body + 9) + 1;
                    require(x + w <= width && y + h <= height, "frame " + frames + " (" + w + "x" + h + " at " + x
                            + "," + y + ") leaves the " + width + "x" + height + " canvas");
                    BufferedImage image = still(wrap(Arrays.copyOfRange(webp, body + 16, body + length)));
                    require(image.getWidth() == w && image.getHeight() == h, "frame " + frames + " decodes to "
                            + image.getWidth() + "x" + image.getHeight() + ", its header says " + w + "x" + h);
                    duration += u24(webp, body + 12);
                    frames++;
                }
                default -> {
                }
            }
            p = body + length + (length & 1);
        }
        require(p == webp.length, (webp.length - p) + " trailing bytes");
        require(frames > 0, "an animation with no frames");
        return new WebpFile(width, height, true, loops, frames, duration);
    }

    /** A frame's image chunks as a file of their own, which is what libwebp's still decoder takes. */
    private static byte[] wrap(byte[] chunks) {
        byte[] file = new byte[12 + chunks.length];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, file, 0, 4);
        int size = 4 + chunks.length;
        file[4] = (byte) size;
        file[5] = (byte) (size >>> 8);
        file[6] = (byte) (size >>> 16);
        file[7] = (byte) (size >>> 24);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, file, 8, 4);
        System.arraycopy(chunks, 0, file, 12, chunks.length);
        return file;
    }

    private static BufferedImage still(byte[] webp) throws IOException {
        ImageReader reader = READER.get();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(webp))) {
            reader.setInput(in, true, true);
            BufferedImage image = reader.read(0);
            require(image != null, "libwebp returned no image");
            return image;
        } catch (IOException | RuntimeException e) {
            // The fork reports a bad bitstream as an IOException or an IllegalStateException, depending on where.
            throw new IOException("libwebp cannot decode it: " + e.getMessage(), e);
        }
    }

    private static void require(boolean condition, String problem) throws IOException {
        if (!condition) throw new IOException(problem);
    }

    private static String ascii(byte[] b, int at) {
        return new String(b, at, 4, StandardCharsets.US_ASCII);
    }

    private static int u24(byte[] b, int at) {
        return b[at] & 0xFF | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16;
    }

    private static int u32(byte[] b, int at) {
        return u24(b, at) | (b[at + 3] & 0xFF) << 24;
    }
}
