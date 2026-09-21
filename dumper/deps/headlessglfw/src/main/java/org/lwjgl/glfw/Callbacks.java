package org.lwjgl.glfw;

/**
 * The real class frees callbacks through native nglfwSet*Callback methods that the fake
 * {@link GLFW} does not have. Minecraft only ever calls glfwFreeCallbacks, from Window.close, and
 * the fake never stored a native callback to free.
 */
public final class Callbacks {
    private Callbacks() {}

    public static void glfwFreeCallbacks(long window) {}
}
