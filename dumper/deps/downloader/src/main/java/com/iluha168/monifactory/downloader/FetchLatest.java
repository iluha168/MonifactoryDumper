package com.iluha168.monifactory.downloader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Asks CurseForge which pack file is current and writes the answer down. */
public final class FetchLatest {
    public static void main(String[] args) throws Exception {
        var options = new Args(args);
        var file = CurseForge.mainFile(options.number("project"));

        var latest = new JsonObject();
        latest.addProperty("fileId", file.fileId());
        latest.addProperty("fileName", file.fileName());
        latest.addProperty("downloadUrl", file.downloadUrl().toString());
        latest.addProperty("fileLength", file.length());
        if (file.sha1() != null) {
            latest.addProperty("sha1", file.sha1());
        }

        var out = options.path("out");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new Gson().toJson(latest), StandardCharsets.UTF_8);
        System.out.println("Latest is " + file.fileName() + " (file " + file.fileId() + ")");
    }
}
