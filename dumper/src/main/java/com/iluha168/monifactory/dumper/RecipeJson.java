package com.iluha168.monifactory.dumper;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ListEmiIngredient;
import dev.emi.emi.api.stack.TagEmiIngredient;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluid;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * One recipe as one line of {@code recipes.json}: everything EMI's API says about it, and nothing it does not.
 * <p>
 * Fields: {@code emiRecipeId} and {@code underlyingRecipeId} (the backing datapack recipe), each null where the recipe
 * has none; {@code cat}; {@code cls}, the EMI display class; {@code w}/{@code h}, the display size; the stack lists
 * {@code in}, {@code cats} and {@code out}; and {@code image}, the recipe's picture as layers of stills (DESIGN 2.1,
 * {@code LayeredImage}), null in a data-only artifact and for a recipe that failed to render.
 * <p>
 * A stack is one of three kinds. {@code k=s} is a concrete stack: {@code t} item or fluid, {@code id}, amount
 * {@code n}, an {@code nbt} flag and, when set, the top-level keys {@code nbtk}, a hash of the tag's text {@code nbth}
 * and any enchantments {@code ench}; then chance {@code c} when not 1 and remainder {@code rem} when there is one.
 * {@code k=t} is a tag: {@code tag}, its registry {@code reg}, amount, chance and how many things it {@code matches}.
 * {@code k=m} is anything else with several options: its class, amount, chance, {@code count} and every member's
 * {@code ids}.
 * <p>
 * Some recipes answer {@code getOutputs()} with nothing although their card shows a result, so the result is read off
 * the card: the slots the recipe marks as its own with {@link SlotWidget#recipeContext}, less any stack it already
 * lists as an input or catalyst, or as an option of one: EMI's fuel card marks the fuel slot itself that way, and its
 * tag card marks every member of the tag it takes as input. Such a record carries
 * {@code "outFrom":"slots"}. FramedBlocks' framing saw is the case that forced this: it hides the result from EMI's
 * lookups on purpose, so 29,584 recipes serialised with no output at all.
 */
final class RecipeJson {
    private RecipeJson() {
    }

    /** A record, and whether any part of it threw. Nothing that throws stops the others from being written. */
    record Line(String json, String error, boolean outputsFromSlots, boolean outputsEmpty) {
    }

    /**
     * Writes {@code recipes.json}: a JSON array with one record per line, in the corpus's order. A reader can parse
     * it whole, or stream it line by line and drop the trailing comma. Fails after writing if any record had an
     * error, since a record with a hole in it is not the recipe. {@code images} holds each record's {@code "image"}
     * object as JSON text, or null for none.
     */
    static void writeFile(List<Corpus.Entry> entries, String[] images, Path file) throws IOException {
        long start = System.nanoTime();
        int errors = 0, fromSlots = 0, empty = 0;
        Map<String, int[]> emptyByCategory = new TreeMap<>();
        Map<String, int[]> slotsByCategory = new TreeMap<>();
        String firstError = null;
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        try (BufferedWriter out = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
            out.write("[\n");
            for (int i = 0; i < entries.size(); i++) {
                Corpus.Entry entry = entries.get(i);
                Line line = write(entry.recipe(), entry.category(), images[i]);
                out.write(line.json());
                out.write(i + 1 < entries.size() ? ",\n" : "\n");
                if (line.error() != null) {
                    errors++;
                    if (firstError == null) firstError = entry.recipe().getId() + ": " + line.error();
                }
                if (line.outputsFromSlots()) {
                    fromSlots++;
                    slotsByCategory.computeIfAbsent(entry.category(), k -> new int[1])[0]++;
                }
                if (line.outputsEmpty()) {
                    empty++;
                    emptyByCategory.computeIfAbsent(entry.category(), k -> new int[1])[0]++;
                }
            }
            out.write("]\n");
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        // Zero errors says nothing threw, not that anything was found, so the log says where outputs are still empty
        // and where they had to come off the card.
        LOG.info("[dumper] outputs read off the card for {} recipes: {}", fromSlots, counts(slotsByCategory));
        LOG.info("[dumper] no outputs at all for {} recipes: {}", empty, counts(emptyByCategory));
        LOG.info("[dumper] wrote {} recipes to {} in {} ms, {} with errors", entries.size(), file,
                (System.nanoTime() - start) / 1_000_000L, errors);
        if (errors > 0) {
            throw new IllegalStateException(errors + " recipes did not serialise whole, first " + firstError);
        }
    }

    private static String counts(Map<String, int[]> byCategory) {
        StringBuilder out = new StringBuilder("{");
        byCategory.forEach((category, n) -> out.append(out.length() > 1 ? ", " : "").append(category).append('=')
                .append(n[0]));
        return out.append('}').toString();
    }

    static Line write(EmiRecipe recipe, String category) {
        return write(recipe, category, null);
    }

    static Line write(EmiRecipe recipe, String category, String image) {
        StringBuilder json = new StringBuilder(512);
        StringBuilder error = new StringBuilder();

        String emiId = null, underlyingId = null;
        try {
            emiId = recipe.getId() == null ? null : recipe.getId().toString();
        } catch (Throwable t) {
            error.append("id:").append(t).append(';');
        }
        try {
            Recipe<?> backing = recipe.getBackingRecipe();
            underlyingId = backing == null || backing.getId() == null ? null : backing.getId().toString();
        } catch (Throwable t) {
            error.append("underlyingRecipeId:").append(t).append(';');
        }
        json.append("{\"emiRecipeId\":").append(str(emiId))
                .append(",\"underlyingRecipeId\":").append(str(underlyingId))
                .append(",\"cat\":").append(str(category))
                .append(",\"cls\":").append(str(recipe.getClass().getName()));
        int width = -1, height = -1;
        try {
            width = recipe.getDisplayWidth();
            height = recipe.getDisplayHeight();
        } catch (Throwable t) {
            error.append("size:").append(t).append(';');
        }
        json.append(",\"w\":").append(width).append(",\"h\":").append(height);

        List<EmiIngredient> inputs = null, catalysts = null;
        try {
            inputs = recipe.getInputs();
            ingredients(json, ",\"in\":", inputs);
        } catch (Throwable t) {
            json.append(",\"in\":null");
            error.append("inputs:").append(t).append(';');
        }
        try {
            catalysts = recipe.getCatalysts();
            ingredients(json, ",\"cats\":", catalysts);
        } catch (Throwable t) {
            json.append(",\"cats\":null");
            error.append("catalysts:").append(t).append(';');
        }

        boolean fromSlots = false, empty = false;
        try {
            List<? extends EmiIngredient> outputs = recipe.getOutputs();
            if (outputs.isEmpty() && width > 0 && height > 0) {
                outputs = resultSlots(recipe, width, height, inputs, catalysts);
                fromSlots = !outputs.isEmpty();
            }
            empty = outputs.isEmpty();
            ingredients(json, ",\"out\":", outputs);
        } catch (Throwable t) {
            json.append(",\"out\":null");
            error.append("outputs:").append(t).append(';');
        }
        if (fromSlots) json.append(",\"outFrom\":\"slots\"");

        json.append(",\"image\":").append(image == null ? "null" : image);
        if (!error.isEmpty()) json.append(",\"err\":").append(str(error.toString()));
        json.append('}');
        return new Line(json.toString(), error.isEmpty() ? null : error.toString(), fromSlots, empty);
    }

    /**
     * Lays out the recipe's card the way EMI does before drawing it, and returns what sits in its result slots. Only
     * called for recipes whose {@code getOutputs()} is empty.
     */
    private static List<EmiIngredient> resultSlots(EmiRecipe recipe, int width, int height,
                                                   List<EmiIngredient> inputs, List<EmiIngredient> catalysts) {
        List<Widget> widgets = new ArrayList<>();
        recipe.addWidgets(new WidgetHolder() {
            @Override
            public int getWidth() {
                return width;
            }

            @Override
            public int getHeight() {
                return height;
            }

            @Override
            public <T extends Widget> T add(T widget) {
                widgets.add(widget);
                return widget;
            }
        });
        List<EmiIngredient> results = new ArrayList<>();
        for (Widget widget : widgets) {
            if (!(widget instanceof SlotWidget slot) || slot.getRecipe() == null) continue;
            EmiIngredient stack = slot.getStack();
            if (stack == null || stack.isEmpty() || listed(stack, inputs) || listed(stack, catalysts)) continue;
            results.add(stack);
        }
        return results;
    }

    /** Whether {@code stack} is one of {@code list}, or every option it offers is an option of one of them. */
    private static boolean listed(EmiIngredient stack, List<EmiIngredient> list) {
        if (list == null) return false;
        for (EmiIngredient other : list) {
            if (other == stack || other.equals(stack)) return true;
            List<EmiStack> options = other.getEmiStacks();
            if (stack.getEmiStacks().stream().allMatch(mine -> options.stream().anyMatch(mine::isEqual))) return true;
        }
        return false;
    }

    private static void ingredients(StringBuilder json, String key, List<? extends EmiIngredient> list) {
        json.append(key).append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(',');
            ingredient(json, list.get(i));
        }
        json.append(']');
    }

    private static void ingredient(StringBuilder json, EmiIngredient ingredient) {
        if (ingredient == null) {
            json.append("null");
            return;
        }
        if (ingredient instanceof TagEmiIngredient tag) {
            json.append("{\"k\":\"t\",\"tag\":").append(str(tag.key.location().toString()))
                    .append(",\"reg\":").append(str(tag.key.registry().location().toString()))
                    .append(",\"n\":").append(ingredient.getAmount());
            chance(json, ingredient.getChance());
            json.append(",\"matches\":").append(ingredient.getEmiStacks().size()).append('}');
            return;
        }
        if (ingredient instanceof EmiStack stack) {
            stack(json, stack);
            return;
        }
        // ListEmiIngredient, or a mod's own EmiIngredient: several options.
        List<EmiStack> stacks = ingredient.getEmiStacks();
        json.append("{\"k\":\"m\",\"cls\":").append(str(ingredient.getClass().getName()))
                .append(",\"n\":").append(ingredient.getAmount());
        chance(json, ingredient.getChance());
        json.append(",\"count\":").append(stacks.size()).append(",\"ids\":[");
        for (int i = 0; i < stacks.size(); i++) {
            if (i > 0) json.append(',');
            json.append(str(String.valueOf(stacks.get(i).getId())));
        }
        json.append(']');
        if (ingredient instanceof ListEmiIngredient list) {
            json.append(",\"parts\":").append(list.getIngredients().size());
        }
        json.append('}');
    }

    private static void stack(StringBuilder json, EmiStack stack) {
        json.append("{\"k\":\"s\",\"t\":").append(str(typeOf(stack)))
                .append(",\"id\":").append(str(String.valueOf(stack.getId())))
                .append(",\"n\":").append(stack.getAmount());
        chance(json, stack.getChance());
        CompoundTag nbt = stack.getNbt();
        if (nbt == null || nbt.isEmpty()) {
            json.append(",\"nbt\":0");
        } else {
            json.append(",\"nbt\":1,\"nbtk\":[");
            boolean first = true;
            for (String key : nbt.getAllKeys()) {
                if (!first) json.append(',');
                first = false;
                json.append(str(key));
            }
            // The hash the reference dump used, so the two compare.
            json.append("],\"nbth\":").append(nbt.toString().hashCode());
            enchantments(json, nbt);
        }
        EmiStack remainder = stack.getRemainder();
        if (remainder != null && !remainder.isEmpty()) {
            json.append(",\"rem\":").append(str(String.valueOf(remainder.getId())));
        }
        json.append('}');
    }

    /** {@code "ench":{"Enchantments":[["minecraft:sharpness",5]],"StoredEnchantments":[...]}}, if there are any. */
    private static void enchantments(StringBuilder json, CompoundTag nbt) {
        boolean any = false;
        for (String key : new String[]{"Enchantments", "StoredEnchantments"}) {
            if (!nbt.contains(key, Tag.TAG_LIST)) continue;
            ListTag list = nbt.getList(key, Tag.TAG_COMPOUND);
            if (list.isEmpty()) continue;
            json.append(any ? "," : ",\"ench\":{");
            any = true;
            json.append(str(key)).append(":[");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag enchantment = list.getCompound(i);
                if (i > 0) json.append(',');
                json.append('[').append(str(enchantment.getString("id"))).append(',')
                        .append(enchantment.getInt("lvl")).append(']');
            }
            json.append(']');
        }
        if (any) json.append('}');
    }

    private static void chance(StringBuilder json, float chance) {
        if (chance != 1.0f) json.append(",\"c\":").append(chance);
    }

    private static String typeOf(EmiStack stack) {
        Object key = stack.getKey();
        if (key instanceof Item) return "item";
        if (key instanceof Fluid) return "fluid";
        if (stack.isEmpty()) return "empty";
        return key == null ? "null" : key.getClass().getName();
    }

    static String str(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 8);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
