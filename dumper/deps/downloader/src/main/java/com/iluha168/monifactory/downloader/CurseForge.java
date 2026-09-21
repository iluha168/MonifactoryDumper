package com.iluha168.monifactory.downloader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Everything this build knows about the pack comes through api.curse.tools, a keyless proxy in
 * front of CurseForge. The official api.curseforge.com wants a per-developer x-api-key and is the
 * fallback if this stops answering; a third party in front of a third party is the same trust
 * either way.
 */
final class CurseForge {
    private static final String API = "https://api.curse.tools/v1/";
    private static final int BATCH = 100;
    private static final int SHA1 = 1;

    private static final Gson GSON = new Gson();

    private CurseForge() {}

    /** One downloadable file. */
    record File(long fileId, long projectId, String fileName, URI downloadUrl, long length, String sha1) {}

    /** A CurseForge project, and the class that decides where its files belong in an instance. */
    record Project(long projectId, String name, int classId) {}

    /** The file CurseForge itself points at as the project's current one. */
    static File mainFile(long projectId) {
        var project = GSON.fromJson(Http.get(URI.create(API + "mods/" + projectId)), JsonObject.class)
            .getAsJsonObject("data");
        long mainFileId = project.get("mainFileId").getAsLong();
        for (var element : project.getAsJsonArray("latestFiles")) {
            var file = element.getAsJsonObject();
            if (file.get("id").getAsLong() == mainFileId) {
                return parseFile(file);
            }
        }
        throw new IllegalStateException(
            "project " + projectId + " points at main file " + mainFileId + ", which is not among its latest files");
    }

    static List<File> files(List<Long> fileIds) {
        var resolved = new ArrayList<File>(fileIds.size());
        forEachBatch("mods/files", "fileIds", fileIds, file -> resolved.add(parseFile(file)));

        var missing = new LinkedHashSet<>(fileIds);
        resolved.forEach(file -> missing.remove(file.fileId()));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("CurseForge knows nothing about file ids " + missing);
        }
        return resolved;
    }

    static Map<Long, Project> projects(List<Long> projectIds) {
        var resolved = new HashMap<Long, Project>(projectIds.size());
        forEachBatch("mods", "modIds", projectIds, project -> {
            long id = project.get("id").getAsLong();
            resolved.put(id, new Project(id, project.get("name").getAsString(), project.get("classId").getAsInt()));
        });

        var missing = new LinkedHashSet<>(projectIds);
        missing.removeAll(resolved.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("CurseForge knows nothing about project ids " + missing);
        }
        return resolved;
    }

    private interface Handler {
        void accept(JsonObject entry);
    }

    private static void forEachBatch(String path, String key, List<Long> ids, Handler handler) {
        var uri = URI.create(API + path);
        for (int from = 0; from < ids.size(); from += BATCH) {
            var batch = ids.subList(from, Math.min(from + BATCH, ids.size()));
            var request = new JsonObject();
            request.add(key, GSON.toJsonTree(batch));
            String body = Http.postJson(uri, GSON.toJson(request));
            for (var element : GSON.fromJson(body, JsonObject.class).getAsJsonArray("data")) {
                handler.accept(element.getAsJsonObject());
            }
        }
    }

    private static File parseFile(JsonObject file) {
        var url = file.get("downloadUrl");
        if (url == null || url.isJsonNull()) {
            // Happens when an author forbids third-party downloads. Nothing to do but say so.
            throw new IllegalStateException(
                "file " + file.get("id") + " (" + file.get("fileName").getAsString() + ") has no download URL");
        }
        String sha1 = null;
        var hashes = file.getAsJsonArray("hashes");
        if (hashes != null) {
            for (var element : hashes) {
                var hash = element.getAsJsonObject();
                if (hash.get("algo").getAsInt() == SHA1) {
                    sha1 = hash.get("value").getAsString();
                }
            }
        }
        return new File(
            file.get("id").getAsLong(),
            file.get("modId").getAsLong(),
            file.get("fileName").getAsString(),
            URI.create(url.getAsString()),
            file.get("fileLength").getAsLong(),
            sha1);
    }
}
