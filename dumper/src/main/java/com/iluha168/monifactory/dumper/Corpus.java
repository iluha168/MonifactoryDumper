package com.iluha168.monifactory.dumper;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * Every recipe EMI aggregated, each one once, minus what EMI synthesises in the two excluded categories. This is the
 * list the build writes and renders, in EMI's category order.
 * <p>
 * Recipes are told apart by identity and never by content. TooManyRecipeViewers can list a category twice with the same
 * recipe objects in both, which would render those recipes twice; and two recipes with equal content are two recipes,
 * which a player sees twice as well (the Forge Hammer lists {@code nether_star_block} twice).
 */
final class Corpus {
    /**
     * EMI fills these two itself from item properties, with no datapack recipe behind any of it. Its anvil entries
     * are the cross product of every enchantable tool with every enchantment, and its grindstone entries the same tools
     * disenchanted and combined. Every frame of either differs from the last (the glint scrolls, and grindstone
     * disenchanting picks a random enchantment per frame), so they are also the most expensive content per recipe.
     * <p>
     * Only what EMI made is dropped. A mod can register into these categories too - Quark's JEI plugin, through
     * TooManyRecipeViewers, puts four real repair recipes in the anvil one (pickarang with a diamond, flamerang with
     * netherite, and each combined with itself) - and a player sees those, so they stay. See {@link #synthesised} and
     * {@link #checkAnvil}.
     */
    static final String ANVIL = "emi:anvil_repairing";
    static final String GRINDING = "emi:grinding";
    static final Set<String> EXCLUDED = Set.of(ANVIL, GRINDING);

    record Entry(EmiRecipe recipe, String category) {
    }

    /** What EMI lists, in its order, and what is kept of it. */
    final List<EmiRecipeCategory> categories;
    /** Recipes per category id, the first listing of each id. */
    final Map<String, Integer> counts;
    final List<Entry> kept;
    /** How many recipes the exclusion dropped, per excluded category id. */
    final Map<String, Integer> dropped;
    final int listed;
    final int distinct;
    final int excluded;

    private Corpus(List<EmiRecipeCategory> categories, Map<String, Integer> counts, List<Entry> kept,
                   Map<String, Integer> dropped, int listed, int distinct, int excluded) {
        this.categories = categories;
        this.counts = counts;
        this.kept = kept;
        this.dropped = dropped;
        this.listed = listed;
        this.distinct = distinct;
        this.excluded = excluded;
    }

    /**
     * Walks EMI's categories. Fails if the category walk and EMI's own flat list disagree about which recipes exist,
     * if a recipe sits in two categories, or if the anvil exclusion would drop a recipe that is not an enchantment
     * cross-product entry.
     */
    static Corpus of(EmiRecipeManager manager) {
        List<EmiRecipeCategory> categories = List.copyOf(manager.getCategories());
        Map<EmiRecipe, String> distinct = new IdentityHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Entry> kept = new ArrayList<>();
        Map<String, Integer> dropped = new LinkedHashMap<>();
        int listed = 0, excluded = 0, repeatedIds = 0;
        for (EmiRecipeCategory category : categories) {
            String id = category.getId().toString();
            List<EmiRecipe> recipes = manager.getRecipes(category);
            listed += recipes.size();
            if (counts.putIfAbsent(id, recipes.size()) != null) repeatedIds++;
            for (EmiRecipe recipe : recipes) {
                String first = distinct.putIfAbsent(recipe, id);
                if (first != null) {
                    // A category listed twice holds the same objects twice. Anything else is one recipe claimed by two
                    // categories, which the sum check below turns into a failure.
                    continue;
                }
                if (EXCLUDED.contains(id) && synthesised(recipe)) {
                    excluded++;
                    dropped.merge(id, 1, Integer::sum);
                } else {
                    kept.add(new Entry(recipe, id));
                }
            }
        }

        // The dedupe check: a recipe is one object in one category, so the identity-distinct recipes are exactly the
        // recipes of the distinct category ids.
        long perUniqueId = counts.values().stream().mapToLong(Integer::intValue).sum();
        if (perUniqueId != distinct.size()) {
            throw new IllegalStateException("EMI lists " + distinct.size() + " distinct recipes, but its " + counts.size()
                    + " distinct categories hold " + perUniqueId + ": some recipe is in two categories, or a category"
                    + " id is listed twice with different recipes");
        }
        Set<EmiRecipe> flat = Collections.newSetFromMap(new IdentityHashMap<>());
        flat.addAll(manager.getRecipes());
        if (!flat.equals(distinct.keySet())) {
            throw new IllegalStateException("EMI's flat recipe list has " + flat.size() + " distinct recipes and its"
                    + " categories " + distinct.size() + "; they are not the same recipes");
        }

        int anvil = checkAnvil(manager, categories);
        LOG.info("[dumper] EMI lists {} categories ({} distinct ids, {} listed twice), {} recipes, {} distinct;"
                        + " excluded {} synthesised ones {}, anvil assertion keeps {}; {} kept",
                categories.size(), counts.size(), repeatedIds, listed, distinct.size(), excluded, dropped, anvil,
                kept.size());
        return new Corpus(categories, counts, List.copyOf(kept), Map.copyOf(dropped), listed, distinct.size(),
                excluded);
    }

    /** A display EMI generated itself, as opposed to one a mod or a recipe viewer bridge registered. */
    static boolean synthesised(EmiRecipe recipe) {
        return recipe.getClass().getName().startsWith("dev.emi.emi.");
    }

    /**
     * The anvil exclusion's assertion, over every anvil recipe the exclusion drops: it must have
     * one output, two inputs, a first input that is the output, and an enchanted book second. Every EMI entry today
     * is (tool, book, the same tool enchanted). If one ever is not, EMI has started putting something in the category
     * the exclusion was not reviewed for, and the build stops.
     */
    private static int checkAnvil(EmiRecipeManager manager, List<EmiRecipeCategory> categories) {
        List<String> wouldKeep = new ArrayList<>();
        int seen = 0;
        for (EmiRecipeCategory category : categories) {
            if (!category.getId().toString().equals(ANVIL)) continue;
            for (EmiRecipe recipe : manager.getRecipes(category)) {
                if (!synthesised(recipe)) continue;
                seen++;
                List<EmiIngredient> in = recipe.getInputs();
                List<EmiStack> out = recipe.getOutputs();
                boolean crossProduct = out.size() == 1 && in.size() == 2
                        && in.get(0) instanceof EmiStack tool && sameStack(tool, out.get(0))
                        && in.get(1) instanceof EmiStack book
                        && "minecraft:enchanted_book".equals(String.valueOf(book.getId()));
                if (!crossProduct) {
                    wouldKeep.add(RecipeJson.write(recipe, ANVIL).json());
                }
            }
        }
        if (!wouldKeep.isEmpty()) {
            throw new IllegalStateException(wouldKeep.size() + " of " + seen + " " + ANVIL + " recipes EMI made are not an"
                    + " enchantment applied to a tool, and the exclusion would drop them; re-review the"
                    + " exclusion. First few:\n" + String.join("\n", wouldKeep.subList(0, Math.min(10, wouldKeep.size()))));
        }
        return wouldKeep.size();
    }

    /** Same thing, same amount, same NBT. */
    private static boolean sameStack(EmiStack a, EmiStack b) {
        return Objects.equals(a.getId(), b.getId()) && a.getAmount() == b.getAmount()
                && Objects.equals(a.getNbt(), b.getNbt());
    }

    /**
     * {@code categories.tsv}: every category EMI lists, in its order, with its recipe count, empty ones included, and
     * how many of those the exclusion dropped.
     */
    void writeCategories(Path file) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("category\trecipes\tdropped\n");
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                out.write(entry.getKey() + "\t" + entry.getValue() + "\t" + dropped.getOrDefault(entry.getKey(), 0)
                        + "\n");
            }
        }
    }
}
