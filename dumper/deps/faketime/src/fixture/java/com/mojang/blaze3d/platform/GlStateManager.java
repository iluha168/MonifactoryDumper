package com.mojang.blaze3d.platform;

/** Empty bodies: max stack 0, so each entry hook has only its own needs to go by. */
public class GlStateManager {
    public static void _drawElements(int mode, int count, int type, long indices) {
    }

    public static void _enableBlend() {
    }

    public static void _disableBlend() {
    }

    public static void _blendFunc(int srcFactor, int dstFactor) {
    }

    public static void _blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
    }

    public static void _blendEquation(int mode) {
    }

    public static void _colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
    }

    public static void _enableColorLogicOp() {
    }

    public static void _disableColorLogicOp() {
    }
}
