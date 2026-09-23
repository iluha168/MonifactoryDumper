package com.iluha168.monifactory.dumper;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.iluha168.monifactory.faketime.FakeTime;
import com.iluha168.monifactory.imgencoder.FramePolicy;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@code meta.json}, the artifact's description of itself (PLAN section 6, DESIGN 2.3): which pack it is of, what drew
 * it, and how. A consumer holding only the directory can tell which pack version and mode it has, which artifact
 * format, whether it is the whole corpus or a sample, whether it has images at all, and what a frame is worth. See
 * {@link Pack} for where the pack fields come from.
 */
final class Meta {
    private Meta() {
    }

    /** The artifact format this renderer writes: layered stills (DESIGN section 2). */
    static final int FORMAT = 2;

    /**
     * What a full build says of its pictures: the scale they were drawn at, the stills in {@code stills.pak} and its
     * size, and how many recipes were drawn as layers and how many whole.
     */
    record Images(int scale, int stills, long stillsBytes, int layered, int fallback) {
    }

    /**
     * Writes {@code meta.json}. {@code images} is null for a data-only artifact, which has no {@code stills.*}: it
     * says {@code "images": false}, and every field that describes the pictures (scale, frame length, frame policy,
     * still count and size, layered and fallback counts) is null rather than absent, so both kinds of artifact have
     * the same keys.
     */
    static void write(Path file, Pack pack, Batch.Selection selection, int failed, Images images) throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("format", FORMAT);
        JsonObject packJson = new JsonObject();
        packJson.addProperty("name", pack.name());
        packJson.addProperty("version", pack.version());
        packJson.addProperty("mode", pack.mode());
        meta.add("pack", packJson);
        meta.addProperty("minecraft", FMLLoader.versionInfo().mcVersion());
        meta.addProperty("forge", FMLLoader.versionInfo().forgeVersion());
        meta.addProperty("renderer", rendererSha256());

        int corpus = selection.corpus(), limit = selection.limit();
        meta.addProperty("recipes", selection.entries().size());
        meta.addProperty("corpus", corpus);
        meta.addProperty("partial", selection.partial());
        meta.addProperty("every", selection.every());
        meta.addProperty("sample", selection.sample());
        meta.addProperty("limit", limit < corpus ? limit : null);
        meta.addProperty("failed", failed);
        meta.addProperty("images", images != null);

        if (images != null) {
            meta.addProperty("scale", images.scale());
            meta.addProperty("frameMillis", FakeTime.FRAME_MILLIS);
            JsonObject policy = new JsonObject();
            policy.addProperty("cap", FramePolicy.CAP);
            policy.addProperty("trim", FramePolicy.TRIM);
            policy.addProperty("maxStored", FramePolicy.MAX_STORED);
            meta.add("framePolicy", policy);
            meta.addProperty("stills", images.stills());
            meta.addProperty("stillsBytes", images.stillsBytes());
            meta.addProperty("layered", images.layered());
            meta.addProperty("fallback", images.fallback());
        } else {
            for (String key : new String[]{"scale", "frameMillis", "framePolicy", "stills", "stillsBytes", "layered",
                    "fallback"}) {
                meta.add(key, JsonNull.INSTANCE);
            }
        }

        Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(meta) + "\n",
                StandardCharsets.UTF_8);
    }

    /** The renderer jar's SHA-256, which pins down exactly which build of this mod drew the images. */
    private static String rendererSha256() throws IOException {
        Path jar = ModList.get().getModFileById(Dumper.MOD_ID).getFile().getFilePath();
        if (!Files.isRegularFile(jar)) return null;
        try (InputStream in = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) != -1; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
