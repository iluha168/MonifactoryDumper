package com.iluha168.monifactory.downloader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** The CurseForge {@code manifest.json} at the root of the client zip. */
record PackManifest(String minecraft, String forge, String overrides, List<Entry> entries) {
    /** One line of the manifest's file list: which project, and which of its files. */
    record Entry(long projectId, long fileId) {}

    private static final String FORGE_PREFIX = "forge-";

    static PackManifest parse(String json) {
        var root = new Gson().fromJson(json, JsonObject.class);
        var mc = root.getAsJsonObject("minecraft");

        // The launcher installs the primary loader and nothing else, so that entry is the one that counts. Two of them
        // would leave it to whichever launcher reads the pack, and this build would have no way to know which.
        String forge = null;
        for (var element : mc.getAsJsonArray("modLoaders")) {
            var loader = element.getAsJsonObject();
            var primary = loader.get("primary");
            if (primary == null || !primary.getAsBoolean()) {
                continue;
            }
            if (forge != null) {
                throw new IllegalStateException("manifest.json declares more than one primary mod loader");
            }
            String id = loader.get("id").getAsString();
            if (!id.startsWith(FORGE_PREFIX)) {
                throw new IllegalStateException("pack runs on '" + id + "', but this build only knows Forge");
            }
            forge = id.substring(FORGE_PREFIX.length());
        }
        if (forge == null) {
            throw new IllegalStateException("manifest.json declares no primary mod loader");
        }

        var entries = new ArrayList<Entry>();
        for (var element : root.getAsJsonArray("files")) {
            var file = element.getAsJsonObject();
            entries.add(new Entry(file.get("projectID").getAsLong(), file.get("fileID").getAsLong()));
        }

        return new PackManifest(
            mc.get("version").getAsString(),
            forge,
            root.get("overrides").getAsString(),
            List.copyOf(entries));
    }

    /**
     * ForgeGradle needs both numbers as literal strings while the project configures, long before
     * this manifest exists, so they are pinned in the version catalog. This is where the pin gets
     * checked against the pack that actually shipped.
     */
    void requireVersions(String expectedMinecraft, String expectedForge) {
        if (minecraft.equals(expectedMinecraft) && forge.equals(expectedForge)) {
            return;
        }
        throw new IllegalStateException(
            "The pack runs on Minecraft " + minecraft + " / Forge " + forge
                + ", but gradle/libs.versions.toml pins " + expectedMinecraft + " / " + expectedForge + ".\n"
                + "The pack decides these. Update the catalog to match and rebuild.");
    }
}
