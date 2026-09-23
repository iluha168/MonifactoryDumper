package com.iluha168.monifactory.dumper;

import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.emi.emi.runtime.EmiDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One recipe at one moment, cut into the layers {@code EmiRenderHelper.renderRecipe} draws, in its draw order: the
 * recipe card first, then one layer per EMI widget, with LDLib's wrapper widget split into one layer per node of its
 * UI tree (see {@link Ldlib}). Composited in order, the layers are the recipe.
 * <p>
 * A plan holds the widget list of one build, and a widget list belongs to one moment: LDLib widgets keep content they
 * refreshed at their build's tick. So the caller builds a plan per frame, and a probe draw at another time gets a
 * plan of its own. The layers are assumed to come out the same, in number and order, from every build of one recipe;
 * nothing here enforces it, and {@link #sameLayers} is how the caller checks. Crop boxes come from each build's own
 * layout and may differ between builds.
 * <p>
 * Building a plan of a GregTech recipe fires the close listeners of the LDLib UI built before it
 * ({@link Ldlib#addTempWidgets}), as {@code renderRecipe} does to its own previous draw. The prototype drew frame
 * plans after a probe plan had closed them and still matched the real render. The plan keeps nothing static:
 * dropping it drops its widgets.
 */
final class LayerPlan {
    /** How far in from the card's corner EMI draws the widgets, in GUI pixels: half of the card's padding. */
    static final int INSET = RecipeRenderer.PADDING / 2;
    /**
     * How far a crop box reaches past the area a layer declares, in GUI pixels on every side. Widgets draw a little
     * outside their bounds (a slot's frame, a text's shadow); what reaches further is caught by the guard ring.
     */
    static final int MARGIN = 2;

    private final Minecraft minecraft;
    private final EmiRecipe recipe;
    private final long millis;
    private final int scale, guiWidth, guiHeight;
    private final Box canvas;
    private final List<Layer> layers = new ArrayList<>();

    private LayerPlan(Minecraft minecraft, EmiRecipe recipe, long millis, List<Widget> widgets) {
        this.minecraft = minecraft;
        this.recipe = recipe;
        this.millis = millis;
        this.scale = RecipeRenderer.scale(minecraft);
        this.guiWidth = recipe.getDisplayWidth() + RecipeRenderer.PADDING;
        this.guiHeight = recipe.getDisplayHeight() + RecipeRenderer.PADDING;
        this.canvas = new Box(0, 0, guiWidth * scale, guiHeight * scale);
        layers.add(new Layer(null, null, canvas, "card"));
        for (Widget widget : widgets) {
            List<Ldlib.Node> nodes = Ldlib.nodes(widget);
            if (nodes == null) {
                layers.add(new Layer(widget, null, crop(widget.getBounds()), Ldlib.shortName(widget.getClass())));
                continue;
            }
            for (Ldlib.Node node : nodes) {
                layers.add(new Layer(widget, node, crop(Ldlib.area(widget, node)),
                        "ldlib:" + Ldlib.shortName(node.type())));
            }
        }
    }

    /**
     * Lays {@code recipe} out at {@code millis}, the way {@code renderRecipe} does for a draw at that time, LDLib's
     * mixin included, and plans its layers. Render thread; not inside a {@link DrawTime} span, since it opens its own.
     */
    static LayerPlan build(Minecraft minecraft, EmiRecipe recipe, long millis) {
        List<Widget> widgets = DrawTime.get(minecraft, millis, () -> {
            List<Widget> built = new ArrayList<>();
            WidgetHolder holder = new WidgetHolder() {
                @Override
                public int getWidth() {
                    return recipe.getDisplayWidth();
                }

                @Override
                public int getHeight() {
                    return recipe.getDisplayHeight();
                }

                @Override
                public <T extends Widget> T add(T widget) {
                    built.add(widget);
                    return widget;
                }
            };
            if (!Ldlib.addTempWidgets(recipe, holder)) recipe.addWidgets(holder);
            // Before any crop box is read: the first draw would move and resize LDLib nodes otherwise.
            for (Widget widget : built) Ldlib.settle(widget);
            return built;
        });
        return new LayerPlan(minecraft, recipe, millis, widgets);
    }

    /** The time the widgets were built at. Draw the layers under {@link DrawTime} at this time, flush included. */
    long millis() {
        return millis;
    }

    /** Canvas width in GUI pixels: the recipe's display width and the card's padding. */
    int guiWidth() {
        return guiWidth;
    }

    /** Canvas height in GUI pixels. */
    int guiHeight() {
        return guiHeight;
    }

    /** Pixels per GUI pixel, {@link RecipeRenderer#scale}. */
    int scale() {
        return scale;
    }

    /** Canvas width in pixels, as {@link RecipeRenderer} draws the whole recipe. */
    int width() {
        return canvas.width();
    }

    /** Canvas height in pixels. */
    int height() {
        return canvas.height();
    }

    /** The layers in draw order; the first is the card. */
    List<Layer> layers() {
        return Collections.unmodifiableList(layers);
    }

    /**
     * Whether {@code other}, another build of the same recipe, has the same layers in the same order: the same kinds,
     * and for LDLib nodes the same place in the tree.
     */
    boolean sameLayers(LayerPlan other) {
        if (other.layers.size() != layers.size()) return false;
        for (int i = 0; i < layers.size(); i++) {
            Layer a = layers.get(i), b = other.layers.get(i);
            if (!a.kind.equals(b.kind) || (a.node == null) != (b.node == null)) return false;
            if (a.node != null && !Arrays.equals(a.node.path(), b.node.path())) return false;
        }
        return true;
    }

    /** A rectangle of the canvas in pixels, from the top left corner, y down. */
    record Box(int x, int y, int width, int height) {
    }

    /**
     * {@code declared}, an area in the widgets' coordinates, as a crop box: moved by the card's inset, grown by
     * {@link #MARGIN}, scaled and clamped to the canvas. A widget that declares no area might draw anywhere, and one
     * that declares an area off the canvas can only show by drawing outside it, so both get the whole canvas.
     */
    private Box crop(Bounds declared) {
        if (declared == null || declared.empty()) return canvas;
        int left = Math.max(0, (declared.left() + INSET - MARGIN) * scale);
        int top = Math.max(0, (declared.top() + INSET - MARGIN) * scale);
        int right = Math.min(canvas.width(), (declared.right() + INSET + MARGIN) * scale);
        int bottom = Math.min(canvas.height(), (declared.bottom() + INSET + MARGIN) * scale);
        if (right <= left || bottom <= top) return canvas;
        return new Box(left, top, right - left, bottom - top);
    }

    /** One layer: the card, an EMI widget, or one node of an LDLib wrapper widget. */
    final class Layer {
        /** Null for the card. */
        private final Widget widget;
        /** Null unless the layer is one node of {@link #widget}'s LDLib tree. */
        private final Ldlib.Node node;
        private final Box box;
        private final String kind;

        private Layer(Widget widget, Ldlib.Node node, Box box, String kind) {
            this.widget = widget;
            this.node = node;
            this.box = box;
            this.kind = kind;
        }

        /**
         * Draws this layer and nothing else, with the recipe's transform already set by the caller: GUI pixel (0, 0)
         * is the card's corner. The card is drawn as {@code renderRecipe} draws it first; a widget moved in by the
         * card's inset and given the mouse far away and the frame's partial tick, as {@code renderRecipe} gives it.
         * An LDLib node is drawn by drawing its wrapper widget with the rest of the tree hidden for the call.
         */
        void draw(GuiGraphics graphics) {
            if (widget == null) {
                EmiRenderHelper.renderRecipeBackground(recipe, EmiDrawContext.wrap(graphics), 0, 0);
                return;
            }
            Runnable undo = node == null ? null : Ldlib.isolate(widget, node);
            try {
                graphics.pose().pushPose();
                graphics.pose().translate(INSET, INSET, 0);
                widget.render(graphics, -1000, -1000, minecraft.getFrameTime());
                graphics.pose().popPose();
            } finally {
                if (undo != null) undo.run();
            }
        }

        /** Where the layer may draw, in canvas pixels. The card's is the whole canvas. */
        Box box() {
            return box;
        }

        /** What the layer is, for diagnostics: {@code card}, an EMI widget's class, or {@code ldlib:} a node's class. */
        String kind() {
            return kind;
        }
    }
}
