package com.iluha168.monifactory.downloader;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** {@code --name value} pairs, because the callers of these mains are Gradle tasks. */
final class Args {
    private final Map<String, String> values = new HashMap<>();

    Args(String[] args) {
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected --name value pairs, got " + String.join(" ", args));
            }
            values.put(args[i].substring(2), args[i + 1]);
        }
    }

    String string(String name) {
        String value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }

    Path path(String name) {
        return Path.of(string(name));
    }

    long number(String name) {
        return Long.parseLong(string(name));
    }

    int number(String name, int fallback) {
        String value = values.get(name);
        return value == null ? fallback : Integer.parseInt(value);
    }
}
