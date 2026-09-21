package com.iluha168.monifactory.downloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * The one place that talks to the network. Every call retries, because a build that dies on a
 * single dropped connection halfway through 223 downloads is worse than useless.
 */
final class Http {
    /** Both api.curse.tools and api.github.com answer 403 to a request with no User-Agent. */
    private static final String USER_AGENT = "MonifactoryDumper (+https://github.com/iluha168/MonifactoryDumper)";

    private static final int ATTEMPTS = 4;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build();

    private Http() {}

    static String get(URI uri) {
        return send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String postJson(URI uri, String body) {
        var request = request(uri)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /**
     * Downloads to a sibling {@code .part} file and moves it into place, so an interrupted build
     * never leaves a truncated jar that looks complete on the next run.
     */
    static void download(URI uri, Path dest, long expectedLength, String expectedSha1) {
        var part = dest.resolveSibling(dest.getFileName() + ".part");
        try {
            Files.createDirectories(dest.getParent());
            var response = send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofFile(part));
            long actualLength = Files.size(part);
            if (expectedLength > 0 && actualLength != expectedLength) {
                throw new DownloadException(uri + " is " + actualLength + " B, expected " + expectedLength);
            }
            if (expectedSha1 != null) {
                String actualSha1 = sha1(part);
                if (!actualSha1.equalsIgnoreCase(expectedSha1)) {
                    throw new DownloadException(uri + " hashes to " + actualSha1 + ", expected " + expectedSha1);
                }
            }
            Files.move(response.body(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DownloadException("cannot write " + dest, e);
        } finally {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // The next run overwrites it anyway.
            }
        }
    }

    static String sha1(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new DownloadException("cannot hash " + file, e);
        }
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofMinutes(10));
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                var response = CLIENT.send(request, handler);
                if (response.statusCode() / 100 == 2) {
                    return response;
                }
                last = new DownloadException(request.uri() + " answered HTTP " + response.statusCode());
            } catch (IOException e) {
                last = new DownloadException(request.uri() + " failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownloadException(request.uri() + " interrupted", e);
            }
            if (attempt < ATTEMPTS) {
                sleep(1000L << attempt);
            }
        }
        throw last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("interrupted while backing off", e);
        }
    }

    static final class DownloadException extends RuntimeException {
        DownloadException(String message) {
            super(message);
        }

        DownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
