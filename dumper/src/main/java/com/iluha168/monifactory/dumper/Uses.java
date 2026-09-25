package com.iluha168.monifactory.dumper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the draws of one still used, for {@code still_uses.json}: each list in the order the draws first used its
 * entries, none twice.
 *
 * @param textures the registered textures its draws sampled, by location, glyph pages left out: the text says it
 * @param sprites  the atlas sprites under the vertices it drew through the uploader, by name
 * @param models   the item models it drew items from, by their location in the model manager
 * @param items    the items it drew, by id, with the NBT after it as {@code /give} writes it if there is any
 * @param fluids   the fluids whose sprite and tint it asked Forge for, by id
 * @param texts    the text it drew, one entry per line, as plain text without formatting
 */
record Uses(List<String> textures, List<String> sprites, List<String> models, List<String> items,
            List<String> fluids, List<String> texts) {
    static final Uses NONE = new Uses(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    /** Both stills' uses, this one's first: two draws that came out the same picture. */
    Uses merge(Uses other) {
        if (other == null || other.equals(this) || other.equals(NONE)) return this;
        if (equals(NONE)) return other;
        return new Uses(union(textures, other.textures), union(sprites, other.sprites), union(models, other.models),
                union(items, other.items), union(fluids, other.fluids), union(texts, other.texts));
    }

    private static List<String> union(List<String> a, List<String> b) {
        if (b.isEmpty() || a.equals(b)) return a;
        if (a.isEmpty()) return b;
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return all.size() == a.size() ? a : List.copyOf(all);
    }

    /**
     * One row of {@code still_uses.json}: an object with a key per list that is not empty, in the order of the record's
     * components. A still nothing registered went into is {@code {}}.
     */
    String toJson() {
        StringBuilder json = new StringBuilder("{");
        list(json, "textures", textures);
        list(json, "sprites", sprites);
        list(json, "models", models);
        list(json, "items", items);
        list(json, "fluids", fluids);
        list(json, "texts", texts);
        return json.append('}').toString();
    }

    private static void list(StringBuilder json, String key, List<String> values) {
        if (values.isEmpty()) return;
        if (json.length() > 1) json.append(',');
        json.append('"').append(key).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append(RecipeJson.str(values.get(i)));
        }
        json.append(']');
    }

    /**
     * The immutable copy of one draw's findings. Strings are interned: the same few thousand sprite and texture names
     * turn up in every other still, and a batch keeps every still's uses until it writes them.
     */
    static Uses of(Collection<String> textures, Collection<String> sprites, Collection<String> models,
                   Collection<String> items, Collection<String> fluids, Collection<String> texts) {
        Uses uses = new Uses(interned(textures), interned(sprites), interned(models), interned(items),
                interned(fluids), interned(texts));
        return uses.equals(NONE) ? NONE : uses;
    }

    private static List<String> interned(Collection<String> values) {
        if (values.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) out.add(value.intern());
        return List.copyOf(out);
    }
}
