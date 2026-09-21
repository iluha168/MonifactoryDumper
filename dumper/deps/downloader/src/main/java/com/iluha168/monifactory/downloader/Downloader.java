package com.iluha168.monifactory.downloader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Assembles a runnable Monifactory instance directory: the pack's own files, then every file it
 * lists, each straight into the folder its CurseForge class says it belongs in. Everything else a
 * run needs - Minecraft, Forge, assets, natives - comes from ForgeGradle and the installer.
 */
public final class Downloader {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        var options = new Args(args);
        var packDir = options.path("pack-dir");
        var zip = options.path("zip");

        var latest = GSON.fromJson(Files.readString(options.path("latest"), StandardCharsets.UTF_8), JsonObject.class);
        long length = latest.get("fileLength").getAsLong();
        System.out.println(latest.get("fileName").getAsString() + " (" + mib(length) + ")");

        if (!hasLength(zip, length)) {
            Http.download(URI.create(latest.get("downloadUrl").getAsString()), zip, length, sha1(latest));
        }

        Files.createDirectories(packDir);
        try (var pack = new ZipFile(zip.toFile())) {
            var manifest = PackManifest.parse(read(pack, "manifest.json"));
            manifest.requireVersions(options.string("minecraft"), options.string("forge"));
            System.out.println("  Minecraft " + manifest.minecraft() + ", Forge " + manifest.forge()
                + ", " + manifest.entries().size() + " files");

            var wanted = plan(packDir, manifest);
            prune(packDir, wanted.keySet());
            extractOverrides(pack, manifest.overrides(), packDir);
            Files.writeString(packDir.resolve("manifest.json"), read(pack, "manifest.json"), StandardCharsets.UTF_8);
            fetch(wanted, options.number("threads", 8));
        }
        System.out.println("  done");
    }

    /**
     * Decides where every listed file goes. The manifest gives project and file ids and nothing
     * else, so this needs both halves of CurseForge: the files for their names and URLs, the
     * projects for their class.
     */
    private static Map<Path, CurseForge.File> plan(Path packDir, PackManifest manifest) {
        var files = CurseForge.files(manifest.entries().stream().map(PackManifest.Entry::fileId).toList());
        var projects = CurseForge.projects(manifest.entries().stream().map(PackManifest.Entry::projectId).toList());

        var wanted = new LinkedHashMap<Path, CurseForge.File>();
        for (var file : files) {
            var project = projects.get(file.projectId());
            if (project == null) {
                // The manifest names a project per entry, CurseForge names one per file, and a
                // transferred or re-uploaded file is where the two disagree.
                throw new IllegalStateException("file " + file.fileId() + " (" + file.fileName()
                    + ") belongs to project " + file.projectId() + ", which manifest.json does not list");
            }
            var folder = InstanceFolder.of(project);
            var previous = wanted.put(packDir.resolve(folder.directory()).resolve(file.fileName()), file);
            if (previous != null) {
                throw new IllegalStateException(
                    "files " + previous.fileId() + " and " + file.fileId() + " are both named " + file.fileName());
            }
        }
        return wanted;
    }

    /** Removes anything the pack no longer lists, so a downgrade cannot leave a mod behind. */
    private static void prune(Path packDir, Set<Path> keep) throws IOException {
        Files.walkFileTree(packDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!keep.contains(file)) {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (directory.equals(packDir)) {
                    return FileVisitResult.CONTINUE;
                }
                try (var children = Files.list(directory)) {
                    if (children.findAny().isEmpty()) {
                        Files.delete(directory);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void fetch(Map<Path, CurseForge.File> wanted, int threads) throws Exception {
        var missing = new ArrayList<Map.Entry<Path, CurseForge.File>>();
        for (var entry : wanted.entrySet()) {
            if (!hasLength(entry.getKey(), entry.getValue().length())) {
                missing.add(entry);
            }
        }
        if (missing.isEmpty()) {
            System.out.println("  all " + wanted.size() + " files are already in place");
            return;
        }

        long bytes = missing.stream().mapToLong(entry -> entry.getValue().length()).sum();
        System.out.println("  downloading " + missing.size() + " of " + wanted.size() + " files (" + mib(bytes) + ")");

        var done = new AtomicInteger();
        var failures = new ArrayList<Exception>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var tasks = new ArrayList<Future<?>>();
            for (var entry : missing) {
                var file = entry.getValue();
                tasks.add(pool.submit(() -> {
                    Http.download(file.downloadUrl(), entry.getKey(), file.length(), file.sha1());
                    System.out.println("    [" + done.incrementAndGet() + "/" + missing.size() + "] "
                        + entry.getKey().getParent().getFileName() + "/" + file.fileName());
                }));
            }
            for (var task : tasks) {
                try {
                    task.get();
                } catch (Exception e) {
                    failures.add(e);
                }
            }
        } finally {
            pool.shutdown();
        }
        if (!failures.isEmpty()) {
            var summary = new IllegalStateException(failures.size() + " of " + missing.size() + " downloads failed");
            failures.forEach(summary::addSuppressed);
            throw summary;
        }
    }

    private static void extractOverrides(ZipFile pack, String overrides, Path packDir) throws IOException {
        String prefix = overrides.endsWith("/") ? overrides : overrides + "/";
        var entries = pack.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().startsWith(prefix) || entry.isDirectory()) {
                continue;
            }
            var target = packDir.resolve(entry.getName().substring(prefix.length())).normalize();
            if (!target.startsWith(packDir)) {
                throw new IOException("zip entry escapes the pack directory: " + entry.getName());
            }
            Files.createDirectories(target.getParent());
            try (var in = pack.getInputStream(entry)) {
                // Replacing, so this does not depend on prune having emptied the tree first.
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String read(ZipFile zip, String name) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException(zip.getName() + " has no " + name);
        }
        try (var in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha1(JsonObject json) {
        var value = json.get("sha1");
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static boolean hasLength(Path file, long length) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) == length;
    }

    private static String mib(long bytes) {
        return Math.round(bytes / 1048576.0) + " MiB";
    }
}
