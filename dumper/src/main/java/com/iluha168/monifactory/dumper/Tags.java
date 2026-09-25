package com.iluha168.monifactory.dumper;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * {@code tags.json}: every tag of every registry, with its entries. Taken from the server side once its datapack reload
 * has bound the tags, since the client only ever gets the tags of registries that go over the network: worldgen's, such
 * as biomes and structures, stay on the server. The server's registries are all of them: the built-in ones, every
 * mod's, and the worldgen and dimension registries the datapacks fill.
 * <p>
 * Captured as strings right away, since the server side is let go of long before the artifact is written.
 */
final class Tags {
    /** Registry id to tag id to entry ids, registries and tags sorted, entries in the tag's own order. */
    private final Map<String, Map<String, List<String>>> registries;

    private Tags(Map<String, Map<String, List<String>>> registries) {
        this.registries = registries;
    }

    static Tags of(RegistryAccess access) {
        long start = System.nanoTime();
        Map<String, Map<String, List<String>>> registries = new TreeMap<>();
        access.registries().forEach(entry -> registries.put(entry.key().location().toString(), tags(entry.value())));
        Tags tags = new Tags(registries);
        LOG.info("[dumper] captured {} tags of {} registries in {} ms", tags.count(), registries.size(),
                (System.nanoTime() - start) / 1_000_000L);
        return tags;
    }

    /**
     * The entries of each of {@code registry}'s tags, in the tag's order: some mods take a tag's first entry for the
     * one to make, so the order is part of what a tag says. Every entry of a bound tag is a registered one.
     */
    private static <T> Map<String, List<String>> tags(Registry<T> registry) {
        Map<String, List<String>> tags = new TreeMap<>();
        registry.getTags().forEach(pair -> {
            List<String> entries = new ArrayList<>(pair.getSecond().size());
            for (Holder<T> holder : pair.getSecond()) {
                entries.add(holder.unwrapKey().orElseThrow(() -> new IllegalStateException("tag " + pair.getFirst()
                        + " holds an unregistered " + holder)).location().toString());
            }
            tags.put(pair.getFirst().location().toString(), entries);
        });
        return tags;
    }

    private int count() {
        return registries.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * One registry after another, a registry with no tags as an empty object, and one tag a line with its entries, so
     * the file streams and diffs line by line like the others.
     */
    void write(Path file) throws IOException {
        Lang.writeJson(file, out -> {
            int registriesLeft = registries.size();
            for (Map.Entry<String, Map<String, List<String>>> registry : registries.entrySet()) {
                out.write(RecipeJson.str(registry.getKey()));
                out.write(":{\n");
                int tagsLeft = registry.getValue().size();
                for (Map.Entry<String, List<String>> tag : registry.getValue().entrySet()) {
                    out.write(RecipeJson.str(tag.getKey()));
                    out.write(":[");
                    List<String> entries = tag.getValue();
                    for (int i = 0; i < entries.size(); i++) {
                        if (i > 0) out.write(',');
                        out.write(RecipeJson.str(entries.get(i)));
                    }
                    out.write(--tagsLeft > 0 ? "],\n" : "]\n");
                }
                out.write(--registriesLeft > 0 ? "},\n" : "}\n");
            }
        });
        LOG.info("[dumper] wrote {} tags of {} registries to {}", count(), registries.size(), file);
    }
}
