package com.iluha168.monifactory.dumper;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * What the layered renderer needs from LDLib, the UI library GregTech draws its EMI recipes with, reached by
 * reflection: LDLib is a jar inside GregTech's jar and not on the compile class path.
 * <p>
 * Two things. LDLib's {@code EmiRenderHelperMixin} makes {@code EmiRenderHelper.renderRecipe} lay a
 * {@code ModularEmiRecipe} out with {@code addTempWidgets} instead of {@code addWidgets}: one
 * {@code ModularWrapperWidget} holding the whole LDLib UI, with slots and tanks drawn by LDLib. Plain
 * {@code addWidgets} draws a visibly different picture, so the layers must be cut from the temp widgets. And that one
 * EMI widget holds a whole tree of LDLib widgets, which can be drawn one node at a time for finer layers: each slot,
 * tank, arrow and text line of a GregTech recipe.
 * <p>
 * Every member is looked up once. If LDLib is missing or any member is, that is logged once and the layered renderer
 * degrades instead of failing: a {@code ModularEmiRecipe} still gets {@code addTempWidgets} if that much resolved,
 * and a wrapper widget is drawn as one layer.
 */
final class Ldlib {
    private Ldlib() {
    }

    private static final Class<?> MODULAR_RECIPE;
    private static final Method ADD_TEMP_WIDGETS;
    /** How to split a wrapper widget, or null when it can't be. */
    private static final Tree TREE;

    static {
        Class<?> modularRecipe = null;
        Method addTempWidgets = null;
        Tree tree = null;
        try {
            modularRecipe = Class.forName("com.lowdragmc.lowdraglib.emi.ModularEmiRecipe");
        } catch (ClassNotFoundException | LinkageError e) {
            LOG.info("[layers] LDLib is not loaded: no recipe is laid out or split through it");
        }
        if (modularRecipe != null) {
            try {
                addTempWidgets = modularRecipe.getMethod("addTempWidgets", WidgetHolder.class);
            } catch (NoSuchMethodException e) {
                LOG.warn("[layers] LDLib has no ModularEmiRecipe.addTempWidgets: its recipes are laid out with"
                        + " addWidgets, which renderRecipe does not draw, so they will fail the check", e);
            }
            try {
                tree = new Tree();
            } catch (ReflectiveOperationException | LinkageError e) {
                LOG.warn("[layers] LDLib's widget tree is not where it was: its wrapper widgets are one layer each", e);
            }
        }
        MODULAR_RECIPE = addTempWidgets == null ? null : modularRecipe;
        ADD_TEMP_WIDGETS = addTempWidgets;
        TREE = tree;
    }

    /**
     * Lays {@code recipe} out into {@code holder} the way LDLib's mixin makes {@code renderRecipe} do it, and returns
     * true; or returns false for a recipe that isn't LDLib's, which the caller lays out with {@code addWidgets}.
     * <p>
     * Each call replaces LDLib's static {@code ModularEmiRecipe.TEMP_CACHE} with the new UI and fires the previous
     * UI's close listeners, as every {@code renderRecipe} of such a recipe does. Nothing else holds the UI: it goes
     * with the widget list.
     */
    static boolean addTempWidgets(EmiRecipe recipe, WidgetHolder holder) {
        if (MODULAR_RECIPE == null || !MODULAR_RECIPE.isInstance(recipe)) return false;
        invoke(ADD_TEMP_WIDGETS, recipe, holder);
        return true;
    }

    /**
     * One node of a wrapper widget's LDLib tree. {@code path} is the child indices from the root
     * ({@code ModularUI.mainGroup}, the empty path) down to it; {@code type} is its class; {@code group} whether it
     * has children of its own.
     */
    record Node(int[] path, Class<?> type, boolean group) {
    }

    /**
     * The nodes of {@code widget}'s LDLib tree, depth first, parents before their children, groups included; or null
     * when {@code widget} is not a wrapper widget or LDLib's tree can't be reached.
     */
    static List<Node> nodes(Widget widget) {
        if (TREE == null || !TREE.wrapper.isInstance(widget)) return null;
        List<Node> out = new ArrayList<>();
        TREE.walk(TREE.root(widget), new int[0], out);
        return out;
    }

    /**
     * Makes the wrapper's UI as its first draw at the current tick would leave it, so its layout is final before any
     * layer is placed. {@code ModularWrapper.draw} refreshes the UI ({@code updateScreen()}, which may resize a label
     * to new text or re-align a widget) when {@code player.tickCount} moved since its last draw; this runs that
     * refresh now and records the tick, so the layer draws that follow at the same tick skip it, exactly as the draws
     * after the first in one {@code renderRecipe} would. Call it under {@link DrawTime}, right after the widgets were
     * built. Does nothing for another widget.
     */
    static void settle(Widget widget) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (TREE == null || !TREE.wrapper.isInstance(widget) || player == null) return;
        Object modular = TREE.modular(widget);
        try {
            if (TREE.lastTick.getInt(modular) == player.tickCount) return;
            invoke(TREE.updateScreen, modular);
            TREE.lastTick.setInt(modular, player.tickCount);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Where {@code node} says it is, in the wrapper widget's own coordinates (the ones EMI's {@code getBounds()} uses):
     * LDLib's position of the node, shifted by the offset the wrapper draws its UI at, and its size.
     */
    static Bounds area(Widget wrapper, Node node) {
        Object modular = TREE.modular(wrapper);
        Object target = TREE.at(TREE.root(wrapper), node.path());
        int left = (int) invoke(TREE.left, modular), top = (int) invoke(TREE.top, modular);
        return new Bounds((int) invoke(TREE.x, target) - left, (int) invoke(TREE.y, target) - top,
                (int) invoke(TREE.width, target), (int) invoke(TREE.height, target));
    }

    /**
     * Leaves only {@code target} to be drawn in the wrapper's tree: every node off the path to it hidden, its own
     * children hidden if it is a group, and the textures the ancestors draw themselves ({@code backgroundTexture},
     * {@code hoverTexture}, {@code overlay}) taken away. Draw the wrapper widget as usual, then run the returned undo,
     * which puts back exactly what this changed. Nodes that were hidden already stay hidden, so the layer draws
     * nothing where the whole UI would draw nothing.
     */
    @SuppressWarnings("unchecked")
    static Runnable isolate(Widget wrapper, Node target) {
        List<Runnable> undo = new ArrayList<>();
        Runnable restore = () -> {
            for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
        };
        boolean isolated = false;
        try {
            Object node = TREE.root(wrapper);
            int[] path = target.path();
            for (int depth = 0; ; depth++) {
                boolean isTarget = depth == path.length;
                if (!isTarget) {
                    for (Field texture : TREE.ownTextures) {
                        Object was = get(texture, node);
                        if (was == null) continue;
                        set(texture, node, null);
                        Object owner = node;
                        undo.add(() -> set(texture, owner, was));
                    }
                }
                if (!TREE.group.isInstance(node)) break;
                List<Object> children = (List<Object>) get(TREE.children, node);
                int keep = isTarget ? -1 : path[depth];
                for (int i = 0; i < children.size(); i++) {
                    Object child = children.get(i);
                    if (i == keep || !(boolean) invoke(TREE.isVisible, child)) continue;
                    invoke(TREE.setVisible, child, false);
                    undo.add(() -> invoke(TREE.setVisible, child, true));
                }
                if (isTarget) break;
                node = children.get(keep);
            }
            isolated = true;
            return restore;
        } finally {
            // Half an isolation left behind would hide nodes from every later draw of this UI.
            if (!isolated) restore.run();
        }
    }

    /** The class name a layer is known by in diagnostics: the simple name, or the binary one past the package. */
    static String shortName(Class<?> type) {
        String simple = type.getSimpleName();
        return simple.isEmpty() ? type.getName().substring(type.getName().lastIndexOf('.') + 1) : simple;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Field field, Object owner, Object value) {
        try {
            field.set(owner, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The members of LDLib that splitting a wrapper widget touches, all found or none. */
    private static final class Tree {
        final Class<?> wrapper, group;
        final Field modularOfWrapper, modularUI, mainGroup, lastTick, children;
        final Field[] ownTextures;
        final Method updateScreen, left, top, isVisible, setVisible, x, y, width, height;

        Tree() throws ReflectiveOperationException {
            wrapper = Class.forName("com.lowdragmc.lowdraglib.emi.ModularWrapperWidget");
            Class<?> modularWrapper = Class.forName("com.lowdragmc.lowdraglib.jei.ModularWrapper");
            Class<?> widget = Class.forName("com.lowdragmc.lowdraglib.gui.widget.Widget");
            group = Class.forName("com.lowdragmc.lowdraglib.gui.widget.WidgetGroup");
            modularOfWrapper = open(wrapper.getDeclaredField("modular"));
            modularUI = open(Class.forName("com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer")
                    .getDeclaredField("modularUI"));
            mainGroup = open(Class.forName("com.lowdragmc.lowdraglib.gui.modular.ModularUI")
                    .getDeclaredField("mainGroup"));
            lastTick = open(modularWrapper.getDeclaredField("lastTick"));
            children = open(group.getDeclaredField("widgets"));
            ownTextures = new Field[]{open(widget.getDeclaredField("backgroundTexture")),
                    open(widget.getDeclaredField("hoverTexture")), open(widget.getDeclaredField("overlay"))};
            updateScreen = modularWrapper.getMethod("updateScreen");
            left = modularWrapper.getMethod("getLeft");
            top = modularWrapper.getMethod("getTop");
            isVisible = widget.getMethod("isVisible");
            setVisible = widget.getMethod("setVisible", boolean.class);
            x = widget.getMethod("getPositionX");
            y = widget.getMethod("getPositionY");
            width = widget.getMethod("getSizeWidth");
            height = widget.getMethod("getSizeHeight");
        }

        private static Field open(Field field) {
            field.setAccessible(true);
            return field;
        }

        Object modular(Widget wrapperWidget) {
            return get(modularOfWrapper, wrapperWidget);
        }

        Object root(Widget wrapperWidget) {
            return get(mainGroup, get(modularUI, modular(wrapperWidget)));
        }

        @SuppressWarnings("unchecked")
        Object at(Object root, int[] path) {
            Object node = root;
            for (int index : path) node = ((List<Object>) get(children, node)).get(index);
            return node;
        }

        @SuppressWarnings("unchecked")
        void walk(Object node, int[] path, List<Node> out) {
            boolean isGroup = group.isInstance(node);
            out.add(new Node(path, node.getClass(), isGroup));
            if (!isGroup) return;
            List<Object> list = (List<Object>) get(children, node);
            for (int i = 0; i < list.size(); i++) {
                int[] child = Arrays.copyOf(path, path.length + 1);
                child[path.length] = i;
                walk(list.get(i), child, out);
            }
        }
    }
}
