package com.iluha168.monifactory.faketime.agent;

import com.iluha168.monifactory.faketime.FakeTime;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Points the game's clocks at {@link FakeTime}, and reports what a frozen draw does to its recorder.
 * <p>
 * Animations in recipe views run off the wall clock, not game ticks: EMI's arrows and tag cycling read
 * {@code System.currentTimeMillis()}, LDLib's progress arrows and the enchantment glint read {@code Util.getMillis()},
 * and animated sprites advance in {@code TextureManager.tick()}. The client level's game and day time keep ticking
 * too. Four rewrites freeze them:
 * <ul>
 * <li>every {@code System.currentTimeMillis()} call site, in every class that can link to FakeTime;</li>
 * <li>the value {@code Util.getMillis()} returns;</li>
 * <li>the values {@code Level.getGameTime()} and {@code getDayTime()} return;</li>
 * <li>a gate on entry to {@code TextureManager.tick()}.</li>
 * </ul>
 * The layered renderer also needs to know what a draw reads and sends to the GPU, to tell from one draw whether it
 * can look different at another time (see {@link FakeTime.Recorder}). Two more rewrites report that and change
 * nothing the game sees:
 * <ul>
 * <li>every {@code System.nanoTime()} call site, like currentTimeMillis, but still returning the real time;</li>
 * <li>a call on entry to the clocks left running ({@code Util.getNanos()}, {@code Blaze3D.getTime()},
 * {@code RenderSystem.getShaderGameTime()}) and to what draws: {@code BufferUploader.upload},
 * {@code GlStateManager._drawElements}, {@code Font.drawInBatch} and {@code ItemRenderer.render}.</li>
 * </ul>
 * And a layer drawn once has to have its alpha channel set up by the renderer right before each of its draws (see
 * {@link FakeTime#capture}), from the blend state the game asked for. So a call on entry to each
 * {@code GlStateManager} method that sets what blending writes keeps FakeTime's copy of it: blending on and off, the
 * factors, the equation, the colour mask and the colour logic op. These change nothing either.
 * There is no list of mod classes. Naming the classes to patch means naming mods, and such a list is only as
 * complete as the last search for one. Instead the agent patches whatever can link: a rewritten call site references
 * FakeTime, so it goes only into classes whose module can read FakeTime's. Forge runs several module layers, and
 * an explicit module such as log4j's API reads only what it requires; a patched call there cannot link, and the
 * prototype showed that kills the JVM before Forge starts. {@link Module#canRead} is the test, and it needs no upkeep
 * when the pack changes. The Minecraft methods above are the only ones named.
 * <p>
 * Classes that are not named here and never mention currentTimeMillis or nanoTime are passed through after a raw
 * byte scan, without ASM ever parsing them.
 */
public final class ClockAgent implements ClassFileTransformer {
    private static final String FAKE_TIME = "com/iluha168/monifactory/faketime/FakeTime";
    private static final String FAKE_TIME_CLASS = FAKE_TIME.replace('/', '.');
    private static final String OWN_PACKAGE = "com/iluha168/monifactory/faketime/";

    private static final byte[] CURRENT_TIME_MILLIS = "currentTimeMillis".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NANO_TIME = "nanoTime".getBytes(StandardCharsets.UTF_8);

    /**
     * A call the agent adds to one Minecraft method. An entry hook runs before the method's first instruction and
     * returns void. A return hook runs in front of every return: it takes the value being returned and gives back one
     * of the same type. {@code args} are what the call takes besides that value, in order, each a pair of ints: a
     * load opcode and a local slot, or {@link Opcodes#BIPUSH} and a constant.
     */
    private record Hook(String label, String owner, Set<String> names, String descriptor, boolean atReturn,
                        String call, String callDescriptor, int... args) {
        /** Operand stack slots the pushed arguments take. */
        int argSlots() {
            int slots = 0;
            for (int i = 0; i < args.length; i += 2) {
                slots += args[i] == Opcodes.LLOAD || args[i] == Opcodes.DLOAD ? 2 : 1;
            }
            return slots;
        }
    }

    private static Hook atEntry(String label, String owner, Set<String> names, String descriptor, String call,
                                String callDescriptor, int... args) {
        return new Hook(label, owner, names, descriptor, false, call, callDescriptor, args);
    }

    private static Hook atReturn(String label, String owner, Set<String> names, String descriptor, String call,
                                 String callDescriptor, int... args) {
        return new Hook(label, owner, names, descriptor, true, call, callDescriptor, args);
    }

    // The game runs on SRG member names. Class names are the official ones in 1.20.1. These SRG names have been
    // stable since 1.17, but they are tied to the Minecraft version like any SRG name. The mojmap names are what the
    // same members are called on the dev road, where the dev-versus-production pixel diff boots with this agent; the
    // descriptor check keeps either name from matching anything else in the class. GlStateManager and RenderSystem
    // are not obfuscated, so their members have one name.
    private static final String UTIL = "net/minecraft/Util";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String TEXTURE_MANAGER = "net/minecraft/client/renderer/texture/TextureManager";
    private static final Set<String> TEXTURE_MANAGER_TICK = Set.of("m_7673_", "tick");

    private static final String UTIL_GET_MILLIS = "Util.getMillis";
    private static final Set<String> LEVEL_TIME = Set.of("Level.getGameTime", "Level.getDayTime");

    private static final String ON_TIME = "(I)V";
    private static final String GL_STATE = "com/mojang/blaze3d/platform/GlStateManager";
    private static final String FONT = "net/minecraft/client/gui/Font";
    /** What every drawInBatch overload takes after the text. The one with a bidi flag takes it after these. */
    private static final String DRAW_IN_BATCH = "FFIZLorg/joml/Matrix4f;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II";
    private static final String ON_TEXT = "(Ljava/lang/Object;FFILjava/lang/Object;)V";
    /** text, x, y, color and matrix; slot 5 is the drop shadow flag. */
    private static final int[] TEXT_ARGS = {Opcodes.ALOAD, 1, Opcodes.FLOAD, 2, Opcodes.FLOAD, 3, Opcodes.ILOAD, 4,
            Opcodes.ALOAD, 6};

    // Return hooks freeze a clock, entry hooks only report to FakeTime. The TIME_ constants are compile-time
    // constants, so naming them here loads no FakeTime into the agent's own class loader.
    private static final List<Hook> HOOKS = List.of(
            atReturn(UTIL_GET_MILLIS, UTIL, Set.of("m_137550_", "getMillis"), "()J", "utilMillis", "(J)J"),
            atReturn("Level.getGameTime", LEVEL, Set.of("m_46467_", "getGameTime"), "()J", "levelTime", "(JI)J",
                    Opcodes.BIPUSH, FakeTime.TIME_LEVEL_GAME),
            atReturn("Level.getDayTime", LEVEL, Set.of("m_46468_", "getDayTime"), "()J", "levelTime", "(JI)J",
                    Opcodes.BIPUSH, FakeTime.TIME_LEVEL_DAY),
            atEntry("Util.getNanos", UTIL, Set.of("m_137569_", "getNanos"), "()J", "time", ON_TIME,
                    Opcodes.BIPUSH, FakeTime.TIME_UTIL_NANOS),
            atEntry("Blaze3D.getTime", "com/mojang/blaze3d/Blaze3D", Set.of("m_83640_", "getTime"), "()D", "time",
                    ON_TIME, Opcodes.BIPUSH, FakeTime.TIME_GLFW),
            atEntry("RenderSystem.getShaderGameTime", "com/mojang/blaze3d/systems/RenderSystem",
                    Set.of("getShaderGameTime"), "()F", "time", ON_TIME, Opcodes.BIPUSH, FakeTime.TIME_SHADER_GAME),
            atEntry("BufferUploader.upload", "com/mojang/blaze3d/vertex/BufferUploader", Set.of("m_231213_", "upload"),
                    "(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)"
                            + "Lcom/mojang/blaze3d/vertex/VertexBuffer;",
                    "onUpload", "(Ljava/lang/Object;)V", Opcodes.ALOAD, 0),
            atEntry("GlStateManager._drawElements", GL_STATE, Set.of("_drawElements"), "(IIIJ)V", "onDrawElements",
                    "()V"),
            atEntry("GlStateManager._enableBlend", GL_STATE, Set.of("_enableBlend"), "()V", "onBlend", "(I)V",
                    Opcodes.BIPUSH, 1),
            atEntry("GlStateManager._disableBlend", GL_STATE, Set.of("_disableBlend"), "()V", "onBlend", "(I)V",
                    Opcodes.BIPUSH, 0),
            atEntry("GlStateManager._blendFunc", GL_STATE, Set.of("_blendFunc"), "(II)V", "onBlendFunc", "(II)V",
                    Opcodes.ILOAD, 0, Opcodes.ILOAD, 1),
            atEntry("GlStateManager._blendFuncSeparate", GL_STATE, Set.of("_blendFuncSeparate"), "(IIII)V",
                    "onBlendFuncSeparate", "(IIII)V", Opcodes.ILOAD, 0, Opcodes.ILOAD, 1, Opcodes.ILOAD, 2,
                    Opcodes.ILOAD, 3),
            atEntry("GlStateManager._blendEquation", GL_STATE, Set.of("_blendEquation"), "(I)V", "onBlendEquation",
                    "(I)V", Opcodes.ILOAD, 0),
            atEntry("GlStateManager._colorMask", GL_STATE, Set.of("_colorMask"), "(ZZZZ)V", "onColorMask", "(ZZZZ)V",
                    Opcodes.ILOAD, 0, Opcodes.ILOAD, 1, Opcodes.ILOAD, 2, Opcodes.ILOAD, 3),
            atEntry("GlStateManager._enableColorLogicOp", GL_STATE, Set.of("_enableColorLogicOp"), "()V", "onLogicOp",
                    "(I)V", Opcodes.BIPUSH, 1),
            atEntry("GlStateManager._disableColorLogicOp", GL_STATE, Set.of("_disableColorLogicOp"), "()V",
                    "onLogicOp", "(I)V", Opcodes.BIPUSH, 0),
            atEntry("Font.drawInBatch(String)", FONT, Set.of("m_271703_", "drawInBatch"),
                    "(Ljava/lang/String;" + DRAW_IN_BATCH + ")I", "onText", ON_TEXT, TEXT_ARGS),
            atEntry("Font.drawInBatch(String,bidi)", FONT, Set.of("m_272078_", "drawInBatch"),
                    "(Ljava/lang/String;" + DRAW_IN_BATCH + "Z)I", "onText", ON_TEXT, TEXT_ARGS),
            atEntry("Font.drawInBatch(Component)", FONT, Set.of("m_272077_", "drawInBatch"),
                    "(Lnet/minecraft/network/chat/Component;" + DRAW_IN_BATCH + ")I", "onText", ON_TEXT, TEXT_ARGS),
            atEntry("Font.drawInBatch(FormattedCharSequence)", FONT, Set.of("m_272191_", "drawInBatch"),
                    "(Lnet/minecraft/util/FormattedCharSequence;" + DRAW_IN_BATCH + ")I", "onText", ON_TEXT, TEXT_ARGS),
            atEntry("ItemRenderer.render", "net/minecraft/client/renderer/entity/ItemRenderer",
                    Set.of("m_115143_", "render"),
                    "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Z"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II"
                            + "Lnet/minecraft/client/resources/model/BakedModel;)V",
                    "onItem", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                    Opcodes.ALOAD, 1, Opcodes.ALOAD, 2, Opcodes.ALOAD, 4));
    private static final Map<String, List<Hook>> HOOKS_BY_OWNER =
            HOOKS.stream().collect(Collectors.groupingBy(Hook::owner));

    private static final AtomicInteger scanned = new AtomicInteger();
    private static final Map<String, AtomicInteger> patchedByModule = new ConcurrentHashMap<>();
    private static final AtomicInteger nanoPatched = new AtomicInteger();
    private static final Map<String, AtomicInteger> unreadableByModule = new ConcurrentHashMap<>();
    private static final Set<String> hooksPatched = ConcurrentHashMap.newKeySet();
    private static volatile boolean atlasPatched;

    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClockAgent());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println(summary()), "faketime-summary"));
        System.out.println("[faketime] clock agent installed");
    }

    /** What the agent did so far, one line. Printed at exit. */
    public static String summary() {
        List<String> entryHooks = HOOKS.stream().filter(h -> !h.atReturn()).map(Hook::label).toList();
        List<String> missing = entryHooks.stream().filter(label -> !hooksPatched.contains(label)).toList();
        return "[faketime] scanned=" + scanned.get()
                + " currentTimeMillis patched in " + total(patchedByModule) + " classes " + sorted(patchedByModule)
                + ", nanoTime counted in " + nanoPatched.get() + " classes"
                + ", skipped as unreadable " + total(unreadableByModule) + " " + sorted(unreadableByModule)
                + ", Util.getMillis " + (hooksPatched.contains(UTIL_GET_MILLIS) ? "patched" : "NOT patched")
                + ", Level time " + (hooksPatched.containsAll(LEVEL_TIME) ? "frozen" : "NOT frozen")
                + ", TextureManager.tick " + (atlasPatched ? "gated" : "NOT gated")
                + ", entry hooks " + entryHooks.stream().filter(hooksPatched::contains).toList()
                + (missing.isEmpty() ? "" : ", NOT hooked " + missing);
    }

    /** How many classes in {@code module} had a clock read redirected. For tests. */
    public static int patchedIn(String module) {
        AtomicInteger count = patchedByModule.get(module);
        return count == null ? 0 : count.get();
    }

    /** How many classes in {@code module} read the clock but could not link to FakeTime. For tests. */
    public static int unreadableIn(String module) {
        AtomicInteger count = unreadableByModule.get(module);
        return count == null ? 0 : count.get();
    }

    /**
     * The JVM calls only this overload for classes in named modules, and the module decides whether a rewritten call
     * site can link.
     */
    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
        try {
            if (className == null || redefined != null) return null;
            if (className.startsWith("java/") || className.startsWith("javax/") || className.startsWith("jdk/")
                    || className.startsWith("sun/") || className.startsWith("com/sun/")
                    || className.startsWith("org/objectweb/asm/") || className.startsWith(OWN_PACKAGE)) return null;

            List<Hook> hooks = HOOKS_BY_OWNER.getOrDefault(className, List.of());
            boolean atlas = className.equals(TEXTURE_MANAGER);
            boolean clock = contains(bytes, CURRENT_TIME_MILLIS) || contains(bytes, NANO_TIME);
            if (!clock && hooks.isEmpty() && !atlas) return null;

            String moduleName = module == null || !module.isNamed() ? "<unnamed>" : module.getName();
            Module target = fakeTimeModule(loader);
            if (target == null) return null;
            if (module != null && module.isNamed() && !module.canRead(target)) {
                unreadableByModule.computeIfAbsent(moduleName, k -> new AtomicInteger()).incrementAndGet();
                return null;
            }

            scanned.incrementAndGet();
            boolean[] hits = new boolean[3];
            byte[] patched = rewrite(bytes, hooks, atlas, hits);
            if (!hits[ANY]) return null;
            if (hits[WALL]) patchedByModule.computeIfAbsent(moduleName, k -> new AtomicInteger()).incrementAndGet();
            if (hits[NANO]) nanoPatched.incrementAndGet();
            return patched;
        } catch (Throwable t) {
            // Stock bytes: a clock read is never worth a failed boot.
            return null;
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                            byte[] bytes) {
        return transform(redefined == null ? null : redefined.getModule(), loader, className, redefined, domain, bytes);
    }

    private static final int ANY = 0, WALL = 1, NANO = 2;

    /**
     * Sets {@code hits[ANY]} if anything changed, {@code hits[WALL]} if a currentTimeMillis call did and
     * {@code hits[NANO]} if a nanoTime call did.
     * <p>
     * The writer does not compute max stack sizes. Every rewrite knows what it adds to the stack, and computing them
     * would run a data flow analysis over every method of every class that so much as calls nanoTime.
     */
    private static byte[] rewrite(byte[] bytes, List<Hook> hooks, boolean atlas, boolean[] hits) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (visitor == null) return null;
                boolean tick = atlas && TEXTURE_MANAGER_TICK.contains(name) && descriptor.equals("()V");
                Hook hook = null;
                for (Hook h : hooks) {
                    if (h.names().contains(name) && h.descriptor().equals(descriptor)) hook = h;
                }
                Hook entry = hook != null && !hook.atReturn() ? hook : null;
                Hook exit = hook != null && hook.atReturn() ? hook : null;
                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        // Loads and a void static call: the stack is empty again after, and no frame changes.
                        if (entry != null) call(entry);
                        if (tick) {
                            // if (!FakeTime.atlasMayTick()) return;
                            // The frame at the label is the method's entry frame, so F_SAME describes it whatever
                            // frames follow, and no frame has to be recomputed.
                            Label go = new Label();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, FAKE_TIME, "atlasMayTick", "()Z", false);
                            super.visitJumpInsn(Opcodes.IFNE, go);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(go);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            hits[ANY] = true;
                            atlasPatched = true;
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        // The value being returned and the arguments go in, a value of the same type comes back out.
                        if (exit != null && opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) call(exit);
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        int stack = maxStack;
                        // Entry arguments go on the empty stack. Return arguments go on top of the value being
                        // returned and whatever lies under it, which maxStack already allowed for.
                        if (entry != null) stack = Math.max(stack, entry.argSlots());
                        if (exit != null) stack += exit.argSlots();
                        // The gate's boolean.
                        if (tick) stack = Math.max(stack, 1);
                        super.visitMaxs(stack, maxLocals);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        boolean system = opcode == Opcodes.INVOKESTATIC && owner.equals("java/lang/System")
                                && desc.equals("()J");
                        if (system && method.equals("currentTimeMillis")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, FAKE_TIME, "millis", "()J", false);
                            hits[ANY] = hits[WALL] = true;
                        } else if (system && method.equals("nanoTime")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, FAKE_TIME, "nanos", "()J", false);
                            hits[ANY] = hits[NANO] = true;
                        } else {
                            super.visitMethodInsn(opcode, owner, method, desc, itf);
                        }
                    }

                    private void call(Hook hook) {
                        int[] args = hook.args();
                        for (int i = 0; i < args.length; i += 2) {
                            if (args[i] == Opcodes.BIPUSH) super.visitIntInsn(Opcodes.BIPUSH, args[i + 1]);
                            else super.visitVarInsn(args[i], args[i + 1]);
                        }
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, FAKE_TIME, hook.call(), hook.callDescriptor(),
                                false);
                        hits[ANY] = true;
                        hooksPatched.add(hook.label());
                    }
                };
            }
        }, 0);
        return hits[ANY] ? writer.toByteArray() : null;
    }

    private static final Map<ClassLoader, Object> resolved = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Object MISSING = new Object();

    /**
     * The module of the FakeTime that {@code loader} sees, or null if no call site there may link to it. An unnamed
     * FakeTime is the agent jar's own copy on the app class path. Linking to it would make a second, separate clock,
     * so only a FakeTime in a named module counts.
     */
    private static Module fakeTimeModule(ClassLoader loader) {
        if (loader == null) return null; // the boot loader never sees FakeTime
        Object found = resolved.get(loader);
        if (found == null) {
            try {
                found = Class.forName(FAKE_TIME_CLASS, false, loader).getModule();
            } catch (Throwable t) {
                found = MISSING;
            }
            resolved.put(loader, found);
        }
        return found instanceof Module module && module.isNamed() ? module : null;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static int total(Map<String, AtomicInteger> counts) {
        return counts.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    private static Map<String, Integer> sorted(Map<String, AtomicInteger> counts) {
        Map<String, Integer> out = new TreeMap<>();
        counts.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
