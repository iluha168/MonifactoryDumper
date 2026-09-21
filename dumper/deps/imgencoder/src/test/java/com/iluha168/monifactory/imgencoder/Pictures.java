package com.iluha168.monifactory.imgencoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.SplittableRandom;

/** Test pictures, and a decode that goes through the fork's own reader. */
final class Pictures {
    private Pictures() {
    }

    /** Pixel art the way the renderer makes it: a small palette, flat areas, every texel drawn as a 2x2 block. */
    static Frame card(int width, int height, long seed) {
        var random = new SplittableRandom(seed);
        int[] palette = new int[12];
        for (int i = 0; i < palette.length; i++)
            palette[i] = 0xFF000000 | random.nextInt(0x1000000);
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int tx = x / 2, ty = y / 2;
                boolean border = tx < 2 || ty < 2 || tx >= width / 2 - 2 || ty >= height / 2 - 2;
                int texel = border ? 0 : (tx / 8 + ty / 8) % 3 == 0 ? 1 + (tx * 31 + ty * 17) % 11 : 2;
                argb[y * width + x] = palette[texel];
            }
        return new Frame(width, height, argb);
    }

    /** Incompressible opaque noise, the worst case for the encoder's transforms. */
    static Frame noise(int width, int height, long seed) {
        var random = new SplittableRandom(seed);
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++)
            argb[i] = 0xFF000000 | random.nextInt(0x1000000);
        return new Frame(width, height, argb);
    }

    /** Every alpha from 0 to 255, colour under alpha 0 included, which a non-exact encoder would throw away. */
    static Frame translucent(int width, int height, long seed) {
        var random = new SplittableRandom(seed);
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++)
            argb[i] = random.nextInt();
        for (int i = 0; i < argb.length; i += 7)
            argb[i] &= 0x00FFFFFF;
        return new Frame(width, height, argb);
    }

    static Frame decode(byte[] webp) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(webp));
        if (image == null)
            throw new IOException("no ImageIO reader took the file");
        int w = image.getWidth(), h = image.getHeight();
        return new Frame(w, h, image.getRGB(0, 0, w, h, null, 0, w));
    }
}
