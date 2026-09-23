package net.minecraft.client.gui;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

/**
 * The four drawInBatch overloads under their SRG names, calling each other the way the real ones do. Their bodies
 * need less stack than the hook's five arguments.
 */
public class Font {
    public enum DisplayMode {NORMAL}

    public int m_271703_(String text, float x, float y, int color, boolean shadow, Matrix4f matrix,
                         MultiBufferSource buffers, DisplayMode mode, int background, int light) {
        return m_272078_(text, x, y, color, shadow, matrix, buffers, mode, background, light, false);
    }

    public int m_272078_(String text, float x, float y, int color, boolean shadow, Matrix4f matrix,
                         MultiBufferSource buffers, DisplayMode mode, int background, int light, boolean bidi) {
        return 0;
    }

    public int m_272077_(Component text, float x, float y, int color, boolean shadow, Matrix4f matrix,
                         MultiBufferSource buffers, DisplayMode mode, int background, int light) {
        return m_272191_(null, x, y, color, shadow, matrix, buffers, mode, background, light);
    }

    public int m_272191_(FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4f matrix,
                         MultiBufferSource buffers, DisplayMode mode, int background, int light) {
        return 0;
    }
}
