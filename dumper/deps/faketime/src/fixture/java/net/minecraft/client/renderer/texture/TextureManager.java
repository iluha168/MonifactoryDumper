package net.minecraft.client.renderer.texture;

import java.util.List;

/** A loop in tick(), like the real one, so the gate's entry frame is tested in front of frames of the method's own. */
public class TextureManager {
    public final List<int[]> tickables = List.of(new int[1], new int[1]);

    public void m_7673_() {
        for (int[] tickable : tickables) {
            tickable[0]++;
        }
    }

    public int ticks() {
        return tickables.get(0)[0];
    }
}
