package com.iluha168.monifactory.faketime;

/**
 * The clock that animations read while the renderer draws.
 * <p>
 * {@link com.iluha168.monifactory.faketime.agent.ClockAgent} points every {@code System.currentTimeMillis()}
 * call site it can link, and the return value of Minecraft's {@code Util.getMillis()}, at this class. Until a
 * thread calls {@link #freeze}, all of them read the real clock, so boot, resource reloads and the game loop run
 * exactly as they would without the agent.
 * <p>
 * Only the thread that froze the clock sees the frozen value. The renderer draws on the render thread, and every
 * other thread in the game (workers, netty, mod watchdogs) keeps real time for the hours a batch takes. The frozen
 * thread really does see only the frozen clock: log4j-core is patched too, so its log lines carry the fake time.
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

    // Everything below is called from patched bytecode, never by hand.

    /** Replaces {@code System.currentTimeMillis()}. */
    public static long millis() {
        return owner == Thread.currentThread() ? frozenAt : System.currentTimeMillis();
    }

    /**
     * Wraps the value {@code Util.getMillis()} returns. Outside a freeze the game keeps its own monotonic
     * milliseconds rather than wall-clock ones, since the two do not share an origin.
     */
    public static long utilMillis(long real) {
        return owner == Thread.currentThread() ? frozenAt : real;
    }

    /** Checked on entry to {@code TextureManager.tick()}, which returns at once when this is false. */
    public static boolean atlasMayTick() {
        return !atlasHeld || atlasTicker == Thread.currentThread();
    }
}
