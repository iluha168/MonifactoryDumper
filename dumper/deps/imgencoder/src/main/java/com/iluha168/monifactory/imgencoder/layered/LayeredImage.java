package com.iluha168.monifactory.imgencoder.layered;

import com.iluha168.monifactory.imgencoder.Frame;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * A recipe's picture in format 2: a {@code width} x {@code height} canvas and its layers in draw order. This is the
 * {@code "image"} object of a {@code recipes.json} record.
 * <p>
 * Layer 0's alpha is the picture's alpha. For a layered recipe it is EMI's card and static; for a recipe drawn whole
 * it is the only layer. Every layer loops on its own, so there is no common period to know.
 */
public record LayeredImage(int width, int height, List<Layer> layers) {
    public LayeredImage {
        if (width < 1 || height < 1)
            throw new IllegalArgumentException("a " + width + "x" + height + " canvas");
        layers = List.copyOf(layers);
        if (layers.isEmpty())
            throw new IllegalArgumentException("an image needs layer 0, whose alpha is the image's");
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            if (layer.x() + layer.width() > width || layer.y() + layer.height() > height)
                throw new IllegalArgumentException("layer " + i + " (" + layer.width() + "x" + layer.height() + " at "
                        + layer.x() + "," + layer.y() + ") leaves the " + width + "x" + height + " canvas");
        }
    }

    /** Any layer shows more than one still. */
    public boolean animated() {
        for (Layer layer : layers)
            if (layer.loop().animated()) return true;
        return false;
    }

    /**
     * The picture at {@code tick}, composited by {@link Compositor} from the stills {@code stills} hands out by id.
     * Throws if a still is not the size of its layer.
     */
    public Frame frameAt(long tick, IntFunction<Frame> stills) {
        List<Compositor.Placed> placed = new ArrayList<>(layers.size());
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            int id = layer.loop().stillAt(tick);
            Frame still = stills.apply(id);
            if (still.width() != layer.width() || still.height() != layer.height())
                throw new IllegalStateException("still " + id + " is " + still.width() + "x" + still.height()
                        + ", layer " + i + " is " + layer.width() + "x" + layer.height());
            placed.add(new Compositor.Placed(layer.x(), layer.y(), still));
        }
        return Compositor.composite(width, height, placed);
    }

    /**
     * Appends the object {@code recipes.json} holds, compact and with keys in a fixed order, so the same picture is
     * always the same text: {@code {"w":..,"h":..,"layers":[{"x":..,"y":..,"f":[..],"d":[..]},..]}}.
     */
    public void appendJson(StringBuilder json) {
        json.append("{\"w\":").append(width).append(",\"h\":").append(height).append(",\"layers\":[");
        for (int i = 0; i < layers.size(); i++) {
            if (i > 0) json.append(',');
            layers.get(i).appendJson(json);
        }
        json.append("]}");
    }

    public String toJson() {
        StringBuilder json = new StringBuilder(64 + 48 * layers.size());
        appendJson(json);
        return json.toString();
    }
}
