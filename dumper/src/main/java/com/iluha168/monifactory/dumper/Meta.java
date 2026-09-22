package com.iluha168.monifactory.dumper;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iluha168.monifactory.faketime.FakeTime;
import com.iluha168.monifactory.imgencoder.FramePolicy;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

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
 * A consumer holding only the directory can tell which pack version it has, whether it is the whole corpus or a
 * sample, and what a frame is worth.
 */
final class Meta {
    private Meta() {
    }

    static void write(Path file, Minecraft minecraft, int recipes, int corpus, int every, int sample, int limit,
                      int failed, long pakBytes) throws IOException {
        JsonObject meta = new JsonObject();
        meta.add("pack", pack());
        meta.addProperty("minecraft", FMLLoader.versionInfo().mcVersion());
        meta.addProperty("forge", FMLLoader.versionInfo().forgeVersion());
        meta.addProperty("renderer", rendererSha256());

        meta.addProperty("recipes", recipes);
        meta.addProperty("corpus", corpus);
        // A sample or a capped run is a partial artifact. It is valid, but it is not the pack.
        meta.addProperty("partial", every > 1 || sample > 1 || recipes < corpus);
        meta.addProperty("every", every);
        meta.addProperty("sample", sample);
        meta.addProperty("limit", limit < corpus ? limit : null);
        meta.addProperty("failed", failed);
        meta.addProperty("imagesBytes", pakBytes);

        meta.addProperty("scale", RecipeRenderer.scale(minecraft));
        meta.addProperty("frameMillis", FakeTime.FRAME_MILLIS);
        JsonArray probes = new JsonArray();
        for (int probe : Batch.PROBES) probes.add(probe);
        meta.add("probes", probes);
        JsonObject policy = new JsonObject();
        policy.addProperty("cap", FramePolicy.CAP);
        policy.addProperty("trim", FramePolicy.TRIM);
        policy.addProperty("maxStored", FramePolicy.MAX_STORED);
        meta.add("framePolicy", policy);

        Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(meta) + "\n",
                StandardCharsets.UTF_8);
    }

    /**
     * The pack's name and version, from the manifest the build installed next to the mods. The build checked the same
     * file against its pins when it downloaded the pack, and names the artifact directory after it.
     */
    private static JsonObject pack() throws IOException {
        JsonObject pack = new JsonObject();
        Path manifest = FMLPaths.GAMEDIR.get().resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            pack.add("name", null);
            pack.add("version", null);
            return pack;
        }
        JsonObject json = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        pack.addProperty("name", json.get("name").getAsString());
        pack.addProperty("version", json.get("version").getAsString());
        return pack;
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
