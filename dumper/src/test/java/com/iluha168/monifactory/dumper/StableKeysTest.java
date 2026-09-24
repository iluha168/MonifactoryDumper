package com.iluha168.monifactory.dumper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StableKeysTest {
    @Test
    void anIdentityOfItsOwnIsTheKeyAndItsDetailIsNeverAsked() {
        List<Integer> asked = new ArrayList<>();
        StableKeys.Keys keys = StableKeys.of(List.of("gtceu:mixer|a|a", "minecraft:crafting|b|b"), i -> {
            asked.add(i);
            return "detail" + i;
        });
        assertEquals(List.of("gtceu:mixer|a|a", "minecraft:crafting|b|b"), keys.keys());
        assertEquals(List.of(), asked, "a recipe keyed by its identity alone should not pay for its detail");
        assertEquals(0, keys.detailed());
        assertEquals(0, keys.occurrences());
    }

    @Test
    void aSharedIdentityGetsEachRecipesDetail() {
        List<String> details = List.of("in=door*2|out=cube*1", "unused", "in=door*1|out=slope*1");
        StableKeys.Keys keys = StableKeys.of(List.of("saw|x", "other", "saw|x"), details::get);
        assertEquals(List.of("saw|x|in=door*2|out=cube*1", "other", "saw|x|in=door*1|out=slope*1"), keys.keys());
        assertEquals(2, keys.detailed());
        assertEquals(0, keys.occurrences());
    }

    @Test
    void recipesEqualInEveryWayAreToldApartByOccurrenceInListOrder() {
        List<String> identities = List.of("fuel|book", "fuel|book", "fuel|book", "fuel|book");
        List<String> details = List.of("in=prot", "in=fire", "in=prot", "in=prot");
        StableKeys.Keys keys = StableKeys.of(identities, details::get);
        assertEquals(List.of("fuel|book|in=prot", "fuel|book|in=fire", "fuel|book|in=prot|#1", "fuel|book|in=prot|#2"),
                keys.keys());
        assertEquals(4, keys.detailed());
        assertEquals(2, keys.occurrences());
    }

    @Test
    void everyRecipeGetsAKeyOfItsOwn() {
        List<String> identities = new ArrayList<>();
        for (int i = 0; i < 1000; i++) identities.add("cat|" + i % 7);
        StableKeys.Keys keys = StableKeys.of(identities, i -> "d" + i % 13);
        assertEquals(identities.size(), new HashSet<>(keys.keys()).size());
    }

    @Test
    void aRecipeKeepsItsKeyWhenOthersComeAndGoAroundIt() {
        // EMI's list drifts between boots; a recipe that shares nothing keeps its key whatever else is listed.
        StableKeys.Keys one = StableKeys.of(List.of("a", "b", "c"), i -> "d");
        StableKeys.Keys two = StableKeys.of(List.of("x", "a", "c"), i -> "d");
        assertEquals(one.keys().get(0), two.keys().get(1));
        assertEquals(one.keys().get(2), two.keys().get(2));
    }
}
