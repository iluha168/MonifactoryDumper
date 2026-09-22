package com.iluha168.monifactory.imgencoder;

import com.luciad.imageio.webp.CompressionType;
import com.luciad.imageio.webp.WebPImageWriterSpi;
import com.luciad.imageio.webp.WebPWrapper;
import com.luciad.imageio.webp.WebPWriteParam;
import com.luciad.imageio.webp.WebPWriter;

import javax.imageio.IIOImage;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Lossless WebP from rendered frames, through libwebp's still encoder in {@code com.github.usefulness:webp-imageio}.
 * <p>
 * Every picture this project emits comes out of {@link #encode(List, int)}. A sequence whose frames are all the same
 * is a static recipe, and it becomes a bare VP8L still. That is the file {@code cwebp -z 9} writes: lossless,
 * quality 100, method 6. A sequence that changes becomes an animated WebP ({@link AnimatedWebp}), whose every ANMF
 * holds the image chunks of a {@link #stillFile}.
 * <p>
 * Output is exact: decoding gives back every input pixel bit for bit, including the colour under alpha 0, which
 * libwebp would otherwise feel free to rewrite. For opaque input that switch changes nothing, and opaque input is
 * all the renderer produces. The one place it is off is inside an animation, for the crops whose alpha 0 pixels the
 * decoder never shows ({@link #hiddenFile}).
 * <p>
 * Not thread-safe. The encode itself is single-threaded native code, so run one encoder per worker thread.
 */
public final class WebpEncoder {
    /** libwebp's effort, 0 to 6. 6 is {@code cwebp -z 9}. */
    public static final int METHOD = 6;

    private static final DirectColorModel ARGB = new DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000);
    // The same packed ints read without their top byte. The writer picks its 3-channel path off the colour model
    // alone (ColorModel.hasAlpha), so an opaque frame has to be handed over in this model to leave the alpha plane
    // out. That saves a few bytes per frame and keeps statics byte-identical to cwebp.
    private static final DirectColorModel RGB = new DirectColorModel(24, 0xFF0000, 0xFF00, 0xFF);

    private final WebPWriter writer = new WebPWriter(new WebPImageWriterSpi());
    private final WebPWriteParam param;
    /**
     * The same settings without {@code exact}, for the muxer's alpha-punched crops. Their alpha 0 pixels are
     * composited away, so their colour is nobody's business, and letting libwebp pick it is worth up to 7% on a frame
     * (the prototype's anvil and grinding sets, 36,718 B against 39,362 B with exact on).
     */
    private final WebPWriteParam hiddenParam;

    public WebpEncoder() {
        // The Kotlin fork loads its library lazily and swallows the failure (it prints a stack trace and marks itself
        // loaded), so a missing .so only shows up as the first native call failing.
        WebPWrapper.loadNativeLibrary();
        param = settings(true);
        hiddenParam = settings(false);
    }

    private static WebPWriteParam settings(boolean exact) {
        WebPWriteParam param;
        try {
            param = new WebPWriteParam(null);
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException("libwebp-imageio did not load; see the stack trace printed before this", e);
        }
        param.setCompressionType(CompressionType.Lossless);
        param.setCompressionQuality(1f); // ImageIO's 0..1, which the fork scales to libwebp's 100
        param.setMethod(METHOD);
        param.setExact(exact);
        return param;
    }

    /**
     * Encodes a frame sequence shown {@code frameMillis} apart, looping forever. Frames that never change give the
     * still; anything else is an animated WebP from {@link AnimatedWebp}, at the effort PLAN section 4 sets for its
     * size.
     */
    public byte[] encode(List<Frame> frames, int frameMillis) {
        if (frames.isEmpty())
            throw new IllegalArgumentException("nothing to encode");
        return encode(frames, frameMillis, AnimatedWebp.effortFor(frames));
    }

    byte[] encode(List<Frame> frames, int frameMillis, AnimatedWebp.Effort effort) {
        if (frames.isEmpty())
            throw new IllegalArgumentException("nothing to encode");
        if (frameMillis < 1 || frameMillis > 0xFFFFFF)
            throw new IllegalArgumentException("an ANMF duration is 24 bits of milliseconds, not " + frameMillis);
        return AnimatedWebp.encode(this, frames, frameMillis, effort);
    }

    /** A single still. */
    public byte[] encode(Frame frame) {
        return stillFile(frame.argb(), frame.width(), frame.height());
    }

    /** A complete {@code RIFF....WEBPVP8L} file for one rectangle of packed ARGB. */
    byte[] stillFile(int[] argb, int width, int height) {
        return stillFile(argb, width, height, param);
    }

    /** {@link #stillFile} for a picture whose alpha 0 pixels are never shown, so their colour need not survive. */
    byte[] hiddenFile(int[] argb, int width, int height) {
        return stillFile(argb, width, height, hiddenParam);
    }

    private byte[] stillFile(int[] argb, int width, int height, WebPWriteParam param) {
        boolean opaque = new Frame(width, height, argb).opaque();
        DirectColorModel model = opaque ? RGB : ARGB;
        var raster = Raster.createPackedRaster(new DataBufferInt(argb, argb.length), width, height, width,
                model.getMasks(), null);
        var image = new BufferedImage(model, raster, false, null);

        var bytes = new ByteArrayOutputStream(opaque ? 2048 : 4096);
        try (var out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // only the in-memory stream can throw, and it does not
        } finally {
            writer.setOutput(null);
        }
        return bytes.toByteArray();
    }
}
