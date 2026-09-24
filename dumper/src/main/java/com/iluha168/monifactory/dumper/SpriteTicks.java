package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.Tickable;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * The atlas tick of a recipe's sequence frame, for the sprites the recipe has used instead of all 1,607.
 * <p>
 * {@code TextureManager.tick} walks every animated sprite of every atlas (1,606 in the block atlas, 1 in particles),
 * and with Embeddium's "animate only visible textures" (on as shipped) each step goes through its {@code preTick}:
 * five dependent loads through scattered objects to learn that the sprite is not active and bump its counter. That is
 * about 70 ns a sprite and 116 us a tick, and every sequence frame has a tick: 20 to 28 s of a {@code sample=50}
 * batch's 156 to 184 s of render thread.
 * <p>
 * Embeddium uploads a sprite's frame only on a tick after something marked it active ({@code getSprite},
 * {@code getU}/{@code getU0}, a sprite blit, a block quad; the tick clears the mark). Every other sprite only counts,
 * and what the GPU shows of it stays as it is. So the pictures a recipe gets depend only on the ticks of the sprites
 * marked while it is drawn. This keeps, per recipe, the sprites whose mark it has seen at any tick so far, and each
 * tick ticks exactly those, through their own tickers, the way the atlas would: a sprite marked in this frame is
 * uploaded on the next tick, and one the recipe showed before and not now still counts. From the tick a sprite joins
 * on, it gets every tick the full walk would give it. Before that, its counter stands still instead of counting ticks
 * it uploaded nothing in, which only moves the phase an animation starts at, and that phase was never the recipe's own:
 * it is whatever the recipes before left. A sprite the recipe draws without a mark (an item model's baked quads) was
 * never uploaded by the full walk either.
 * <p>
 * Finding the marked sprites is a walk too, but of one flag per sprite in arrays built once: 14 ns a sprite, and about
 * 9 sprites to tick, 3 to 4 s of that batch. Two boots of the same build now agree on more animated pictures, too:
 * a sprite's phase only moves in the recipes that show it, where it used to move with every sequence frame of every
 * recipe before it, so a recipe more or less in EMI's list shifted all the phases after it. Where the flag can't be
 * read (no Embeddium, or its interface is gone), where the option is off (then Embeddium uploads every sprite), or
 * where Oculus holds PBR atlases that tick with the colour atlas, every tick is the full {@code TextureManager.tick},
 * as before. A ticker that is not the vanilla sprite's own is ticked every time. Textures that tick and are not atlases
 * are ticked every time. Render thread only.
 */
final class SpriteTicks {
    private static final Field TICKABLE = ObfuscationReflectionHelper.findField(TextureManager.class, "f_118469_");
    private static final Field ANIMATED = ObfuscationReflectionHelper.findField(TextureAtlas.class, "f_118262_");
    /** {@code TextureAtlasSprite.createTicker}'s anonymous ticker's outer sprite. */
    private static final String TICKER_SPRITE = "f_243782_";
    private static final String EMBEDDIUM = "me.jellysquid.mods.sodium.client.";
    /** Embeddium's {@code SpriteContentsExtended.sodium$isActive}, as {@code (Object) boolean}, or null. */
    private static final MethodHandle IS_ACTIVE = isActive();
    /** {@code SodiumClientMod.options().performance.animateOnlyVisibleTextures}, or null. */
    private static final MethodHandle ON_DEMAND = onDemand();

    private final Minecraft minecraft;
    /** The atlases with animated sprites as last indexed, and what else ticks. */
    private final List<Atlas> atlases = new ArrayList<>();
    private final List<Tickable> others = new ArrayList<>();
    private int indexedTickables = -1;
    /** Which atlas has Oculus's PBR atlases, as of the last index, or null. */
    private String pbr;
    /** Why ticks are the full walk, or null while they are the recipe's own. Until the first recipe, unknown. */
    private String whole = "no recipe yet";
    private String loggedWhole;

    private long ownTicks, fullTicks, spritesTicked;

    SpriteTicks(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    /** One atlas's animated sprites, in its own order, and which the recipe has used. */
    private static final class Atlas {
        final TextureAtlas atlas;
        final List<TextureAtlasSprite.Ticker> source;
        final TextureAtlasSprite.Ticker[] tickers;
        /** Per ticker, its sprite's contents, or null to tick it always. */
        final SpriteContents[] contents;
        final boolean[] used;
        final int[] order;
        int count;

        Atlas(TextureAtlas atlas, List<TextureAtlasSprite.Ticker> source) {
            this.atlas = atlas;
            this.source = source;
            int n = source.size();
            tickers = source.toArray(TextureAtlasSprite.Ticker[]::new);
            contents = new SpriteContents[n];
            used = new boolean[n];
            order = new int[n];
            for (int i = 0; i < n; i++) contents[i] = contentsOf(tickers[i]);
        }

        void forget() {
            for (int k = 0; k < count; k++) used[order[k]] = false;
            count = 0;
        }
    }

    /** A new recipe: no sprite is its own yet. */
    void recipe() {
        for (Atlas atlas : atlases) atlas.forget();
        whole = wholeReason();
        if (whole != null && !whole.equals(loggedWhole)) {
            LOG.warn("[dumper] every atlas tick ticks every animated sprite: {}", whole);
        }
        loggedWhole = whole;
    }

    /** One tick for the next sequence frame. */
    void tick() {
        TextureManager manager = minecraft.getTextureManager();
        if (whole != null || !indexed(manager)) {
            fullTicks++;
            FakeTime.tickAtlas(manager::tick);
            return;
        }
        ownTicks++;
        for (Atlas a : atlases) {
            for (int i = 0; i < a.tickers.length; i++) {
                if (!a.used[i] && (a.contents[i] == null || active(a.contents[i]))) {
                    a.used[i] = true;
                    a.order[a.count++] = i;
                }
            }
            if (a.count == 0) continue;
            // As TextureAtlas.cycleAnimationFrames: the uploads go to the bound texture. Each sprite writes only its
            // own rectangle, so the order they go in doesn't matter.
            a.atlas.bind();
            for (int k = 0; k < a.count; k++) a.tickers[a.order[k]].tickAndUpload();
            spritesTicked += a.count;
        }
        for (Tickable other : others) other.tick();
    }

    /** For the batch summary. */
    String summary() {
        return ownTicks + " atlas ticks of the recipe's own sprites (" + (ownTicks == 0 ? 0 : spritesTicked / ownTicks)
                + " a tick on average), " + fullTicks + " of every sprite";
    }

    /**
     * Why the recipe's ticks have to be the full walk, or null. The option is read per recipe, since the options screen
     * can change it; the PBR atlases only come and go with a resource reload, which restitches the atlases, and are
     * looked for when {@link #indexed} rebuilds.
     */
    private String wholeReason() {
        if (IS_ACTIVE == null) return "Embeddium's sprite activity is not readable";
        if (ON_DEMAND == null) return "Embeddium's options are not readable";
        try {
            if (!(boolean) ON_DEMAND.invokeExact()) return "Embeddium animates textures that are not visible";
        } catch (Throwable e) {
            return "Embeddium's options are not readable: " + e;
        }
        return pbr == null ? null : "Oculus ticks PBR atlases along with " + pbr;
    }

    /**
     * Whether the index is the texture manager's current set of atlases, rebuilding it if not: a restitched atlas has
     * a new list. False if an atlas can't be read, which makes the tick the full walk.
     */
    @SuppressWarnings("unchecked")
    private boolean indexed(TextureManager manager) {
        try {
            Set<Tickable> tickables = (Set<Tickable>) TICKABLE.get(manager);
            boolean current = tickables.size() == indexedTickables;
            for (int i = 0; current && i < atlases.size(); i++) {
                current = ANIMATED.get(atlases.get(i).atlas) == atlases.get(i).source;
            }
            if (current) return true;
            atlases.clear();
            others.clear();
            pbr = null;
            for (Tickable tickable : tickables) {
                if (tickable instanceof TextureAtlas atlas) {
                    if (pbr(atlas)) pbr = atlas.location().toString();
                    List<TextureAtlasSprite.Ticker> animated = (List<TextureAtlasSprite.Ticker>) ANIMATED.get(atlas);
                    if (!animated.isEmpty()) atlases.add(new Atlas(atlas, animated));
                } else {
                    others.add(tickable);
                }
            }
            indexedTickables = tickables.size();
            whole = wholeReason();
            return whole == null;
        } catch (IllegalAccessException | RuntimeException e) {
            whole = "the atlases are not readable: " + e;
            LOG.warn("[dumper] every atlas tick ticks every animated sprite: {}", whole);
            loggedWhole = whole;
            atlases.clear();
            indexedTickables = -1;
            return false;
        }
    }

    private static boolean active(SpriteContents contents) {
        try {
            return (boolean) IS_ACTIVE.invokeExact((Object) contents);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The contents of the sprite {@code ticker} ticks, or null if it is not the vanilla sprite's ticker. */
    private static SpriteContents contentsOf(TextureAtlasSprite.Ticker ticker) {
        try {
            Field sprite = ObfuscationReflectionHelper.findField(ticker.getClass(), TICKER_SPRITE);
            return sprite.get(ticker) instanceof TextureAtlasSprite owner ? owner.contents() : null;
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    /** Whether Oculus gave {@code atlas} a holder of PBR atlases, which its mixin ticks after the atlas's own tick. */
    private static boolean pbr(TextureAtlas atlas) {
        try {
            Method holder = atlas.getClass().getMethod("getPBRHolder");
            return holder.invoke(atlas) != null;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    private static MethodHandle onDemand() {
        try {
            Class<?> mod = Class.forName(EMBEDDIUM + "SodiumClientMod", false, SpriteTicks.class.getClassLoader());
            Method options = mod.getMethod("options");
            Field performance = options.getReturnType().getField("performance");
            Field onDemand = performance.getType().getField("animateOnlyVisibleTextures");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            return MethodHandles.filterReturnValue(MethodHandles.filterReturnValue(lookup.unreflect(options),
                    lookup.unreflectGetter(performance)), lookup.unreflectGetter(onDemand));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static MethodHandle isActive() {
        try {
            Class<?> extended = Class.forName(EMBEDDIUM + "render.texture.SpriteContentsExtended", false,
                    SpriteTicks.class.getClassLoader());
            if (!extended.isAssignableFrom(SpriteContents.class)) return null;
            return MethodHandles.publicLookup().findVirtual(extended, "sodium$isActive",
                    MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
