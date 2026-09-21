package com.iluha168.monifactory.faketime;

import com.iluha168.monifactory.faketime.agent.ClockAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs under the real agent (see build.gradle.kts) against a module layer built the way BootstrapLauncher builds
 * MC-BOOTSTRAP: the agent jar as an automatic module, a Minecraft stand-in that reads it, and a named module that
 * does not.
 */
class ClockAgentTest {
    private static final String MODULE = "com.iluha168.monifactory.faketime";
    private static final String FIXTURE = "faketime.fixture";
    private static final String SEALED = "faketime.fixture.sealed";
    /** Far enough from now that no real clock read can pass for it. */
    private static final long FROZEN = 1_000_000L;

    private static Class<?> fakeTime;
    private static Class<?> clocks;
    private static Class<?> sealedClocks;
    private static Class<?> textureManager;

    @BeforeAll
    static void layer() throws Exception {
        ModuleFinder finder = ModuleFinder.of(
                Path.of(System.getProperty("faketime.test.agent")),
                Path.of(System.getProperty("faketime.test.fixture")),
                Path.of(System.getProperty("faketime.test.sealedFixture")));
        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration().resolve(finder, ModuleFinder.of(), Set.of(MODULE, FIXTURE, SEALED));
        // The platform loader as parent, so nothing in the layer can fall back to the test's own class path.
        ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader());
        ClassLoader loader = layer.findLoader(FIXTURE);
        fakeTime = Class.forName(MODULE + ".FakeTime", true, loader);
        clocks = Class.forName("faketime.fixture.Clocks", true, loader);
        sealedClocks = Class.forName("faketime.fixture.sealed.Clocks", true, loader);
        textureManager = Class.forName("net.minecraft.client.renderer.texture.TextureManager", true, loader);
    }

    @AfterEach
    void reset() throws Exception {
        call(fakeTime, "release");
        call(fakeTime, "holdAtlas", true, false);
        FakeTime.release();
    }

    @Test
    void frozenClockStepsFrameByFrame() throws Exception {
        for (int frame = 0; frame < 4; frame++) {
            long at = FROZEN + frame * FakeTime.FRAME_MILLIS;
            call(fakeTime, "freeze", long.class, at);
            assertEquals(at, (long) call(clocks, "wall"), "System.currentTimeMillis() at frame " + frame);
            assertEquals(at, (long) call(clocks, "util"), "Util.getMillis() at frame " + frame);
        }
        assertTrue(ClockAgent.patchedIn(FIXTURE) >= 1, ClockAgent.summary());
    }

    @Test
    void releasedClockIsTheRealOne() throws Exception {
        call(fakeTime, "freeze", long.class, FROZEN);
        call(fakeTime, "release");
        assertNearRealWall((long) call(clocks, "wall"));
        // Util keeps its own monotonic origin, not the wall clock's.
        long nanos = System.nanoTime() / 1_000_000L;
        assertTrue(Math.abs((long) call(clocks, "util") - nanos) < 10_000);
    }

    @Test
    void onlyTheFreezingThreadSeesTheFrozenClock() throws Exception {
        call(fakeTime, "freeze", long.class, FROZEN);
        AtomicLong elsewhere = new AtomicLong();
        Thread other = new Thread(() -> {
            try {
                elsewhere.set((long) call(clocks, "wall"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        other.start();
        other.join();
        assertNearRealWall(elsewhere.get());
        assertEquals(FROZEN, (long) call(clocks, "wall"));
    }

    @Test
    void moduleThatCannotReadTheClockIsLeftAlone() throws Exception {
        call(fakeTime, "freeze", long.class, FROZEN);
        assertNearRealWall((long) call(sealedClocks, "wall"));
        assertEquals(1, ClockAgent.unreadableIn(SEALED), ClockAgent.summary());
        assertEquals(0, ClockAgent.patchedIn(SEALED), ClockAgent.summary());
    }

    @Test
    void callSitesNeverLinkToTheAgentJarsOwnCopy() throws Exception {
        // This class sees the unnamed FakeTime off the class path, the one -javaagent adds.
        assertFalse(FakeTime.class.getModule().isNamed());
        FakeTime.freeze(FROZEN);
        assertNearRealWall((long) call(clocks, "wall"));
        assertEquals(0, ClockAgent.patchedIn("<unnamed>"), ClockAgent.summary());
    }

    @Test
    void heldAtlasTicksOnlyWhenTheRendererSaysSo() throws Exception {
        Object manager = textureManager.getConstructor().newInstance();
        Method tick = textureManager.getMethod("m_7673_");
        Method ticks = textureManager.getMethod("ticks");

        tick.invoke(manager);
        assertEquals(1, ticks.invoke(manager));

        call(fakeTime, "holdAtlas", true, true);
        tick.invoke(manager);
        assertEquals(1, ticks.invoke(manager), "the game loop ticked a held atlas");

        Runnable byRenderer = () -> {
            try {
                tick.invoke(manager);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        };
        fakeTime.getMethod("tickAtlas", Runnable.class).invoke(null, byRenderer);
        assertEquals(2, ticks.invoke(manager));

        tick.invoke(manager);
        assertEquals(2, ticks.invoke(manager), "the hold did not come back after tickAtlas");

        call(fakeTime, "holdAtlas", true, false);
        tick.invoke(manager);
        assertEquals(3, ticks.invoke(manager));
    }

    private static void assertNearRealWall(long millis) {
        assertTrue(Math.abs(millis - System.currentTimeMillis()) < 10_000, "not the real clock: " + millis);
    }

    private static Object call(Class<?> owner, String method) throws Exception {
        return owner.getMethod(method).invoke(null);
    }

    private static Object call(Class<?> owner, String method, boolean flag, boolean value) throws Exception {
        return owner.getMethod(method, boolean.class).invoke(null, value);
    }

    private static Object call(Class<?> owner, String method, Class<?> type, Object value) throws Exception {
        return owner.getMethod(method, type).invoke(null, value);
    }
}
