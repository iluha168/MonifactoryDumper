/**
 * The descriptor of the merged module, not of this jar alone. BootstrapLauncher's -DmergeModules
 * unions this jar with the client's lwjgl-glfw and the EGL binding, and the last jar on the
 * classpath that carries a module-info names the result. That has to stay org.lwjgl.glfw, because
 * the GLFW natives module requires it by that name.
 *
 * org.lwjgl.egl is inside the union, so it needs no requires - and since it is not exported, no
 * code outside this jar can make an EGL call. Everything EGL lives in {@link org.lwjgl.glfw.GLFW}.
 */
module org.lwjgl.glfw {
    requires org.lwjgl;
    requires org.lwjgl.opengl;
    exports org.lwjgl.glfw;
}
