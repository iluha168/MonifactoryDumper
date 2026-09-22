package com.iluha168.monifactory.dumper;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
 * {@code meta.json}, the artifact's description of itself (PLAN section 6): which pack it is of, what drew it, and how.
 * A consumer holding only the directory can tell which pack version and mode it has, whether it is the whole corpus or
 * a sample, whether it has images at all, and what a frame is worth. See {@link Pack} for where the pack fields come
 * from.
 */
final class Meta {
    private Meta() {
    }

    /**
     * Writes {@code meta.json}. {@code pakBytes} and {@code scale} are null for a data-only artifact, which has no
     * {@code images.pak}: it says {@code "images": false}, and every field that describes the images (their size, scale,
     * frame length, probes and frame policy) is null rather than absent, so both kinds of artifact have the same keys.
     */
    static void write(Path file, Pack pack, Batch.Selection selection, int failed, Long pakBytes, Integer scale)
            throws IOException {
        boolean images = pakBytes != null;
        if (images != (scale != null)) throw new IllegalArgumentException("images.pak without a scale, or the reverse");
        JsonObject meta = new JsonObject();
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
        meta.addProperty("images", images);
        meta.addProperty("imagesBytes", pakBytes);

        meta.addProperty("scale", scale);
        if (images) {
            meta.addProperty("frameMillis", FakeTime.FRAME_MILLIS);
            JsonArray probes = new JsonArray();
            for (int probe : Batch.PROBES) probes.add(probe);
            meta.add("probes", probes);
            JsonObject policy = new JsonObject();
            policy.addProperty("cap", FramePolicy.CAP);
            policy.addProperty("trim", FramePolicy.TRIM);
            policy.addProperty("maxStored", FramePolicy.MAX_STORED);
            meta.add("framePolicy", policy);
        } else {
            meta.add("frameMillis", JsonNull.INSTANCE);
            meta.add("probes", JsonNull.INSTANCE);
            meta.add("framePolicy", JsonNull.INSTANCE);
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
