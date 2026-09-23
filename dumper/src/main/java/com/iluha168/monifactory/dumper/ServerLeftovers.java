package com.iluha168.monifactory.dumper;

import net.minecraft.server.ReloadableServerResources;
import net.minecraft.world.item.crafting.RecipeManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * Lets go of what the server datapack reload leaves behind, once EMI has read the synced recipes and nothing will read
 * the server's side again. The renderer drops its own {@link DataPlane.Server}; mods keep theirs in statics. Measured
 * on Monifactory 0.13.8 (ignored/perf/REPORT.md), the live heap after EMI's reload goes from about 4.1 to 3.2 GB:
 * <ul>
 *   <li>the server's recipe manager (FastSuite's, 712 MB retained), which KubeJS and Thermal both hold, so it only
 *       goes once both let go;</li>
 *   <li>4.9 million cached regex matches in KubeJS's recipe filters (195 MB);</li>
 *   <li>GregTech's generated server datapack (27 MB), and the loot tables and condition context emi_loot and
 *       CodeChickenLib keep.</li>
 * </ul>
 * The smaller heap is faster, not just smaller: with 4.2 GB live in a 5 GB heap, G1 marks nearly all the time (1.3 to
 * 1.8 cores during the render) and the batch took 15 to 19% longer.
 * <p>
 * Every step is by reflection and on its own. A mod that is not there, or renamed what a step reaches for, costs that
 * step only: it is skipped with a line in the log, and the render goes on as it would have without it. A static is
 * only cleared when it holds this reload's own object (checked by identity), except the regex caches, which are a pure
 * memo of {@code pattern.matcher(id).find()}.
 */
final class ServerLeftovers {
    /** The line a sharded build waits for before it starts the next game: the boot's memory peak is behind this one. */
    static final String MARKER = "[dumper] released the server side";

    private ServerLeftovers() {
    }

    private interface Step {
        /** What the step let go of, or why it kept it, for the log. */
        String run() throws ReflectiveOperationException;
    }

    /**
     * Releases what it can of {@code server}'s leftovers. The caller then drops its own reference and calls
     * {@link #collect}: a collection while {@code server} is still held keeps the recipe manager alive.
     */
    static void release(DataPlane.Server server) {
        ReloadableServerResources resources = server.resources();
        RecipeManager recipes = resources.getRecipeManager();
        run("KubeJS's reload statics", () -> {
            Class<?> listener = modClass("dev.latvian.mods.kubejs.server.KubeJSReloadListener");
            Field held = field(listener, "resources");
            if (held.get(null) != resources) return "kept, KubeJSReloadListener.resources is not this reload's";
            held.set(null, null);
            field(listener, "recipeContext").set(null, null);
            field(modClass("dev.latvian.mods.kubejs.recipe.RecipesEventJS"), "instance").set(null, null);
            return "released KubeJSReloadListener.resources, .recipeContext and RecipesEventJS.instance";
        });
        run("Thermal's server recipe manager", () -> {
            Class<?> managers = modClass("cofh.thermal.lib.util.ThermalRecipeManagers");
            Object instance = managers.getMethod("instance").invoke(null);
            Field held = field(managers, "serverRecipeManager");
            if (held.get(instance) != recipes) {
                return "kept, ThermalRecipeManagers.serverRecipeManager is not this reload's";
            }
            // Only refreshServer() reads it, and that runs on a server's reload.
            managers.getMethod("setServerRecipeManager", RecipeManager.class).invoke(instance, (Object) null);
            return "released ThermalRecipeManagers.serverRecipeManager";
        });
        run("emi_loot's loot tables", () -> clearIfSame(
                modClass("fzzyhmstrs.emi_loot.parser.LootTableParser"), "lootManager", resources.getLootData()));
        run("CodeChickenLib's condition context", () -> clearIfSame(
                modClass("codechicken.lib.datagen.ConditionalIngredient$Serializer"), "conditionContext",
                resources.getConditionContext()));
        run("KubeJS's regex filter caches", ServerLeftovers::clearRegexCaches);
        run("GregTech's generated server datapack", () -> {
            modClass("com.gregtechceu.gtceu.data.pack.GTDynamicDataPack").getMethod("clearServer").invoke(null);
            return "called GTDynamicDataPack.clearServer()";
        });
    }

    /** One full collection, and {@link #MARKER} with the heap it left. */
    static void collect() {
        Runtime runtime = Runtime.getRuntime();
        long before = runtime.totalMemory() - runtime.freeMemory();
        long start = System.nanoTime();
        System.gc();
        long after = runtime.totalMemory() - runtime.freeMemory();
        LOG.info(MARKER + ": heap {} MiB used after GC ({} MiB before, {} MiB committed, {} ms)", after >> 20,
                before >> 20, runtime.totalMemory() >> 20, (System.nanoTime() - start) / 1_000_000);
    }

    private static void run(String what, Step step) {
        try {
            LOG.info("[dumper] server side, {}: {}", what, step.run());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.info("[dumper] server side, {}: skipped, {}", what, e.toString());
        }
    }

    /** Nulls the static {@code name} of {@code owner} if it holds {@code server}, this reload's own object. */
    private static String clearIfSame(Class<?> owner, String name, Object server) throws ReflectiveOperationException {
        Field held = field(owner, name);
        if (held.get(null) != server) return "kept, " + owner.getSimpleName() + "." + name + " is not this reload's";
        held.set(null, null);
        return "released " + owner.getSimpleName() + "." + name;
    }

    /**
     * Every {@code RegexIDFilter} is interned in its static {@code INTERNER}, and each remembers the answer for every
     * recipe id it was ever asked about. Only a server's recipe event asks.
     */
    private static String clearRegexCaches() throws ReflectiveOperationException {
        Class<?> filter = modClass("dev.latvian.mods.kubejs.recipe.filter.RegexIDFilter");
        Object interner = field(filter, "INTERNER").get(null);
        // Guava's interner is a map of the interned objects to a placeholder, in a field of its own.
        Map<?, ?> interned = null;
        for (Class<?> c = interner.getClass(); c != null && interned == null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType()) && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    interned = (Map<?, ?>) f.get(interner);
                    break;
                }
            }
        }
        if (interned == null) throw new NoSuchFieldException("no map in " + interner.getClass().getName());
        Field cache = field(filter, "matchCache");
        long matches = 0;
        int filters = 0;
        for (Object each : interned.keySet()) {
            Map<?, ?> memo = (Map<?, ?>) cache.get(each);
            matches += memo.size();
            memo.clear();
            filters++;
        }
        return "cleared " + matches + " cached matches in " + filters + " filters";
    }

    /** A class of some mod in the pack. A pack without the mod fails the step, and only the step. */
    private static Class<?> modClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ServerLeftovers.class.getClassLoader());
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
