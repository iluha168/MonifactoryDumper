package com.iluha168.monifactory.dumper;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Why recipes were drawn whole, counted for the run's summary: by reason, and by category with each category's
 * reasons. A reason here is its kind ("differs from the real render"), not the detail that goes to
 * {@code render.tsv} (which frame, how many pixels), so recipes that failed the same way count together. Pure.
 */
final class FallbackTally {
    private final Map<String, Integer> byReason = new HashMap<>();
    private final Map<String, Map<String, Integer>> byCategory = new HashMap<>();
    private int total;

    void add(String category, String reason) {
        total++;
        byReason.merge(reason, 1, Integer::sum);
        byCategory.computeIfAbsent(category, c -> new TreeMap<>()).merge(reason, 1, Integer::sum);
    }

    int total() {
        return total;
    }

    /** {@code count reason} per line, the most common first, ties by name. Empty when nothing fell back. */
    String byReason() {
        StringBuilder out = new StringBuilder();
        sorted(byReason).forEach(e -> out.append("\n  ").append(e.getValue()).append('\t').append(e.getKey()));
        return out.toString();
    }

    /**
     * The {@code top} categories with the most fallbacks, one per line: {@code count category {reason=count, ...}},
     * the most first, ties by name. Says how many categories were left out.
     */
    String byCategory(int top) {
        Map<String, Integer> counts = new HashMap<>();
        byCategory.forEach((category, reasons) ->
                counts.put(category, reasons.values().stream().mapToInt(Integer::intValue).sum()));
        List<Map.Entry<String, Integer>> sorted = sorted(counts);
        StringBuilder out = new StringBuilder();
        sorted.stream().limit(top).forEach(e -> out.append("\n  ").append(e.getValue()).append('\t')
                .append(e.getKey()).append('\t').append(byCategory.get(e.getKey())));
        if (sorted.size() > top) out.append("\n  (").append(sorted.size() - top).append(" more categories)");
        return out.toString();
    }

    private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
    }
}
