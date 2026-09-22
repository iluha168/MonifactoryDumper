package com.iluha168.monifactory.compare;

import com.iluha168.monifactory.imgencoder.FramePolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks the build's animation detection offline, on identical pixels (PLAN section 4 and M4): one boot records raw
 * frame-hash sequences ({@code seq.csv}, the renderer's {@code seq} mode), and every rule is then a function of them.
 * <ul>
 *     <li>The probe ladder: frame 0 against 53, 97 and 199. A miss is a recipe with more than one distinct frame over
 *     the whole sequence that the ladder calls static. A false call is the reverse, and cannot happen. A miss on a
 *     loop the build would store whole (a strict period of at most {@link FramePolicy#MAX_STORED}) is a lost
 *     animation and fails the check. Other misses are listed and pass. A longer loop never closes within
 *     {@link FramePolicy#CAP} and is stored as 40 frames from wherever it stands, and a loop the probes miss is mostly
 *     plateau, so a still is one of the windows the trim could have stored anyway. A sequence with no loop at all is
 *     a few odd frames from draws that do not follow the fake clock (TMRV-bridged JEI widgets and the like).</li>
 *     <li>{@link FramePolicy}, the early-stopping strict period the build stores loops by, against the full strict
 *     rule over {@link FramePolicy#CAP} frames. Checked from several start phases of each sequence, because the
 *     build's sequences start wherever the atlas happens to stand. Where the two disagree on the period but store the
 *     same frames (a period of exactly 40 against no closure, both storing frames 0 to 39), the stored loop is the
 *     same; where they store different frame counts, the build would ship a different file than the rule says.</li>
 * </ul>
 * Usage: {@code <seq.csv>...}. Exits 1 if the ladder misses a loop or calls a still animated, or the policy stores
 * different frames anywhere.
 */
public final class CheckDetection {
    private static final int[] PROBES = {53, 97, 199};
    private static final int[] STARTS = {0, 11, 23, 37, 53, 60};

    public static void main(String[] args) throws IOException {
        boolean bad = false;
        for (String file : args) bad |= check(Path.of(file));
        System.exit(bad ? 1 : 0);
    }

    private static boolean check(Path file) throws IOException {
        int rows = 0, failed = 0, animated = 0, ladderMisses = 0, loopsMissed = 0, ladderFalse = 0, windows = 0,
                periodDiffers = 0, storedDiffers = 0;
        long drawn = 0;
        Map<String, Integer> kinds = new TreeMap<>();
        List<String> examples = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = in.readLine();
            if (header == null || !header.startsWith("recipe_id,")) throw new IOException(file + " is not a seq.csv");
            for (String line; (line = in.readLine()) != null; ) {
                List<String> cells = csv(line);
                rows++;
                String id = cells.get(0), category = cells.get(1), error = cells.get(6), hashes = cells.get(7);
                if (!error.isEmpty() || hashes.isBlank()) {
                    failed++;
                    continue;
                }
                String[] hex = hashes.trim().split(" ");
                long[] h = new long[hex.length];
                for (int k = 0; k < hex.length; k++) h[k] = Long.parseUnsignedLong(hex[k], 16);
                boolean moves = new HashSet<>(java.util.Arrays.stream(h).boxed().toList()).size() > 1;
                boolean ladder = false;
                for (int probe : PROBES) ladder |= probe < h.length && h[probe] != h[0];
                if (moves) animated++;
                if (moves && !ladder) {
                    ladderMisses++;
                    int period = FramePolicy.strictPeriod(h, h.length);
                    int changed = 0;
                    for (long hash : h) if (hash != h[0]) changed++;
                    if (period > 0 && period <= FramePolicy.MAX_STORED) loopsMissed++;
                    if (examples.size() < 20) {
                        examples.add("ladder miss: " + id + " (" + category + "), " + (period > 0 ? "loops at " + period
                                : "no loop") + ", " + changed + " of " + h.length + " frames differ from frame 0");
                    }
                }
                if (!moves && ladder) ladderFalse++;
                if (!moves) continue;

                for (int start : STARTS) {
                    if (start + FramePolicy.CAP > h.length) continue;
                    long[] window = java.util.Arrays.copyOfRange(h, start, start + FramePolicy.CAP);
                    int reference = FramePolicy.strictPeriod(window, FramePolicy.CAP);
                    FramePolicy policy = new FramePolicy();
                    int k = 0;
                    while (!policy.offer(window[k])) k++;
                    windows++;
                    drawn += policy.frames();
                    if (policy.period() == reference) continue;
                    periodDiffers++;
                    int storedReference = reference > 0 ? reference : FramePolicy.TRIM;
                    String kind = "policy " + policy.period() + " at " + policy.frames() + " frames, full rule "
                            + reference;
                    if (policy.stored() != storedReference) {
                        storedDiffers++;
                        kinds.merge(kind, 1, Integer::sum);
                        if (examples.size() < 20) {
                            examples.add("stores " + policy.stored() + " not " + storedReference + ": " + id + " ("
                                    + category + ") from frame " + start);
                        }
                    } else {
                        kinds.merge(kind + " (same frames stored)", 1, Integer::sum);
                    }
                }
            }
        }
        System.out.printf("%s: %d rows, %d failed; %d animated over the whole sequence%n", file, rows, failed, animated);
        System.out.printf("  probe ladder %s: %d missed (%d of them loops that close), %d called animated wrongly%n",
                java.util.Arrays.toString(PROBES), ladderMisses, loopsMissed, ladderFalse);
        System.out.printf("  frame policy over %d windows (%d start phases): period differs on %d, stored frames differ"
                        + " on %d; %.1f frames drawn per window against %d for the full rule%n", windows, STARTS.length,
                periodDiffers, storedDiffers, windows == 0 ? 0.0 : (double) drawn / windows, FramePolicy.CAP);
        kinds.forEach((kind, count) -> System.out.println("    " + count + "x " + kind));
        examples.forEach(example -> System.out.println("    " + example));
        return loopsMissed > 0 || ladderFalse > 0 || storedDiffers > 0;
    }

    /** One CSV line: commas separate, double quotes quote, a doubled quote is a quote. */
    private static List<String> csv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }
}
