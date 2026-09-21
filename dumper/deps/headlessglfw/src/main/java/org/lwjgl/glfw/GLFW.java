package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL10;
import org.lwjgl.egl.EGL12;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * GLFW with no window system behind it. The one window Minecraft asks for is a handle that stands
 * for an EGL context on the GPU's device platform, which needs no X11 or Wayland display. Nothing
 * is ever presented: the renderer reads its pixels back from framebuffers.
 *
 * <p>The method set is every GLFW call site in the Minecraft 1.20.1 + Forge 47.4.13 client (Window,
 * GLX, InputConstants, MouseHandler, KeyboardHandler, ClipboardManager, ScreenManager, Monitor).
 * Anything else that calls into GLFW fails with a NoSuchMethodError, which is the right way to
 * find out about it.
 *
 * <p>The context is created once, on whichever thread first initializes GLFW - Minecraft's render
 * thread - and stays current there until the process exits. There is deliberately no way to hand
 * it to another thread: the renderer runs inside the game's own frames and never needs to.
 */
public final class GLFW {
    public static final int GLFW_TRUE = 1;
    public static final int GLFW_FALSE = 0;
    public static final int GLFW_RELEASE = 0;
    public static final int GLFW_PRESS = 1;
    public static final int GLFW_FOCUSED = 0x20001;
    public static final int GLFW_ICONIFIED = 0x20002;
    public static final int GLFW_MAXIMIZED = 0x20003;
    public static final int GLFW_VISIBLE = 0x20004;
    public static final int GLFW_CLIENT_API = 0x22001;
    public static final int GLFW_CONTEXT_VERSION_MAJOR = 0x22002;
    public static final int GLFW_CONTEXT_VERSION_MINOR = 0x22003;
    public static final int GLFW_OPENGL_API = 0x30001;

    /** Stands for the window, and for the monitor too - both only have to be nonzero. */
    public static final long FAKE_WINDOW = 0x5EED0001L;
    private static final long FAKE_CURSOR = 0x5EEDC0DEL;
    public static final int WIDTH = 854;
    public static final int HEIGHT = 480;

    private static final long T0 = System.nanoTime();
    private static boolean initialized = false;
    private static Thread owner = null;
    private static boolean warnedForeignThread = false;
    private static GLFWErrorCallback errorCallback = null;
    private static int glMajor = 3, glMinor = 3;

    private GLFW() {}

    // EGL enums, spelled out because the binding's constant classes are split across versions.
    private static final int EGL_PLATFORM_DEVICE_EXT = 0x313F;
    private static final int EGL_OPENGL_API = 0x30A2;
    private static final int EGL_SURFACE_TYPE = 0x3033, EGL_PBUFFER_BIT = 0x0001;
    private static final int EGL_RENDERABLE_TYPE = 0x3040, EGL_OPENGL_BIT = 0x0008;
    private static final int EGL_RED_SIZE = 0x3024, EGL_GREEN_SIZE = 0x3023, EGL_BLUE_SIZE = 0x3022,
        EGL_ALPHA_SIZE = 0x3021, EGL_DEPTH_SIZE = 0x3025;
    private static final int EGL_CONTEXT_MAJOR_VERSION = 0x3098, EGL_CONTEXT_MINOR_VERSION = 0x30FB;
    private static final int EGL_WIDTH = 0x3057, EGL_HEIGHT = 0x3056;
    private static final int EGL_NONE = 0x3038;

    private static synchronized boolean ensureContext() {
        if (owner != null) {
            if (owner != Thread.currentThread() && !warnedForeignThread) {
                warnedForeignThread = true;
                System.out.println("[headlessglfw] " + Thread.currentThread().getName()
                    + " asked for the context, which stays current on " + owner.getName());
            }
            return true;
        }
        try {
            try {
                EGL.create();
            } catch (IllegalStateException alreadyCreated) {
                // Harmless: someone else loaded libEGL first.
            }
            // The device platform is what makes this work with no display server. EGL 1.5 names
            // the entry point without the suffix, older drivers only have the extension.
            FunctionProvider functions = EGL.getFunctionProvider();
            long getPlatformDisplay = functions.getFunctionAddress("eglGetPlatformDisplay");
            if (getPlatformDisplay == 0) getPlatformDisplay = functions.getFunctionAddress("eglGetPlatformDisplayEXT");
            if (getPlatformDisplay == 0) throw new IllegalStateException("EGL has no eglGetPlatformDisplay");
            long display = JNI.invokePPPP(EGL_PLATFORM_DEVICE_EXT, 0, 0, getPlatformDisplay);
            if (display == 0) throw new IllegalStateException("no EGL device display");

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer major = stack.mallocInt(1), minor = stack.mallocInt(1);
                if (!EGL10.eglInitialize(display, major, minor)) throw new IllegalStateException("eglInitialize failed");
                EGL12.eglBindAPI(EGL_OPENGL_API);

                PointerBuffer configs = stack.mallocPointer(4);
                IntBuffer found = stack.mallocInt(1);
                IntBuffer wanted = stack.ints(
                    EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                    EGL_DEPTH_SIZE, 24,
                    EGL_NONE);
                if (!EGL10.eglChooseConfig(display, wanted, configs, found) || found.get(0) < 1) {
                    throw new IllegalStateException("eglChooseConfig found no config");
                }
                long config = configs.get(0);

                // 3.3 is what Minecraft asks GLFW for. A driver that refuses a versioned request
                // still hands out its newest compatible context when asked for nothing in particular.
                long context = EGL10.eglCreateContext(display, config, 0,
                    stack.ints(EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3, EGL_NONE));
                if (context == 0) context = EGL10.eglCreateContext(display, config, 0, stack.ints(EGL_NONE));
                if (context == 0) throw new IllegalStateException("eglCreateContext failed");

                // Surfaceless first. Without EGL_KHR_surfaceless_context, a 1x1 pbuffer does the
                // same job: whatever the game blits to the default framebuffer is never looked at.
                if (!EGL10.eglMakeCurrent(display, 0, 0, context)) {
                    long pbuffer = EGL10.eglCreatePbufferSurface(display, config, stack.ints(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE));
                    if (pbuffer == 0 || !EGL10.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                        throw new IllegalStateException("eglMakeCurrent failed");
                    }
                }
            }

            GL.createCapabilities();
            // Minecraft reads the context version back through glfwGetWindowAttrib.
            String version = GL11C.glGetString(GL11C.GL_VERSION);
            if (version != null && version.matches(".*\\d+\\.\\d+.*")) {
                String[] parts = version.replaceAll("^[^0-9]*", "").split("[. ]");
                try {
                    glMajor = Integer.parseInt(parts[0]);
                    glMinor = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    // Keep 3.3, which is what was asked for.
                }
            }
            owner = Thread.currentThread();
            System.out.println("[headlessglfw] EGL context current on " + owner.getName() + ": "
                + GL11C.glGetString(GL11C.GL_RENDERER) + ", GL " + glMajor + "." + glMinor);
            return true;
        } catch (Throwable t) {
            System.out.println("[headlessglfw] EGL context creation failed: " + t);
            t.printStackTrace(System.out);
            return false;
        }
    }

    public static boolean glfwInit() {
        if (!initialized) initialized = ensureContext();
        return initialized;
    }

    /** The context lives until the process does. */
    public static void glfwTerminate() {}

    // ---- window ----
    public static long glfwCreateWindow(int width, int height, CharSequence title, long monitor, long share) {
        return ensureContext() ? FAKE_WINDOW : 0L;
    }
    public static long glfwCreateWindow(int width, int height, ByteBuffer title, long monitor, long share) {
        return ensureContext() ? FAKE_WINDOW : 0L;
    }
    public static void glfwDestroyWindow(long window) {}
    public static void glfwMakeContextCurrent(long window) { if (window != 0L) ensureContext(); }
    public static long glfwGetCurrentContext() { return owner != null ? FAKE_WINDOW : 0L; }
    public static void glfwSwapBuffers(long window) {}
    public static void glfwSwapInterval(int interval) {}
    public static void glfwDefaultWindowHints() {}
    public static void glfwWindowHint(int hint, int value) {}
    public static void glfwShowWindow(long window) {}
    public static void glfwHideWindow(long window) {}
    public static void glfwPollEvents() {}
    public static void glfwWaitEventsTimeout(double timeout) {}
    public static void glfwPostEmptyEvent() {}
    public static boolean glfwWindowShouldClose(long window) { return false; }
    public static void glfwSetWindowTitle(long window, CharSequence title) {}
    public static void glfwSetWindowTitle(long window, ByteBuffer title) {}
    public static void glfwSetWindowIcon(long window, GLFWImage.Buffer icons) {}
    public static long glfwGetWindowMonitor(long window) { return 0L; }
    public static void glfwSetWindowMonitor(long window, long monitor, int xpos, int ypos, int width, int height, int refreshRate) {}
    public static int glfwGetWindowAttrib(long window, int attrib) {
        return switch (attrib) {
            case GLFW_CONTEXT_VERSION_MAJOR -> glMajor;
            case GLFW_CONTEXT_VERSION_MINOR -> glMinor;
            case GLFW_FOCUSED -> GLFW_TRUE;
            default -> GLFW_FALSE;
        };
    }
    public static void glfwSetWindowAttrib(long window, int attrib, int value) {}
    public static float glfwGetWindowOpacity(long window) { return 1.0f; }
    public static void glfwSetWindowOpacity(long window, float opacity) {}
    public static void glfwSetWindowSizeLimits(long window, int minWidth, int minHeight, int maxWidth, int maxHeight) {}
    public static void glfwRequestWindowAttention(long window) {}
    public static void glfwFocusWindow(long window) {}
    public static void glfwIconifyWindow(long window) {}
    public static void glfwRestoreWindow(long window) {}
    public static void glfwMaximizeWindow(long window) {}

    public static void glfwGetFramebufferSize(long window, int[] width, int[] height) { width[0] = WIDTH; height[0] = HEIGHT; }
    public static void glfwGetFramebufferSize(long window, IntBuffer width, IntBuffer height) { width.put(0, WIDTH); height.put(0, HEIGHT); }
    public static void glfwGetWindowSize(long window, int[] width, int[] height) { width[0] = WIDTH; height[0] = HEIGHT; }
    public static void glfwGetWindowSize(long window, IntBuffer width, IntBuffer height) { width.put(0, WIDTH); height.put(0, HEIGHT); }
    public static void glfwGetWindowPos(long window, int[] x, int[] y) { x[0] = 0; y[0] = 0; }
    public static void glfwGetWindowPos(long window, IntBuffer x, IntBuffer y) { x.put(0, 0); y.put(0, 0); }
    public static void glfwSetWindowPos(long window, int x, int y) {}

    // ---- monitors ----
    // One monitor. Its video modes live in real GLFWvidmode structs, because Monitor.refreshVideoModes
    // dereferences what both glfwGetVideoModes and glfwGetVideoMode return.
    private static final int[][] VIDEO_MODES = {{1920, 1080, 60}, {1280, 720, 60}, {WIDTH, HEIGHT, 60}};
    private static long videoModes = 0L;

    private static synchronized long videoModes() {
        if (videoModes == 0L) {
            long address = MemoryUtil.nmemAlloc((long) GLFWVidMode.SIZEOF * VIDEO_MODES.length);
            for (int i = 0; i < VIDEO_MODES.length; i++) {
                long mode = address + (long) i * GLFWVidMode.SIZEOF;
                MemoryUtil.memPutInt(mode + GLFWVidMode.WIDTH, VIDEO_MODES[i][0]);
                MemoryUtil.memPutInt(mode + GLFWVidMode.HEIGHT, VIDEO_MODES[i][1]);
                MemoryUtil.memPutInt(mode + GLFWVidMode.REDBITS, 8);
                MemoryUtil.memPutInt(mode + GLFWVidMode.GREENBITS, 8);
                MemoryUtil.memPutInt(mode + GLFWVidMode.BLUEBITS, 8);
                MemoryUtil.memPutInt(mode + GLFWVidMode.REFRESHRATE, VIDEO_MODES[i][2]);
            }
            videoModes = address;
        }
        return videoModes;
    }

    public static long glfwGetPrimaryMonitor() { return FAKE_WINDOW; }
    public static PointerBuffer glfwGetMonitors() {
        PointerBuffer monitors = PointerBuffer.allocateDirect(1);
        monitors.put(0, FAKE_WINDOW);
        return monitors;
    }
    public static void glfwGetMonitorPos(long monitor, int[] x, int[] y) { x[0] = 0; y[0] = 0; }
    public static void glfwGetMonitorPos(long monitor, IntBuffer x, IntBuffer y) { x.put(0, 0); y.put(0, 0); }
    public static GLFWVidMode glfwGetVideoMode(long monitor) { return GLFWVidMode.create(videoModes()); }
    public static GLFWVidMode.Buffer glfwGetVideoModes(long monitor) { return new GLFWVidMode.Buffer(videoModes(), VIDEO_MODES.length); }
    public static GLFWMonitorCallback glfwSetMonitorCallback(GLFWMonitorCallbackI callback) { return null; }

    // ---- input: nothing is ever pressed, the cursor rests in the middle ----
    public static int glfwGetInputMode(long window, int mode) { return 0; }
    public static void glfwSetInputMode(long window, int mode, int value) {}
    public static boolean glfwRawMouseMotionSupported() { return false; }
    public static int glfwGetKey(long window, int key) { return GLFW_RELEASE; }
    public static int glfwGetMouseButton(long window, int button) { return GLFW_RELEASE; }
    public static String glfwGetKeyName(int key, int scancode) { return null; }
    public static void glfwGetCursorPos(long window, DoubleBuffer x, DoubleBuffer y) { x.put(0, WIDTH / 2.0); y.put(0, HEIGHT / 2.0); }
    public static void glfwGetCursorPos(long window, double[] x, double[] y) { x[0] = WIDTH / 2.0; y[0] = HEIGHT / 2.0; }
    public static void glfwSetCursorPos(long window, double x, double y) {}
    public static long glfwCreateStandardCursor(int shape) { return FAKE_CURSOR; }
    public static long glfwCreateCursor(GLFWImage image, int xhot, int yhot) { return FAKE_CURSOR; }
    public static void glfwDestroyCursor(long cursor) {}
    public static void glfwSetCursor(long window, long cursor) {}

    // ---- callbacks: no events ever happen, so none is kept except the error callback ----
    public static GLFWErrorCallback glfwSetErrorCallback(GLFWErrorCallbackI callback) {
        GLFWErrorCallback previous = errorCallback;
        errorCallback = callback instanceof GLFWErrorCallback concrete ? concrete : null;
        return previous;
    }
    public static int glfwGetError(PointerBuffer description) { return 0; }
    public static GLFWWindowPosCallback glfwSetWindowPosCallback(long window, GLFWWindowPosCallbackI callback) { return null; }
    public static GLFWWindowSizeCallback glfwSetWindowSizeCallback(long window, GLFWWindowSizeCallbackI callback) { return null; }
    public static GLFWWindowCloseCallback glfwSetWindowCloseCallback(long window, GLFWWindowCloseCallbackI callback) { return null; }
    public static GLFWWindowFocusCallback glfwSetWindowFocusCallback(long window, GLFWWindowFocusCallbackI callback) { return null; }
    public static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI callback) { return null; }
    public static GLFWWindowMaximizeCallback glfwSetWindowMaximizeCallback(long window, GLFWWindowMaximizeCallbackI callback) { return null; }
    public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(long window, GLFWFramebufferSizeCallbackI callback) { return null; }
    public static GLFWKeyCallback glfwSetKeyCallback(long window, GLFWKeyCallbackI callback) { return null; }
    public static GLFWCharCallback glfwSetCharCallback(long window, GLFWCharCallbackI callback) { return null; }
    public static GLFWCharModsCallback glfwSetCharModsCallback(long window, GLFWCharModsCallbackI callback) { return null; }
    public static GLFWMouseButtonCallback glfwSetMouseButtonCallback(long window, GLFWMouseButtonCallbackI callback) { return null; }
    public static GLFWCursorEnterCallback glfwSetCursorEnterCallback(long window, GLFWCursorEnterCallbackI callback) { return null; }
    public static GLFWCursorPosCallback glfwSetCursorPosCallback(long window, GLFWCursorPosCallbackI callback) { return null; }
    public static GLFWScrollCallback glfwSetScrollCallback(long window, GLFWScrollCallbackI callback) { return null; }
    public static GLFWDropCallback glfwSetDropCallback(long window, GLFWDropCallbackI callback) { return null; }

    // ---- clipboard ----
    public static void glfwSetClipboardString(long window, CharSequence string) {}
    public static void glfwSetClipboardString(long window, ByteBuffer string) {}
    public static String glfwGetClipboardString(long window) { return null; }

    // ---- time ----
    public static double glfwGetTime() { return (System.nanoTime() - T0) / 1e9; }
    public static void glfwSetTime(double time) {}
}
