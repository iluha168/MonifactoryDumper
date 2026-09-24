package com.iluha168.monifactory.downloader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The version check artifact names rest on: the pack's manifest decides, and the catalog pin must agree with it. */
class PackManifestTest {
    private static String manifest(String minecraft, String loaders) {
        return """
            {"minecraft": {"version": "%s", "modLoaders": [%s]},
             "manifestType": "minecraftModpack", "name": "Monifactory", "version": "0.13.8",
             "overrides": "overrides", "files": [{"projectID": 1, "fileID": 2, "required": true}]}
            """.formatted(minecraft, loaders);
    }

    @Test
    void readsThePrimaryLoader() {
        var pack = PackManifest.parse(manifest("1.20.1",
            "{\"id\": \"forge-47.2.0\"}, {\"id\": \"forge-47.4.13\", \"primary\": true}"));
        assertEquals("1.20.1", pack.minecraft());
        assertEquals("47.4.13", pack.forge());
        assertEquals(1, pack.entries().size());
        assertDoesNotThrow(() -> pack.requireVersions("1.20.1", "47.4.13"));
    }

    @Test
    void failsOnAMinecraftMismatch() {
        var pack = PackManifest.parse(manifest("1.20.4", "{\"id\": \"forge-47.4.13\", \"primary\": true}"));
        var failure = assertThrows(IllegalStateException.class, () -> pack.requireVersions("1.20.1", "47.4.13"));
        assertTrue(failure.getMessage().contains("Minecraft 1.20.4"), failure.getMessage());
        assertTrue(failure.getMessage().contains("libs.versions.toml"), failure.getMessage());
    }

    @Test
    void failsOnAForgeMismatch() {
        var pack = PackManifest.parse(manifest("1.20.1", "{\"id\": \"forge-47.4.14\", \"primary\": true}"));
        assertThrows(IllegalStateException.class, () -> pack.requireVersions("1.20.1", "47.4.13"));
    }

    @Test
    void failsOnAnotherLoader() {
        assertThrows(IllegalStateException.class,
            () -> PackManifest.parse(manifest("1.20.1", "{\"id\": \"neoforge-47.1.106\", \"primary\": true}")));
    }

    @Test
    void failsWithoutExactlyOnePrimaryLoader() {
        assertThrows(IllegalStateException.class,
            () -> PackManifest.parse(manifest("1.20.1", "{\"id\": \"forge-47.4.13\"}")));
        assertThrows(IllegalStateException.class, () -> PackManifest.parse(manifest("1.20.1",
            "{\"id\": \"forge-47.4.13\", \"primary\": true}, {\"id\": \"forge-47.4.12\", \"primary\": true}")));
    }
}
