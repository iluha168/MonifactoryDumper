package com.iluha168.monifactory.dumper;

import com.iluha168.monifactory.faketime.FakeTime;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Watches one draw of a layer through the clock agent's hooks and tells from it whether the layer can look different
 * at another time. A layer can if its draw
 * <ul>
 * <li>read a clock: the wall clock, {@code Util.getMillis}, the level's game or day time, GLFW's, or the shader's
 * {@code GameTime};</li>
 * <li>sampled an atlas sprite that animates, found from the UVs of what went through {@code BufferUploader};</li>
 * <li>drew from a vertex buffer the uploader never saw while an atlas holding animated sprites was bound, since its
 * UVs are on the GPU and out of sight.</li>
 * </ul>
 * Anything else a draw puts on screen is a function of state it does not change. {@code System.nanoTime} and
 * {@code Util.getNanos} are counted but don't count: code reads them to time itself, not to animate.
 * <p>
 * Reading a clock is not the same as depending on it. ImmediatelyFast stamps a pooled buffer on every text draw, CIT
 * Resewn stamps its cache on every item. So {@link Verdict#CLOCK} only says the draw read one; the caller draws the
 * layer again at other times to see whether the clock reached the pixels.
 * <p>
 * The hooks run inside the draw they watch, so whatever they do is part of it. They read no clock, since a read would
 * be reported back to them, and they create nothing: a texture's GL id is read from its field, never through
 * {@code AbstractTexture.getId()}, which makes the GL texture of one not made yet. That call, mid-draw, once corrupted
 * most recipes' pictures. Render thread only; one draw at a time.
 */
final class LayerRecorder implements FakeTime.Recorder {
    /** What a trace says about a layer. */
    enum Verdict {
        /** Nothing it drew can change with time. */
        STATIC,
        /** It read a clock and nothing else that moves: static if a draw at another time comes out the same. */
        CLOCK,
        /** It sampled an animated sprite, or may have, through a draw the uploader never saw. */
        ANIMATED
    }

    /** What one draw did, as counts. Names only the animated sprites, for diagnostics. */
    static final class Trace {
        private final int[] reads = new int[FakeTime.TIME_SOURCES];
        private int uploads, draws, unrecordedDraws;
        private boolean unrecordedAnimatedAtlas;
        private final Set<String> animatedSprites = new TreeSet<>();

        /** How many times the draw read the {@code FakeTime.TIME_} clock {@code source}. */
        int reads(int source) {
            return reads[source];
        }

        /** Buffers drawn through {@code BufferUploader}. */
        int uploads() {
            return uploads;
        }

        /** Indexed draws, uploaded or not. */
        int draws() {
            return draws;
        }

        /** Indexed draws from vertex buffers the uploader never saw. */
        int unrecordedDraws() {
            return unrecordedDraws;
        }

        /** The animated sprites the draw sampled, by name. */
        Set<String> animatedSprites() {
            return Collections.unmodifiableSet(animatedSprites);
        }

        Verdict verdict() {
            if (!animatedSprites.isEmpty() || unrecordedAnimatedAtlas) return Verdict.ANIMATED;
            for (int source = 0; source < reads.length; source++) {
                if (reads[source] > 0 && isClock(source)) return Verdict.CLOCK;
            }
            return Verdict.STATIC;
        }

        /** One line for a log or a diagnostics column. */
        @Override
        public String toString() {
            StringBuilder out = new StringBuilder(verdict().name().toLowerCase());
            for (int source = 0; source < reads.length; source++) {
                if (reads[source] > 0) out.append(' ').append(SOURCE_NAMES[source]).append('=').append(reads[source]);
            }
            out.append(" uploads=").append(uploads).append(" draws=").append(draws);
            if (unrecordedDraws > 0) {
                out.append(" unrecorded=").append(unrecordedDraws);
                if (unrecordedAnimatedAtlas) out.append(" (animated atlas bound)");
            }
            if (!animatedSprites.isEmpty()) out.append(" sprites=").append(animatedSprites);
            return out.toString();
        }
    }

    /** {@code FakeTime.TIME_} sources by index, as {@link Trace#toString} names them. */
    private static final String[] SOURCE_NAMES = {"wall", "utilMillis", "nano", "utilNanos", "levelGameTime",
            "levelDayTime", "glfw", "shaderGameTime"};

    /** Whether a read of {@code source} can animate: every clock but the two that time code. */
    static boolean isClock(int source) {
        return source != FakeTime.TIME_NANO && source != FakeTime.TIME_UTIL_NANOS;
    }

    /**
     * {@code TextureManager.byPath}, {@code AbstractTexture.id} and {@code TextureAtlas.texturesByName}, by their SRG
     * names like {@link Dumper}'s: found by type instead, the id would be one of the int fields mods' mixins add.
     */
    private static final Field TEXTURES_BY_PATH =
            ObfuscationReflectionHelper.findField(TextureManager.class, "f_118468_");
    private static final Field TEXTURE_ID = ObfuscationReflectionHelper.findField(AbstractTexture.class, "f_117950_");
    private static final Field SPRITES_BY_NAME =
            ObfuscationReflectionHelper.findField(TextureAtlas.class, "f_118264_");

    private final Minecraft minecraft;
    private Trace trace;
    /** Uploads not yet followed by the indexed draw that draws them. */
    private int pending;

    LayerRecorder(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    /**
     * Records the calling thread's draws into a fresh trace until {@link #stop}. The agent reports only a frozen
     * thread's draws, so this must run under {@link DrawTime}: outside it nothing would be seen, and every layer would
     * look static. Put the flush that sends the layer to the GPU before {@link #stop}.
     */
    void start() {
        if (!FakeTime.isFrozen()) throw new IllegalStateException("recording a draw that is not under DrawTime");
        trace = new Trace();
        pending = 0;
        FakeTime.record(this);
    }

    /** Stops recording and returns what was recorded since {@link #start}. */
    Trace stop() {
        FakeTime.record(null);
        Trace out = trace;
        trace = null;
        return out;
    }

    // Hooks. The agent calls them on the frozen thread, in the middle of the draw.

    @Override
    public void time(int source) {
        if (trace != null) trace.reads[source]++;
    }

    @Override
    public void upload(Object renderedBuffer) {
        if (trace == null) return;
        BufferBuilder.RenderedBuffer buffer = (BufferBuilder.RenderedBuffer) renderedBuffer;
        // The uploader releases an empty buffer without drawing it.
        if (buffer.isEmpty()) return;
        trace.uploads++;
        pending++;
        BufferBuilder.DrawState state = buffer.drawState();
        if (state.indexOnly() || !(texture(RenderSystem.getShaderTexture(0)) instanceof TextureAtlas atlas)) return;
        AtlasIndex index = index(atlas);
        if (index.animated.isEmpty()) return;
        VertexFormat format = state.format();
        int uv = uvOffset(format);
        if (uv < 0) return;
        int stride = format.getVertexSize();
        int corners = switch (state.mode()) {
            case QUADS -> 4;
            case TRIANGLES -> 3;
            default -> 1;
        };
        ByteBuffer data = buffer.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int base = data.position();
        // One sprite per primitive, the one under its UV centroid: a corner's UV sits on the sprite's edge, where
        // the neighbour starts.
        for (int vertex = 0; vertex + corners <= state.vertexCount(); vertex += corners) {
            float u = 0, v = 0;
            for (int k = 0; k < corners; k++) {
                int at = base + (vertex + k) * stride + uv;
                u += data.getFloat(at);
                v += data.getFloat(at + 4);
            }
            TextureAtlasSprite sprite = index.at(u / corners, v / corners);
            if (sprite != null && index.animated.contains(sprite)) {
                trace.animatedSprites.add(sprite.contents().name().toString());
            }
        }
    }

    @Override
    public void drawElements() {
        if (trace == null) return;
        trace.draws++;
        if (pending > 0) {
            pending--;
            return;
        }
        trace.unrecordedDraws++;
        if (trace.unrecordedAnimatedAtlas) return;
        // Its UVs are on the GPU. All that can be known is what it could sample: RenderSystem's 12 texture units.
        for (int unit = 0; unit < 12; unit++) {
            if (texture(RenderSystem.getShaderTexture(unit)) instanceof TextureAtlas atlas
                    && !index(atlas).animated.isEmpty()) {
                trace.unrecordedAnimatedAtlas = true;
                return;
            }
        }
    }

    /** Does nothing yet: a recorder of what texts a layer draws, for the metadata, is future work. */
    @Override
    public void text(Object text, float x, float y, int color, Object matrix) {
    }

    /** Does nothing yet: a recorder of what items a layer draws, for the metadata, is future work. */
    @Override
    public void item(Object stack, Object context, Object poseStack) {
    }

    // Textures by GL id.

    private final Map<Integer, AbstractTexture> byId = new HashMap<>();
    /** Ids looked up and not found since the texture manager's map last changed size. */
    private final Set<Integer> unknown = new HashSet<>();
    private int indexedSize = -1;

    /**
     * The texture the game registered that holds GL id {@code id} now, or null.
     * <p>
     * The id map is rebuilt when the texture manager holds a different number of textures, or when an id not seen
     * before turns up: a texture gets its GL id lazily, on its first bind, so one registered before the last rebuild
     * may hold an id the map doesn't know. An id still unknown after a rebuild is remembered, and not rebuilt for
     * again until the manager's map changes size. A remembered hit is checked against the texture's id, which moves
     * when a texture is released and its id handed to another.
     */
    @SuppressWarnings("unchecked")
    private AbstractTexture texture(int id) {
        if (id <= 0) return null;
        Map<ResourceLocation, AbstractTexture> all = (Map<ResourceLocation, AbstractTexture>)
                get(TEXTURES_BY_PATH, minecraft.getTextureManager());
        AbstractTexture found = byId.get(id);
        if (found != null && idOf(found) == id) return found;
        if (all.size() != indexedSize) unknown.clear();
        else if (found == null && unknown.contains(id)) return null;
        byId.clear();
        for (AbstractTexture texture : all.values()) {
            int own = idOf(texture);
            if (own > 0) byId.put(own, texture);
        }
        indexedSize = all.size();
        found = byId.get(id);
        if (found == null) unknown.add(id);
        return found;
    }

    private static int idOf(AbstractTexture texture) {
        try {
            return TEXTURE_ID.getInt(texture);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Where the first float texture coordinate sits in a vertex of {@code format}, in bytes, or -1. */
    private static int uvOffset(VertexFormat format) {
        int offset = 0;
        for (VertexFormatElement e : format.getElements()) {
            if (e.getUsage() == VertexFormatElement.Usage.UV && e.getIndex() == 0
                    && e.getType() == VertexFormatElement.Type.FLOAT) return offset;
            offset += e.getByteSize();
        }
        return -1;
    }

    // Atlases.

    private final Map<TextureAtlas, AtlasIndex> atlases = new IdentityHashMap<>();

    /** The index of {@code atlas}, built on first use and again only if the atlas was restitched since. */
    @SuppressWarnings("unchecked")
    private AtlasIndex index(TextureAtlas atlas) {
        Map<ResourceLocation, TextureAtlasSprite> sprites = (Map<ResourceLocation, TextureAtlasSprite>)
                get(SPRITES_BY_NAME, atlas);
        AtlasIndex index = atlases.get(atlas);
        if (index == null || index.source != sprites) {
            index = new AtlasIndex(sprites);
            atlases.put(atlas, index);
        }
        return index;
    }

    /**
     * An atlas's sprites by where they sit, on a {@link #CELLS} square grid over its UV space, and which of them
     * animate. A lookup checks the few sprites overlapping one cell instead of every sprite of the atlas.
     */
    private static final class AtlasIndex {
        static final int CELLS = 256;
        /** The map it was built from: a restitched atlas has a new one. */
        final Map<ResourceLocation, TextureAtlasSprite> source;
        final TextureAtlasSprite[][] grid = new TextureAtlasSprite[CELLS * CELLS][];
        final Set<TextureAtlasSprite> animated = Collections.newSetFromMap(new IdentityHashMap<>());

        AtlasIndex(Map<ResourceLocation, TextureAtlasSprite> source) {
            this.source = source;
            Collection<TextureAtlasSprite> sprites = source.values();
            List<List<TextureAtlasSprite>> cells = new ArrayList<>(Collections.nCopies(CELLS * CELLS, null));
            for (TextureAtlasSprite sprite : sprites) {
                if (sprite.contents().getUniqueFrames().count() > 1) animated.add(sprite);
                for (int y = cell(sprite.getV0()); y <= cell(sprite.getV1()); y++) {
                    for (int x = cell(sprite.getU0()); x <= cell(sprite.getU1()); x++) {
                        int at = y * CELLS + x;
                        if (cells.get(at) == null) cells.set(at, new ArrayList<>(2));
                        cells.get(at).add(sprite);
                    }
                }
            }
            for (int i = 0; i < grid.length; i++) {
                List<TextureAtlasSprite> list = cells.get(i);
                if (list != null) grid[i] = list.toArray(new TextureAtlasSprite[0]);
            }
        }

        static int cell(float f) {
            return Math.max(0, Math.min(CELLS - 1, (int) (f * CELLS)));
        }

        /** The sprite whose UV rectangle holds ({@code u}, {@code v}), or null. */
        TextureAtlasSprite at(float u, float v) {
            TextureAtlasSprite[] candidates = grid[cell(v) * CELLS + cell(u)];
            if (candidates == null) return null;
            for (TextureAtlasSprite s : candidates) {
                if (u >= s.getU0() && u <= s.getU1() && v >= s.getV0() && v <= s.getV1()) return s;
            }
            return null;
        }
    }
}
