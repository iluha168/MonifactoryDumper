package com.iluha168.monifactory.dumper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Gives every recipe of a list a key of its own that the next boot gives it too, from two descriptions of each: its
 * identity (category and ids, or with no ids its stacks without amounts or NBT), which holds from boot to boot, and its
 * detail (every stack with amount, chance and NBT, the result off the card where the recipe lists none), which tells
 * apart recipes that share an identity but for some recipes changes from boot to boot.
 * <p>
 * So the detail only goes into the key where the identity is not enough. A recipe whose identity no other recipe of the
 * list shares is keyed by its identity alone: a GregTech recycling recipe keeps its key while its amounts move and the
 * crafting recipe whose ingredient NBT variant GregTech picks per boot keeps its key. A recipe that shares its identity
 * is keyed by identity and detail: FramedBlocks' framing saw names none of its 29,584 recipes and hides their results
 * from EMI, so by identity they were a few dozen keys, three of them 134 recipes each.
 * <p>
 * Recipes equal in identity and detail too look the same in every way the artifact can tell (TooManyRecipeViewers lists
 * each of Thermal's disenchantment fuels three times), so which of them is which does not matter, only that there are
 * as many keys as recipes. The second and later ones get their occurrence, in the list's order: {@code key|#1},
 * {@code key|#2}.
 */
final class StableKeys {
    private StableKeys() {
    }

    /** The keys, in the list's order, and how many recipes needed their detail and their occurrence. */
    record Keys(List<String> keys, int detailed, int occurrences) {
    }

    /** {@code detail} is asked only for the recipes whose identity another shares, by their index in the list. */
    static Keys of(List<String> identities, IntFunction<String> detail) {
        Map<String, Integer> shared = new HashMap<>();
        for (String identity : identities) shared.merge(identity, 1, Integer::sum);
        List<String> keys = new ArrayList<>(identities.size());
        int detailed = 0;
        for (int i = 0; i < identities.size(); i++) {
            String identity = identities.get(i);
            if (shared.get(identity) == 1) {
                keys.add(identity);
            } else {
                keys.add(identity + "|" + detail.apply(i));
                detailed++;
            }
        }
        Map<String, Integer> seen = new HashMap<>();
        int occurrences = 0;
        for (int i = 0; i < keys.size(); i++) {
            int occurrence = seen.merge(keys.get(i), 1, Integer::sum) - 1;
            if (occurrence > 0) {
                keys.set(i, keys.get(i) + "|#" + occurrence);
                occurrences++;
            }
        }
        return new Keys(keys, detailed, occurrences);
    }
}
