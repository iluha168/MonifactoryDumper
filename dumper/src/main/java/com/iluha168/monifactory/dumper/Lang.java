package com.iluha168.monifactory.dumper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * The artifact's English text, always {@code en_us}: the game's own language is whatever the instance's options.txt
 * selects, and a player's choice there must not change what the artifact says.
 * <ul>
 * <li>{@code lang.json}: every translation, key to text. It is built the way the game builds its language on a resource
 * reload, from every {@code en_us.json} in the client's resource packs (vanilla, each mod, KubeJS's assets), a later
 * pack's key over an earlier one's.</li>
 * <li>{@code matter_names.json}: the name of every registered item and fluid, as the game shows it. Mods name many of
 * them in code, not by a key of their own: GregTech fills a template such as "%s Dust" or "Liquid %s" with the
 * material's name, and picks the template by the material's properties. So these are read off the game, with the
 * English language put in place while they are.</li>
 * </ul>
 */
final class Lang {
    private Lang() {
    }

    static final String LANGUAGE = LanguageManager.DEFAULT_LANGUAGE_CODE;

    /** Writes {@code lang.json} and {@code matter_names.json} into {@code output}. */
    static void write(Minecraft minecraft, Path output) throws IOException {
        long start = System.nanoTime();
        ClientLanguage english = ClientLanguage.loadFrom(minecraft.getResourceManager(), List.of(LANGUAGE), false);
        // Forge's getLanguageData hands out the whole map.
        Map<String, String> translations = new TreeMap<>(english.getLanguageData());
        if (translations.isEmpty()) {
            throw new IllegalStateException("no resource pack has a " + LANGUAGE + " translation");
        }
        Map<String, String> items, fluids;
        Language previous = Language.getInstance();
        Language.inject(english);
        try {
            items = names(ForgeRegistries.ITEMS, item -> new ItemStack(item).getHoverName().getString());
            fluids = names(ForgeRegistries.FLUIDS,
                    fluid -> new FluidStack(fluid, FluidType.BUCKET_VOLUME).getDisplayName().getString());
        } finally {
            Language.inject(previous);
        }

        writeJson(output.resolve("lang.json"), out -> entries(out, translations));
        writeJson(output.resolve("matter_names.json"), out -> {
            out.write("\"item\":{\n");
            entries(out, items);
            out.write("},\n\"fluid\":{\n");
            entries(out, fluids);
            out.write("}\n");
        });
        LOG.info("[dumper] wrote {} {} translations and the names of {} items and {} fluids in {} ms",
                translations.size(), LANGUAGE, items.size(), fluids.size(), (System.nanoTime() - start) / 1_000_000L);
    }

    /**
     * Every entry of {@code registry}, id to name, sorted by id. The table is whole or there is none: a name that
     * throws fails the dump.
     */
    private static <T> Map<String, String> names(IForgeRegistry<T> registry, Function<T, String> name) {
        Map<String, String> names = new TreeMap<>();
        List<String> failed = new ArrayList<>();
        RuntimeException first = null;
        for (Map.Entry<ResourceKey<T>, T> entry : registry.getEntries()) {
            String id = entry.getKey().location().toString();
            try {
                names.put(id, name.apply(entry.getValue()));
            } catch (RuntimeException e) {
                failed.add(id);
                if (first == null) first = e;
            }
        }
        if (first != null) {
            throw new IllegalStateException(failed.size() + " of " + registry.getRegistryName() + " have no name; first"
                    + " few: " + failed.subList(0, Math.min(10, failed.size())), first);
        }
        return names;
    }

    interface Body {
        void write(Writer out) throws IOException;
    }

    /** A JSON object around {@code body}, written to the side and moved in whole. */
    static void writeJson(Path file, Body body) throws IOException {
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        try (BufferedWriter out = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
            out.write("{\n");
            body.write(out);
            out.write("}\n");
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * One {@code "key":"value"} a line, with no indentation, so the file streams and two packs' files diff line by line.
     * Values are the raw text: format specifiers ({@code %s}, {@code %1$s}) and section-sign colour codes stay in.
     */
    private static void entries(Writer out, Map<String, String> map) throws IOException {
        int left = map.size();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            out.write(RecipeJson.str(entry.getKey()));
            out.write(':');
            out.write(RecipeJson.str(entry.getValue()));
            out.write(--left > 0 ? ",\n" : "\n");
        }
    }
}
