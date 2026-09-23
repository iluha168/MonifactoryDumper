package com.iluha168.monifactory.compare;

import com.iluha168.monifactory.imgencoder.Frame;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * One still out of {@code stills.pak}, read back the way a consumer would: through libwebp, whole, to straight ARGB
 * with the top row first.
 * <p>
 * A still is one image. The container is checked by hand first, after the WebP container spec, because libwebp's
 * still decoder would take an animated file and quietly hand back its first frame.
 */
final class WebpFile {
    /** The animation bit of a VP8X header's flags. */
    private static final int ANIMATION = 0x02;

    /** The fork's reader, one per thread; ImageIO's service lookup costs more than a small still's decode. */
    private static final ThreadLocal<ImageReader> READER = ThreadLocal.withInitial(() -> {
        ImageIO.setUseCache(false);
        var readers = ImageIO.getImageReadersByFormatName("webp");
        if (!readers.hasNext()) throw new IllegalStateException("no WebP reader; webp-imageio is not on the classpath");
        return readers.next();
    });

    private WebpFile() {
    }

    /** Decodes {@code webp}, which must be a single still, or throws saying why it cannot. */
    static Frame decode(byte[] webp) throws IOException {
        require(webp.length >= 21, "only " + webp.length + " B");
        require(ascii(webp, 0).equals("RIFF") && ascii(webp, 8).equals("WEBP"), "not a RIFF WEBP file");
        require(u32(webp, 4) == webp.length - 8, "RIFF size " + u32(webp, 4) + " but the payload has "
                + (webp.length - 8) + " B after the header");
        require(!ascii(webp, 12).equals("VP8X") || (webp[20] & ANIMATION) == 0,
                "an animation, but a still is a single image");
        ImageReader reader = READER.get();
        BufferedImage image;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(webp))) {
            reader.setInput(in, true, true);
            image = reader.read(0);
        } catch (IOException | RuntimeException e) {
            // The fork reports a bad bitstream as an IOException or an IllegalStateException, depending on where.
            throw new IOException("libwebp cannot decode it: " + e.getMessage(), e);
        }
        require(image != null, "libwebp returned no image");
        int width = image.getWidth(), height = image.getHeight();
        return new Frame(width, height, image.getRGB(0, 0, width, height, null, 0, width));
    }

    private static void require(boolean condition, String problem) throws IOException {
        if (!condition) throw new IOException(problem);
    }

    private static String ascii(byte[] b, int at) {
        return new String(b, at, 4, StandardCharsets.US_ASCII);
    }

    private static int u32(byte[] b, int at) {
        return b[at] & 0xFF | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }
}
