package com.iluha168.monifactory.dumper;

import java.util.Map;

/**
 * What one draw of a layer blends with, as the game asked {@code GlStateManager} for it, and how a layer drawn once
 * over a transparent clear has to blend instead so that the target ends up holding its picture (DESIGN 3.2).
 * <p>
 * The target holds premultiplied colour {@code p} and, in its alpha channel, the layer's transmittance {@code t}: how
 * much of whatever lies under the layer still shows through. Cleared, {@code p = 0} and {@code t = 1}. A draw that
 * blends {@code p' = src + p * f} with a factor {@code f} that is one number per pixel, taken from the source alone,
 * lets through {@code t' = t * f} of what was under it, so its alpha factors are set to {@code ZERO} for the source
 * and {@code f} for the destination. Composited over any background {@code B}, the layer is then
 * {@code p + B * t}, exactly what the draws would have made of {@code B} in the real render; straight alpha is
 * {@code a = 1 - t} and colour {@code p / a}. That covers standard "over" ({@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA}),
 * premultiplied over ({@code ONE, ONE_MINUS_SRC_ALPHA}) and additive draws such as the enchantment glint
 * ({@code SRC_COLOR, ONE}, which leaves {@code t} alone).
 * <p>
 * Blending off is the case the game's own alpha channel gets wrong: the fragment replaces the pixel, colour and alpha,
 * and a cutout texture can hand it an alpha below 1. The real render shows that colour opaque, and so did the old
 * black and white draws, but one draw with the game's blending would store it translucent. As a blend it is
 * {@code ONE, ZERO}, so the draw goes out with blending on, {@code p' = src}, and {@code t' = 0}: opaque, whatever the
 * fragment's alpha.
 * <p>
 * Anything else can't be captured in one draw: a destination factor per colour channel ({@code SRC_COLOR} and its
 * inverse, where each channel lets through its own amount), a factor that reads the destination ({@code DST_COLOR},
 * {@code DST_ALPHA} and the like, in the real render a different target), an equation other than add, a colour mask
 * that holds back a channel, a colour logic op. Such a layer is drawn over black and over white instead.
 * <p>
 * Factors and the equation are GL enums. Pure.
 */
record BlendState(boolean on, int srcRgb, int dstRgb, int equation, int colorMask, boolean logicOp) {
    static final int ZERO = 0, ONE = 1, SRC_COLOR = 0x0300, ONE_MINUS_SRC_COLOR = 0x0301, SRC_ALPHA = 0x0302,
            ONE_MINUS_SRC_ALPHA = 0x0303, DST_ALPHA = 0x0304, ONE_MINUS_DST_ALPHA = 0x0305, DST_COLOR = 0x0306,
            ONE_MINUS_DST_COLOR = 0x0307, SRC_ALPHA_SATURATE = 0x0308, CONSTANT_COLOR = 0x8001,
            ONE_MINUS_CONSTANT_COLOR = 0x8002, CONSTANT_ALPHA = 0x8003, ONE_MINUS_CONSTANT_ALPHA = 0x8004;
    static final int FUNC_ADD = 0x8006;
    /** Every colour channel written: red 8, green 4, blue 2, alpha 1. */
    static final int ALL_CHANNELS = 0xF;

    /**
     * The state as the game asked for it. With blending off the factors and the equation do nothing, so they are
     * dropped, and two draws that differ only in them are one state.
     */
    static BlendState of(boolean on, int srcRgb, int dstRgb, int equation, int colorMask, boolean logicOp) {
        return on ? new BlendState(true, srcRgb, dstRgb, equation, colorMask, logicOp)
                : new BlendState(false, ONE, ZERO, FUNC_ADD, colorMask, logicOp);
    }

    /**
     * Whether one draw with {@link #captureSrc} and {@link #captureDst}, blending on and the equation add, leaves
     * exactly this draw's picture.
     */
    boolean oneDraw() {
        if (logicOp || colorMask != ALL_CHANNELS) return false;
        if (!on) return true;
        return equation == FUNC_ADD && sourceOnly(srcRgb) && scalar(dstRgb);
    }

    /** The colour source factor a capture draws with. Its alpha source factor is always {@code ZERO}. */
    int captureSrc() {
        return on ? srcRgb : ONE;
    }

    /**
     * The destination factor a capture draws with, for colour and alpha alike: for a {@link #oneDraw} state it is one
     * number for every channel, so the transmittance goes down by what the colour lets through.
     */
    int captureDst() {
        return on ? dstRgb : ZERO;
    }

    /** A factor computed from the source fragment and constants only. */
    private static boolean sourceOnly(int factor) {
        return switch (factor) {
            case ZERO, ONE, SRC_COLOR, ONE_MINUS_SRC_COLOR, SRC_ALPHA, ONE_MINUS_SRC_ALPHA, CONSTANT_COLOR,
                 ONE_MINUS_CONSTANT_COLOR, CONSTANT_ALPHA, ONE_MINUS_CONSTANT_ALPHA -> true;
            default -> false;
        };
    }

    /** A source-only factor that is one number for all three colour channels. */
    private static boolean scalar(int factor) {
        return switch (factor) {
            case ZERO, ONE, SRC_ALPHA, ONE_MINUS_SRC_ALPHA, CONSTANT_ALPHA, ONE_MINUS_CONSTANT_ALPHA -> true;
            default -> false;
        };
    }

    private static final Map<Integer, String> FACTORS = Map.ofEntries(Map.entry(ZERO, "ZERO"), Map.entry(ONE, "ONE"),
            Map.entry(SRC_COLOR, "SRC_COLOR"), Map.entry(ONE_MINUS_SRC_COLOR, "ONE_MINUS_SRC_COLOR"),
            Map.entry(SRC_ALPHA, "SRC_ALPHA"), Map.entry(ONE_MINUS_SRC_ALPHA, "ONE_MINUS_SRC_ALPHA"),
            Map.entry(DST_ALPHA, "DST_ALPHA"), Map.entry(ONE_MINUS_DST_ALPHA, "ONE_MINUS_DST_ALPHA"),
            Map.entry(DST_COLOR, "DST_COLOR"), Map.entry(ONE_MINUS_DST_COLOR, "ONE_MINUS_DST_COLOR"),
            Map.entry(SRC_ALPHA_SATURATE, "SRC_ALPHA_SATURATE"), Map.entry(CONSTANT_COLOR, "CONSTANT_COLOR"),
            Map.entry(ONE_MINUS_CONSTANT_COLOR, "ONE_MINUS_CONSTANT_COLOR"),
            Map.entry(CONSTANT_ALPHA, "CONSTANT_ALPHA"), Map.entry(ONE_MINUS_CONSTANT_ALPHA, "ONE_MINUS_CONSTANT_ALPHA"));
    private static final Map<Integer, String> EQUATIONS = Map.of(FUNC_ADD, "ADD", 0x8007, "MIN", 0x8008, "MAX",
            0x800A, "SUBTRACT", 0x800B, "REVERSE_SUBTRACT");

    /** For logs: {@code SRC_COLOR,ONE}, {@code off}, then the equation, mask and logic op where not the usual. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(on ? name(FACTORS, srcRgb) + "," + name(FACTORS, dstRgb) : "off");
        if (equation != FUNC_ADD) out.append(" equation ").append(name(EQUATIONS, equation));
        if (colorMask != ALL_CHANNELS) {
            out.append(" mask ");
            for (int bit = 3; bit >= 0; bit--) out.append((colorMask >> bit & 1) != 0 ? "RGBA".charAt(3 - bit) : '-');
        }
        if (logicOp) out.append(" logic op");
        return out.toString();
    }

    private static String name(Map<Integer, String> names, int value) {
        String name = names.get(value);
        return name != null ? name : "0x" + Integer.toHexString(value);
    }
}
