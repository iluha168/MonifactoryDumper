package com.iluha168.monifactory.imgencoder.layered;

import com.iluha168.monifactory.imgencoder.Frame;

import java.util.List;

/**
 * Draws layer pictures onto one canvas the way a consumer of format 2 does (DESIGN 2.2). The renderer uses it too, to
 * check its layers against the real render before it trusts them.
 * <p>
 * The first picture is layer 0, and its alpha is the result's: 0 outside its box. It is put down as it is, which is
 * what straight-alpha "over" does onto a transparent canvas, so a single translucent layer comes back unchanged. Every
 * later picture goes over it per colour channel as {@code c = cs*as + c*(1-as)}, with {@code as} its own alpha, and
 * leaves the alpha alone. That is GL's {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA} colour blend, Minecraft's default, and it
 * matches the real render exactly wherever layer 0 is opaque. Where layer 0 is translucent and something is drawn over
 * it, the real render's alpha would grow and this one's does not; the renderer's check against the real render is what
 * catches a recipe where that shows.
 * <p>
 * The arithmetic is on 8-bit integers, rounded to the nearest level after every layer:
 * {@code (cs*as + c*(255-as) + 127) / 255}. A GL render target of 8 bits a channel rounds after every draw the same
 * way, and since 255 is odd there are no ties to break. Colour where the result's alpha is 0 is meaningless, so it is
 * set to 0: two composites of the same picture are then equal pixel for pixel.
 */
public final class Compositor {
    private Compositor() {
    }

    /** A picture, top-left at ({@code x}, {@code y}) on the canvas. */
    public record Placed(int x, int y, Frame picture) {
    }

    /** The canvas after every picture has been drawn onto it in order. The first one is layer 0. */
    public static Frame composite(int width, int height, List<Placed> pictures) {
        if (pictures.isEmpty())
            throw new IllegalArgumentException("nothing to composite: layer 0 is missing");
        int[] canvas = new int[width * height];
        for (int i = 0; i < pictures.size(); i++) {
            Placed placed = pictures.get(i);
            Frame picture = placed.picture();
            int w = picture.width(), h = picture.height();
            if (placed.x() < 0 || placed.y() < 0 || placed.x() + w > width || placed.y() + h > height)
                throw new IllegalArgumentException("picture " + i + " (" + w + "x" + h + " at " + placed.x() + ","
                        + placed.y() + ") leaves the " + width + "x" + height + " canvas");
            int[] source = picture.argb();
            for (int y = 0; y < h; y++) {
                int from = y * w, to = (placed.y() + y) * width + placed.x();
                if (i == 0) {
                    System.arraycopy(source, from, canvas, to, w);
                    continue;
                }
                for (int x = 0; x < w; x++)
                    canvas[to + x] = over(source[from + x], canvas[to + x]);
            }
        }
        for (int p = 0; p < canvas.length; p++)
            if (canvas[p] >>> 24 == 0) canvas[p] = 0;
        return new Frame(width, height, canvas);
    }

    /** {@code source}'s colour over {@code below}'s, keeping {@code below}'s alpha. */
    static int over(int source, int below) {
        int a = source >>> 24;
        if (a == 0) return below;
        if (a == 0xFF) return below & 0xFF000000 | source & 0xFFFFFF;
        int keep = 0xFF - a;
        int r = ((source >>> 16 & 0xFF) * a + (below >>> 16 & 0xFF) * keep + 127) / 255;
        int g = ((source >>> 8 & 0xFF) * a + (below >>> 8 & 0xFF) * keep + 127) / 255;
        int b = ((source & 0xFF) * a + (below & 0xFF) * keep + 127) / 255;
        return below & 0xFF000000 | r << 16 | g << 8 | b;
    }
}
