package com.iluha168.monifactory.imgencoder.layered;

/**
 * One layer of a {@link LayeredImage}: a box on the canvas, top-left at ({@code x}, {@code y}) with y down, and the
 * loop of stills drawn into it. Every still of the loop is exactly the box's size.
 * <p>
 * The size is not in {@code recipes.json}; it is the size {@code stills.json} gives the layer's stills. The renderer
 * knows it from the crop box it cut them from, and a reader gets it from the table through {@link #of}.
 */
public record Layer(int x, int y, int width, int height, Timeline loop) {
    public Layer {
        if (x < 0 || y < 0)
            throw new IllegalArgumentException("a layer at " + x + "," + y);
        if (width < 1 || height < 1)
            throw new IllegalArgumentException("a " + width + "x" + height + " layer");
        if (loop == null)
            throw new IllegalArgumentException("a layer with no loop");
    }

    /** A layer read back from an artifact: its size is its stills' size, which must be the same for all of them. */
    public static Layer of(int x, int y, Timeline loop, StillTable stills) {
        StillEntry first = stills.get(loop.still(0));
        for (int i = 1; i < loop.size(); i++) {
            StillEntry still = stills.get(loop.still(i));
            if (still.width() != first.width() || still.height() != first.height())
                throw new IllegalArgumentException("still " + loop.still(i) + " is " + still.width() + "x"
                        + still.height() + ", but still " + loop.still(0) + " of the same layer is " + first.width()
                        + "x" + first.height());
        }
        return new Layer(x, y, first.width(), first.height(), loop);
    }

    /** Appends the layer's object in {@code recipes.json}: {@code {"x":..,"y":..,"f":[..],"d":[..]}}. */
    void appendJson(StringBuilder json) {
        json.append("{\"x\":").append(x).append(",\"y\":").append(y).append(',');
        loop.appendJson(json);
        json.append('}');
    }
}
