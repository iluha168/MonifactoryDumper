package com.iluha168.monifactory.faketime;

import com.iluha168.monifactory.faketime.agent.ClockAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs under the real agent (see build.gradle.kts) against a module layer built the way BootstrapLauncher builds
 * MC-BOOTSTRAP: the agent jar as an automatic module, a Minecraft stand-in that reads it, and a named module that
 * does not. The stand-ins' bodies need less stack than the hooks put in them, so a wrong max stack fails verification
 * as the class loads.
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
    private static Class<?> recorderType;
    private static Class<?> blendType;
    private static ClassLoader loader;

    /** Every fixture class the agent hooks, loaded up front so the summary has seen them all. */
    private static final List<String> HOOKED = List.of("net.minecraft.Util", "net.minecraft.world.level.Level",
            "com.mojang.blaze3d.Blaze3D", "com.mojang.blaze3d.systems.RenderSystem",
            "com.mojang.blaze3d.vertex.BufferUploader", "com.mojang.blaze3d.platform.GlStateManager",
            "net.minecraft.client.gui.Font", "net.minecraft.client.renderer.entity.ItemRenderer",
            "net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions");

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
        loader = layer.findLoader(FIXTURE);
        fakeTime = Class.forName(MODULE + ".FakeTime", true, loader);
        recorderType = Class.forName(MODULE + ".FakeTime$Recorder", true, loader);
        blendType = Class.forName(MODULE + ".FakeTime$Blend", true, loader);
        clocks = Class.forName("faketime.fixture.Clocks", true, loader);
        sealedClocks = Class.forName("faketime.fixture.sealed.Clocks", true, loader);
        textureManager = Class.forName("net.minecraft.client.renderer.texture.TextureManager", true, loader);
        for (String name : HOOKED) Class.forName(name, true, loader);
    }

    @AfterEach
    void reset() throws Exception {
        fakeTime.getMethod("record", recorderType).invoke(null, (Object) null);
        fakeTime.getMethod("capture", blendType).invoke(null, (Object) null);
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

    @Test
    void levelTimeIsFrozenInTicksOnlyOnTheFreezingThread() throws Exception {
        Object level = fixture("net.minecraft.world.level.Level").getConstructor().newInstance();
        Method gameTime = level.getClass().getMethod("m_46467_");
        Method dayTime = level.getClass().getMethod("m_46468_");
        assertEquals(123L, gameTime.invoke(level));
        assertEquals(456L, dayTime.invoke(level));

        List<List<Object>> calls = record();
        long at = FROZEN + 7 * FakeTime.FRAME_MILLIS + 13;
        call(fakeTime, "freeze", long.class, at);
        assertEquals(at / FakeTime.FRAME_MILLIS, gameTime.invoke(level));
        assertEquals(at / FakeTime.FRAME_MILLIS, dayTime.invoke(level));
        assertEquals(List.of(List.of("time", FakeTime.TIME_LEVEL_GAME), List.of("time", FakeTime.TIME_LEVEL_DAY)),
                calls);

        calls.clear();
        assertEquals(List.of(123L, 456L), onOtherThread(() -> List.of(gameTime.invoke(level), dayTime.invoke(level))));
        assertEquals(List.of(), calls, "another thread's level time reads were recorded");
    }

    @Test
    void frozenClockReadsAreRecorded() throws Exception {
        List<List<Object>> calls = record();
        call(fakeTime, "freeze", long.class, FROZEN);
        call(clocks, "wall");
        assertEquals(List.of(List.of("time", FakeTime.TIME_WALL)), calls);

        calls.clear();
        call(clocks, "util");
        assertTrue(calls.contains(List.of("time", FakeTime.TIME_UTIL_MILLIS)), calls.toString());

        call(fakeTime, "release");
        calls.clear();
        call(clocks, "wall");
        call(clocks, "util");
        assertEquals(List.of(), calls, "reads after release were recorded");
    }

    @Test
    void nanoTimeIsRealAndRecorded() throws Exception {
        List<List<Object>> calls = record();
        call(fakeTime, "freeze", long.class, FROZEN);
        long before = System.nanoTime();
        long nanos = (long) call(clocks, "nano");
        long after = System.nanoTime();
        assertTrue(before <= nanos && nanos <= after, "not the real nanoTime: " + nanos);
        assertEquals(List.of(List.of("time", FakeTime.TIME_NANO)), calls);

        calls.clear();
        call(fixture("net.minecraft.Util"), "m_137569_");
        assertEquals(List.of(List.of("time", FakeTime.TIME_UTIL_NANOS), List.of("time", FakeTime.TIME_NANO)), calls);
    }

    @Test
    void hooksReachTheRecorderOnlyFromTheFrozenThreadWhileRecording() throws Exception {
        List<List<Object>> calls = record();
        ThrowingRunnable draw = draw();

        draw.run();
        assertEquals(List.of(), calls, "recorded without a frozen clock");

        call(fakeTime, "freeze", long.class, FROZEN);
        onOtherThread(() -> {
            draw.run();
            return null;
        });
        assertEquals(List.of(), calls, "recorded another thread");

        draw.run();
        assertEquals(drawn, calls);

        fakeTime.getMethod("record", recorderType).invoke(null, (Object) null);
        calls.clear();
        draw.run();
        assertEquals(List.of(), calls, "recorded after recording stopped");
    }

    @Test
    void summaryNamesEveryHook() {
        String summary = ClockAgent.summary();
        for (String part : List.of("Util.getMillis patched", "Level time frozen", "TextureManager.tick gated",
                "Util.getNanos", "Blaze3D.getTime", "RenderSystem.getShaderGameTime", "BufferUploader.upload",
                "GlStateManager._drawElements", "GlStateManager._enableBlend", "GlStateManager._disableBlend",
                "GlStateManager._blendFunc", "GlStateManager._blendFuncSeparate", "GlStateManager._blendEquation",
                "GlStateManager._colorMask", "GlStateManager._enableColorLogicOp",
                "GlStateManager._disableColorLogicOp", "Font.drawInBatch(String)", "Font.drawInBatch(String,bidi)",
                "Font.drawInBatch(Component)", "Font.drawInBatch(FormattedCharSequence)", "ItemRenderer.render",
                "IClientFluidTypeExtensions.of(Fluid)")) {
            assertTrue(summary.contains(part), part + " missing from " + summary);
        }
        assertFalse(summary.contains("NOT"), summary);
        assertTrue(summary.matches(".*nanoTime counted in [1-9]\\d* classes.*"), summary);
    }

    @Test
    void blendStateIsWhatGlStateManagerWasAskedFor() throws Exception {
        Class<?> gl = fixture("com.mojang.blaze3d.platform.GlStateManager");
        gl.getMethod("_enableBlend").invoke(null);
        gl.getMethod("_blendFuncSeparate", int.class, int.class, int.class, int.class)
                .invoke(null, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
        gl.getMethod("_blendEquation", int.class).invoke(null, GL_FUNC_ADD);
        gl.getMethod("_colorMask", boolean.class, boolean.class, boolean.class, boolean.class)
                .invoke(null, true, true, true, true);
        gl.getMethod("_disableColorLogicOp").invoke(null);
        assertEquals(List.of(true, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO, GL_FUNC_ADD, 0xF, false),
                blend());

        // _blendFunc leaves the alpha factors alone, as it leaves them in GlStateManager's cache.
        gl.getMethod("_blendFunc", int.class, int.class).invoke(null, GL_SRC_COLOR, GL_ONE);
        gl.getMethod("_colorMask", boolean.class, boolean.class, boolean.class, boolean.class)
                .invoke(null, true, false, true, false);
        gl.getMethod("_enableColorLogicOp").invoke(null);
        gl.getMethod("_disableBlend").invoke(null);
        gl.getMethod("_blendEquation", int.class).invoke(null, GL_FUNC_REVERSE_SUBTRACT);
        assertEquals(List.of(false, GL_SRC_COLOR, GL_ONE, GL_ONE, GL_ZERO, GL_FUNC_REVERSE_SUBTRACT, 0xA, true),
                blend());

        // Any thread's calls count: only the render thread makes them, and GlStateManager asserts that itself.
        onOtherThread(() -> gl.getMethod("_enableBlend").invoke(null));
        assertEquals(true, blend().get(0));
    }

    @Test
    void aCaptureIsToldTheBlendStateBeforeEveryDrawOfTheFrozenThread() throws Exception {
        Class<?> gl = fixture("com.mojang.blaze3d.platform.GlStateManager");
        Method drawElements = gl.getMethod("_drawElements", int.class, int.class, int.class, long.class);
        gl.getMethod("_disableBlend").invoke(null);
        gl.getMethod("_blendFuncSeparate", int.class, int.class, int.class, int.class)
                .invoke(null, GL_ONE, GL_ZERO, GL_ONE, GL_ZERO);
        gl.getMethod("_blendEquation", int.class).invoke(null, GL_FUNC_ADD);
        gl.getMethod("_colorMask", boolean.class, boolean.class, boolean.class, boolean.class)
                .invoke(null, true, true, true, true);
        gl.getMethod("_disableColorLogicOp").invoke(null);
        List<List<Object>> told = Collections.synchronizedList(new ArrayList<>());
        Object capture = Proxy.newProxyInstance(loader, new Class<?>[]{blendType}, (proxy, method, args) -> {
            told.add(Arrays.asList(args));
            return null;
        });
        fakeTime.getMethod("capture", blendType).invoke(null, capture);

        drawElements.invoke(null, 4, 6, 5125, 0L);
        assertEquals(List.of(), told, "told without a frozen clock");

        call(fakeTime, "freeze", long.class, FROZEN);
        onOtherThread(() -> drawElements.invoke(null, 4, 6, 5125, 0L));
        assertEquals(List.of(), told, "told of another thread's draw");

        drawElements.invoke(null, 4, 6, 5125, 0L);
        gl.getMethod("_enableBlend").invoke(null);
        gl.getMethod("_blendFunc", int.class, int.class).invoke(null, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        assertEquals(1, told.size(), "told of a state change rather than a draw");
        drawElements.invoke(null, 4, 6, 5125, 0L);
        assertEquals(List.of(
                List.of(false, GL_ONE, GL_ZERO, GL_ONE, GL_ZERO, GL_FUNC_ADD, 0xF, false),
                List.of(true, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO, GL_FUNC_ADD, 0xF, false)), told);

        fakeTime.getMethod("capture", blendType).invoke(null, (Object) null);
        told.clear();
        drawElements.invoke(null, 4, 6, 5125, 0L);
        assertEquals(List.of(), told, "told after the capture ended");
    }

    private static final int GL_ZERO = 0, GL_ONE = 1, GL_SRC_COLOR = 0x0300, GL_SRC_ALPHA = 0x0302,
            GL_ONE_MINUS_SRC_ALPHA = 0x0303, GL_FUNC_ADD = 0x8006, GL_FUNC_REVERSE_SUBTRACT = 0x800B;

    /** The blend state FakeTime holds now, as {@code Blend.state} gets it. */
    private static List<Object> blend() throws Exception {
        List<Object> state = new ArrayList<>();
        Object to = Proxy.newProxyInstance(loader, new Class<?>[]{blendType}, (proxy, method, args) -> {
            state.addAll(Arrays.asList(args));
            return null;
        });
        fakeTime.getMethod("blend", blendType).invoke(null, to);
        return state;
    }

    /** What {@link #draw} should record, in order. */
    private List<List<Object>> drawn;

    /**
     * One call to every hooked method but the clocks, with arguments the recorder should get back as they are. The
     * String and Component overloads of drawInBatch call another overload, as the real ones do.
     */
    private ThrowingRunnable draw() throws Exception {
        Class<?> buffer = fixture("com.mojang.blaze3d.vertex.BufferBuilder$RenderedBuffer");
        Object rendered = buffer.getConstructor().newInstance();
        Method upload = fixture("com.mojang.blaze3d.vertex.BufferUploader").getMethod("m_231202_", buffer);
        Method drawElements = fixture("com.mojang.blaze3d.platform.GlStateManager")
                .getMethod("_drawElements", int.class, int.class, int.class, long.class);

        Class<?> font = fixture("net.minecraft.client.gui.Font");
        Object fontInstance = font.getConstructor().newInstance();
        Class<?> matrixType = fixture("org.joml.Matrix4f");
        Object matrix = matrixType.getConstructor().newInstance();
        Class<?> buffers = fixture("net.minecraft.client.renderer.MultiBufferSource");
        Class<?> mode = fixture("net.minecraft.client.gui.Font$DisplayMode");
        Object normal = mode.getEnumConstants()[0];
        Class<?> component = fixture("net.minecraft.network.chat.Component");
        Object text = component.getConstructor().newInstance();
        Method drawString = font.getMethod("m_271703_", String.class, float.class, float.class, int.class,
                boolean.class, matrixType, buffers, mode, int.class, int.class);
        Method drawComponent = font.getMethod("m_272077_", component, float.class, float.class, int.class,
                boolean.class, matrixType, buffers, mode, int.class, int.class);

        Class<?> itemRenderer = fixture("net.minecraft.client.renderer.entity.ItemRenderer");
        Object items = itemRenderer.getConstructor().newInstance();
        Class<?> stackType = fixture("net.minecraft.world.item.ItemStack");
        Object stack = stackType.getConstructor().newInstance();
        Class<?> contextType = fixture("net.minecraft.world.item.ItemDisplayContext");
        Object context = contextType.getEnumConstants()[0];
        Class<?> poseType = fixture("com.mojang.blaze3d.vertex.PoseStack");
        Object pose = poseType.getConstructor().newInstance();
        Class<?> modelType = fixture("net.minecraft.client.resources.model.BakedModel");
        Object model = Proxy.newProxyInstance(loader, new Class<?>[]{modelType}, (proxy, method, args) -> null);
        Method renderItem = itemRenderer.getMethod("m_115143_", stackType, contextType, boolean.class, poseType,
                buffers, int.class, int.class, modelType);

        Class<?> fluidType = fixture("net.minecraft.world.level.material.Fluid");
        Object fluid = fluidType.getConstructor().newInstance();
        Method fluidLook = fixture("net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions")
                .getMethod("of", fluidType);

        Method glfwTime = fixture("com.mojang.blaze3d.Blaze3D").getMethod("m_83640_");
        Method shaderTime = fixture("com.mojang.blaze3d.systems.RenderSystem").getMethod("getShaderGameTime");

        drawn = List.of(
                List.of("upload", rendered),
                List.of("drawElements"),
                List.of("text", "hi", 1f, 2f, 0xABCDEF, matrix),
                List.of("text", "hi", 1f, 2f, 0xABCDEF, matrix),
                List.of("text", text, 3f, 4f, 5, matrix),
                Arrays.asList("text", null, 3f, 4f, 5, matrix),
                List.of("item", stack, context, pose, model),
                List.of("fluid", fluid),
                List.of("time", FakeTime.TIME_GLFW),
                List.of("time", FakeTime.TIME_SHADER_GAME));
        return () -> {
            upload.invoke(null, rendered);
            drawElements.invoke(null, 4, 6, 5125, 0L);
            drawString.invoke(fontInstance, "hi", 1f, 2f, 0xABCDEF, true, matrix, null, normal, 0, 0xF000F0);
            drawComponent.invoke(fontInstance, text, 3f, 4f, 5, false, matrix, null, normal, 0, 0xF000F0);
            renderItem.invoke(items, stack, context, false, pose, null, 0xF000F0, 0, model);
            fluidLook.invoke(null, fluid);
            glfwTime.invoke(null);
            shaderTime.invoke(null);
        };
    }

    /**
     * Registers a recorder with the layer's FakeTime and returns what it is told: the method name, then the
     * arguments. The list is safe to read from the test thread, whichever thread the recorder was called on.
     */
    private static List<List<Object>> record() throws Exception {
        List<List<Object>> calls = Collections.synchronizedList(new ArrayList<>());
        Object recorder = Proxy.newProxyInstance(loader, new Class<?>[]{recorderType}, (proxy, method, args) -> {
            List<Object> call = new ArrayList<>();
            call.add(method.getName());
            if (args != null) call.addAll(Arrays.asList(args));
            calls.add(call);
            return null;
        });
        fakeTime.getMethod("record", recorderType).invoke(null, recorder);
        return calls;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static <T> T onOtherThread(ThrowingSupplier<T> body) throws Exception {
        List<T> result = new ArrayList<>(1);
        List<Exception> failure = new ArrayList<>(1);
        Thread other = new Thread(() -> {
            try {
                result.add(body.get());
            } catch (Exception e) {
                failure.add(e);
            }
        });
        other.start();
        other.join();
        if (!failure.isEmpty()) throw failure.get(0);
        return result.get(0);
    }

    private static Class<?> fixture(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
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
