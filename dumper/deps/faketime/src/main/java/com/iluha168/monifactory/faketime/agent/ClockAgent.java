package com.iluha168.monifactory.faketime.agent;

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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Points the game's clocks at {@link com.iluha168.monifactory.faketime.FakeTime}.
 * <p>
 * Animations in recipe views run off the wall clock, not game ticks: EMI's arrows and tag cycling read
 * {@code System.currentTimeMillis()}, LDLib's progress arrows and the enchantment glint read {@code Util.getMillis()},
 * and animated sprites advance in {@code TextureManager.tick()}. Three rewrites cover them:
 * <ul>
 * <li>every {@code System.currentTimeMillis()} call site, in every class that can link to FakeTime;</li>
 * <li>the value {@code Util.getMillis()} returns;</li>
 * <li>a gate on entry to {@code TextureManager.tick()}.</li>
 * </ul>
 * There is no list of mod classes. Naming the classes to patch means naming mods, and such a list is only as
 * complete as the last search for one. Instead the agent patches whatever can link: a rewritten call site references
 * FakeTime, so it goes only into classes whose module can read FakeTime's. Forge runs several module layers, and
 * an explicit module such as log4j's API reads only what it requires; a patched call there cannot link, and the
 * prototype showed that kills the JVM before Forge starts. {@link Module#canRead} is the test, and it needs no upkeep
 * when the pack changes.
 * <p>
 * Classes that never mention a clock are passed through after a raw byte scan, without ASM ever parsing them.
 */
public final class ClockAgent implements ClassFileTransformer {
    private static final String FAKE_TIME = "com/iluha168/monifactory/faketime/FakeTime";
    private static final String FAKE_TIME_CLASS = FAKE_TIME.replace('/', '.');
    private static final String OWN_PACKAGE = "com/iluha168/monifactory/faketime/";

    // The game runs on SRG member names. Class names are the official ones in 1.20.1. These SRG names have been
    // stable since 1.17, but they are tied to the Minecraft version like any SRG name. The mojmap names are what the
    // same members are called on the dev road, where the dev-versus-production pixel diff boots with this agent; the
    // descriptor check keeps either name from matching anything else in the class.
    private static final String UTIL = "net/minecraft/Util";
    private static final Set<String> UTIL_GET_MILLIS = Set.of("m_137550_", "getMillis");
    private static final String TEXTURE_MANAGER = "net/minecraft/client/renderer/texture/TextureManager";
    private static final Set<String> TEXTURE_MANAGER_TICK = Set.of("m_7673_", "tick");

    private static final byte[] CURRENT_TIME_MILLIS = "currentTimeMillis".getBytes(StandardCharsets.UTF_8);

    private static final AtomicInteger scanned = new AtomicInteger();
    private static final Map<String, AtomicInteger> patchedByModule = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> unreadableByModule = new ConcurrentHashMap<>();
    private static volatile boolean utilPatched;
    private static volatile boolean atlasPatched;

    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClockAgent());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println(summary()), "faketime-summary"));
        System.out.println("[faketime] clock agent installed");
    }

    /** What the agent did so far, one line. Printed at exit. */
    public static String summary() {
        return "[faketime] scanned=" + scanned.get()
                + " currentTimeMillis patched in " + total(patchedByModule) + " classes " + sorted(patchedByModule)
                + ", skipped as unreadable " + total(unreadableByModule) + " " + sorted(unreadableByModule)
                + ", Util.getMillis " + (utilPatched ? "patched" : "NOT patched")
                + ", TextureManager.tick " + (atlasPatched ? "gated" : "NOT gated");
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

            boolean util = className.equals(UTIL);
            boolean atlas = className.equals(TEXTURE_MANAGER);
            boolean wall = contains(bytes, CURRENT_TIME_MILLIS);
            if (!wall && !util && !atlas) return null;

            String moduleName = module == null || !module.isNamed() ? "<unnamed>" : module.getName();
            Module target = fakeTimeModule(loader);
            if (target == null) return null;
            if (module != null && module.isNamed() && !module.canRead(target)) {
                unreadableByModule.computeIfAbsent(moduleName, k -> new AtomicInteger()).incrementAndGet();
                return null;
            }

            scanned.incrementAndGet();
            boolean[] hits = new boolean[2];
            byte[] patched = rewrite(bytes, util, atlas, hits);
            if (!hits[ANY]) return null;
            if (hits[WALL]) patchedByModule.computeIfAbsent(moduleName, k -> new AtomicInteger()).incrementAndGet();
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

    private static final int ANY = 0, WALL = 1;

    /** Sets {@code hits[ANY]} if anything changed and {@code hits[WALL]} if a currentTimeMillis call did. */
    private static byte[] rewrite(byte[] bytes, boolean util, boolean atlas, boolean[] hits) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (visitor == null) return null;
                boolean getMillis = util && UTIL_GET_MILLIS.contains(name) && descriptor.equals("()J");
                boolean tick = atlas && TEXTURE_MANAGER_TICK.contains(name) && descriptor.equals("()V");
                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
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
                        if (getMillis && opcode == Opcodes.LRETURN) {
                            // The long being returned goes in and a long comes back out: the stack depth never changes.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, FAKE_TIME, "utilMillis", "(J)J", false);
                            hits[ANY] = true;
                            utilPatched = true;
                        }
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals("java/lang/System")
                                && method.equals("currentTimeMillis") && desc.equals("()J")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, FAKE_TIME, "millis", "()J", false);
                            hits[ANY] = hits[WALL] = true;
                        } else {
                            super.visitMethodInsn(opcode, owner, method, desc, itf);
                        }
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
