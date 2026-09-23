package com.iluha168.monifactory.compare;

import com.iluha168.monifactory.imgencoder.FramePolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks the build's loop detection offline, on identical pixels (PLAN section 4 and M4): one boot records raw
 * frame-hash sequences ({@code seq.csv}, the renderer's {@code seq} mode), and the rule is then a function of them.
 * <p>
 * {@link FramePolicy}, the early-stopping strict period the build stores loops by, is checked against the full strict
 * rule over {@link FramePolicy#CAP} frames, on every sequence with more than one distinct frame. Checked from several
 * start phases of each sequence, because the build's sequences start wherever the atlas happens to stand. Where the
 * two disagree on the period but store the same frames (a period of exactly 40 against no closure, both storing frames
 * 0 to 39), the stored loop is the same; where they store different frame counts, the build would ship a different
 * loop than the rule says.
 * <p>
 * Whether a layer moves at all is not decided from its frames: the renderer watches what frame 0's draw reads
 * (DESIGN 3.4), so there is nothing about it to check here.
 * <p>
 * Usage: {@code <seq.csv>...}. Exits 1 if the policy stores different frames anywhere.
 */
public final class CheckDetection {
    private static final int[] STARTS = {0, 11, 23, 37, 53, 60};

    public static void main(String[] args) throws IOException {
        boolean bad = false;
        for (String file : args) bad |= check(Path.of(file));
        System.exit(bad ? 1 : 0);
    }

    private static boolean check(Path file) throws IOException {
        int rows = 0, failed = 0, animated = 0, windows = 0, periodDiffers = 0, storedDiffers = 0;
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
                if (Arrays.stream(h).distinct().count() < 2) continue;
                animated++;

                for (int start : STARTS) {
                    if (start + FramePolicy.CAP > h.length) continue;
                    long[] window = Arrays.copyOfRange(h, start, start + FramePolicy.CAP);
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
        System.out.printf("  frame policy over %d windows (%d start phases): period differs on %d, stored frames differ"
                        + " on %d; %.1f frames drawn per window against %d for the full rule%n", windows, STARTS.length,
                periodDiffers, storedDiffers, windows == 0 ? 0.0 : (double) drawn / windows, FramePolicy.CAP);
        kinds.forEach((kind, count) -> System.out.println("    " + count + "x " + kind));
        examples.forEach(example -> System.out.println("    " + example));
        return storedDiffers > 0;
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
