package com.iluha168.monifactory.faketime;

/**
 * The clock that animations read while the renderer draws.
 * <p>
 * {@link com.iluha168.monifactory.faketime.agent.ClockAgent} points every {@code System.currentTimeMillis()}
 * call site it can link, and the return values of Minecraft's {@code Util.getMillis()}, {@code Level.getGameTime()}
 * and {@code Level.getDayTime()}, at this class. Until a thread calls {@link #freeze}, all of them read the real
 * clock, so boot, resource reloads and the game loop run exactly as they would without the agent.
 * <p>
 * Only the thread that froze the clock sees the frozen value. The renderer draws on the render thread, and every
 * other thread in the game (workers, netty, mod watchdogs) keeps real time for the hours a batch takes. The frozen
 * thread really does see only the frozen clock: log4j-core is patched too, so its log lines carry the fake time.
 * <p>
 * The layered renderer draws a layer once and has to tell from that one draw whether the layer could look different
 * at another time. For that the agent also reports what the frozen thread reads and draws to a {@link Recorder}:
 * every clock read, the frozen ones above and those it leaves running, and the buffers, text, items and fluids that
 * go to the GPU.
 * <p>
 * A layer drawn once over a transparent clear needs its alpha channel to hold coverage, which the game's own blend
 * state does not give it. So the agent also keeps a copy of the blend state the game asks {@code GlStateManager} for,
 * and during a {@link #capture} hands it to the renderer before every draw, for the renderer to set the GL's state
 * the draw goes out with.
 * <p>
 * State lives in static fields, so there must be exactly one copy of this class that call sites link to. At boot
 * it is the copy in the {@code com.iluha168.monifactory.faketime} module of the MC-BOOTSTRAP layer, which every
 * layer above it reads. The agent never links a call site to the unnamed copy that -javaagent puts on the app
 * class path.
 */
public final class FakeTime {
    private FakeTime() {
    }

    /**
     * One rendered frame is one 20 Hz game tick, which is also one texture atlas animation tick. Advancing the clock
     * by this much per frame and ticking the atlas once keeps the game's own ratio between the two, so a period
     * counted in frames is the real period.
     */
    public static final long FRAME_MILLIS = 50;

    private static volatile Thread owner;
    private static volatile long frozenAt;

    private static volatile boolean atlasHeld;
    private static volatile Thread atlasTicker;

    /**
     * Freezes the clock at {@code millis} for the calling thread only, until {@link #release}. Calling it again moves
     * the clock; it may move backwards.
     */
    public static void freeze(long millis) {
        frozenAt = millis;
        owner = Thread.currentThread();
    }

    /** Gives the calling thread the real clock back. Put it in a finally block around the draw. */
    public static void release() {
        if (owner == Thread.currentThread()) {
            owner = null;
        }
    }

    /** Whether the calling thread reads a frozen clock. */
    public static boolean isFrozen() {
        return owner == Thread.currentThread();
    }

    /**
     * Stops the game loop from advancing animated sprites on its own. While held, {@code TextureManager.tick()} does
     * nothing unless it is called through {@link #tickAtlas}, so sprite frames depend only on how many times the
     * renderer ticked, never on how long a frame took.
     */
    public static void holdAtlas(boolean hold) {
        atlasHeld = hold;
    }

    /** Runs {@code tick} - a call to {@code TextureManager.tick()} - with the atlas hold lifted for this thread. */
    public static void tickAtlas(Runnable tick) {
        Thread previous = atlasTicker;
        atlasTicker = Thread.currentThread();
        try {
            tick.run();
        } finally {
            atlasTicker = previous;
        }
    }

    /** Time sources, as passed to {@link Recorder#time}. */
    public static final int TIME_WALL = 0, TIME_UTIL_MILLIS = 1, TIME_NANO = 2, TIME_UTIL_NANOS = 3,
            TIME_LEVEL_GAME = 4, TIME_LEVEL_DAY = 5, TIME_GLFW = 6, TIME_SHADER_GAME = 7;
    /** How many time sources there are, for a recorder that counts them in an array. */
    public static final int TIME_SOURCES = 8;

    /**
     * Told what the frozen thread does while it is registered with {@link #record}. Arguments are the game's own
     * objects, typed Object so this module needs none of the game's classes.
     * <p>
     * Every method runs on the frozen thread in the middle of a draw. Whatever it does is part of that draw: a clock
     * it reads is reported back to it, and state it changes can change the picture.
     */
    public interface Recorder {
        /** A clock was read, one of the {@code TIME_} sources. */
        void time(int source);

        /** {@code BufferUploader.upload(BufferBuilder.RenderedBuffer)}: every buffer drawn through the uploader. */
        void upload(Object renderedBuffer);

        /**
         * {@code GlStateManager._drawElements}: every indexed draw Minecraft issues. An upload comes before the draw
         * of its buffer, so a draw without one is from a vertex buffer the uploader never saw.
         */
        void drawElements();

        /**
         * {@code Font.drawInBatch}: a String, Component or FormattedCharSequence, and where it goes. The overloads
         * call each other: the String one without a bidi flag calls the one with it, and the Component one calls the
         * FormattedCharSequence one. So one text can arrive twice in a row.
         */
        void text(Object text, float x, float y, int color, Object matrix);

        /**
         * {@code ItemRenderer.render}: an ItemStack, its ItemDisplayContext, the PoseStack it is drawn with, and the
         * BakedModel it is drawn from, overrides already resolved.
         */
        void item(Object stack, Object context, Object poseStack, Object model);

        /**
         * {@code IClientFluidTypeExtensions.of(Fluid)}: a Fluid whose client look was asked for, which is how EMI and
         * LDLib find a fluid's sprite and tint before they draw it.
         */
        void fluid(Object fluid);
    }

    private static volatile Recorder recorder;

    /**
     * Told a blend state as the game asked {@code GlStateManager} for it: whether blending is on, the four factors,
     * the equation, the colour write mask as bits (8 red, 4 green, 2 blue, 1 alpha), and whether a colour logic op is
     * on. Factors and the equation are GL enums.
     */
    public interface Blend {
        void state(boolean on, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, int equation, int colorMask,
                   boolean logicOp);
    }

    // The blend state asked for, as GlStateManager's own cache holds it, starting from the GL's defaults. The
    // equation, which GlStateManager does not cache, is the last one asked for. Render thread only: GlStateManager
    // asserts that right after each hook that writes here.
    private static boolean blendOn;
    private static int srcRgb = 1, dstRgb = 0, srcAlpha = 1, dstAlpha = 0;
    private static int blendEquation = 0x8006;
    private static int colorMask = 0xF;
    private static boolean logicOp;

    private static volatile Blend capture;

    /**
     * From now on, until {@code capture(null)}, calls {@code blend} on entry to every indexed draw the frozen thread
     * makes, with the blend state asked for at that point. The draw has not gone out yet, so {@code blend} may set the
     * GL's blend state to something else for it; {@code GlStateManager}'s cache and the copy here keep what the game
     * asked for, and it is the caller's to put the GL back in line with them afterwards ({@link #blend}).
     */
    public static void capture(Blend blend) {
        FakeTime.capture = blend;
    }

    /** Tells {@code to} the blend state asked for now. */
    public static void blend(Blend to) {
        to.state(blendOn, srcRgb, dstRgb, srcAlpha, dstAlpha, blendEquation, colorMask, logicOp);
    }

    /** Reports the frozen thread's draws to {@code recorder} from now on, or to nobody after {@code null}. */
    public static void record(Recorder recorder) {
        FakeTime.recorder = recorder;
    }

    /** The recorder to report to, or null if nobody listens or the calling thread is not the frozen one. */
    private static Recorder listening() {
        Recorder r = recorder;
        return r != null && owner == Thread.currentThread() ? r : null;
    }

    // Everything below is called from patched bytecode, never by hand.

    /** Replaces {@code System.currentTimeMillis()}. */
    public static long millis() {
        if (owner != Thread.currentThread()) return System.currentTimeMillis();
        time(TIME_WALL);
        return frozenAt;
    }

    /**
     * Wraps the value {@code Util.getMillis()} returns. Outside a freeze the game keeps its own monotonic
     * milliseconds rather than wall-clock ones, since the two do not share an origin.
     */
    public static long utilMillis(long real) {
        if (owner != Thread.currentThread()) return real;
        time(TIME_UTIL_MILLIS);
        return frozenAt;
    }

    /**
     * Wraps the value {@code Level.getGameTime()} or {@code getDayTime()} returns, {@code source} saying which. The
     * client level keeps ticking in real time, so a frozen draw sees the frozen clock in game ticks instead: one
     * tick per {@link #FRAME_MILLIS}, like the atlas.
     */
    public static long levelTime(long real, int source) {
        if (owner != Thread.currentThread()) return real;
        time(source);
        return frozenAt / FRAME_MILLIS;
    }

    /**
     * Replaces {@code System.nanoTime()}, and returns the real value even to a frozen draw. Code reads nanoTime to
     * measure how long something took, and a frozen one would make every such wait endless. The read is still
     * reported, for a recorder to judge.
     */
    public static long nanos() {
        time(TIME_NANO);
        return System.nanoTime();
    }

    /** On entry to a method that reads a clock the agent does not freeze: {@code Util.getNanos()} and the like. */
    public static void time(int source) {
        Recorder r = listening();
        if (r != null) r.time(source);
    }

    public static void onUpload(Object renderedBuffer) {
        Recorder r = listening();
        if (r != null) r.upload(renderedBuffer);
    }

    public static void onDrawElements() {
        Blend c = capture;
        if (c != null && owner == Thread.currentThread()) blend(c);
        Recorder r = listening();
        if (r != null) r.drawElements();
    }

    /** {@code GlStateManager._enableBlend} with 1, {@code _disableBlend} with 0. */
    public static void onBlend(int on) {
        blendOn = on != 0;
    }

    /** {@code GlStateManager._blendFunc}, which leaves the alpha factors in the cache as they were. */
    public static void onBlendFunc(int src, int dst) {
        srcRgb = src;
        dstRgb = dst;
    }

    public static void onBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        FakeTime.srcRgb = srcRgb;
        FakeTime.dstRgb = dstRgb;
        FakeTime.srcAlpha = srcAlpha;
        FakeTime.dstAlpha = dstAlpha;
    }

    public static void onBlendEquation(int mode) {
        blendEquation = mode;
    }

    public static void onColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        colorMask = (red ? 8 : 0) | (green ? 4 : 0) | (blue ? 2 : 0) | (alpha ? 1 : 0);
    }

    /** {@code GlStateManager._enableColorLogicOp} with 1, {@code _disableColorLogicOp} with 0. */
    public static void onLogicOp(int on) {
        logicOp = on != 0;
    }

    public static void onText(Object text, float x, float y, int color, Object matrix) {
        Recorder r = listening();
        if (r != null) r.text(text, x, y, color, matrix);
    }

    public static void onItem(Object stack, Object context, Object poseStack, Object model) {
        Recorder r = listening();
        if (r != null) r.item(stack, context, poseStack, model);
    }

    public static void onFluid(Object fluid) {
        Recorder r = listening();
        if (r != null) r.fluid(fluid);
    }

    /** Checked on entry to {@code TextureManager.tick()}, which returns at once when this is false. */
    public static boolean atlasMayTick() {
        return !atlasHeld || atlasTicker == Thread.currentThread();
    }
}
