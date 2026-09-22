package com.iluha168.monifactory.dumper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * Which pack this is, for {@code meta.json}: its name, its version and its mode, the last two as the running game has
 * them rather than as a config file says.
 * <p>
 * The mode is what KubeJS's startup script put in {@code global.packmode}, the value every recipe script branched on.
 * The version is the text the title screen shows after its "Version" label, which FancyMenu resolves from its layout.
 * The name has no in-game source and comes from the manifest, which the build also names the artifact directory after.
 * If the manifest's version disagrees with the game's, the run fails: the artifact would carry one version in its
 * directory name and another inside.
 * <p>
 * Both mods are reached by reflection, by class name. The renderer compiles against EMI because it draws through it;
 * for two static reads it would otherwise need two more pack jars on its compile classpath, and a missing class or
 * member here fails with its name instead of a {@link NoClassDefFoundError} from somewhere in the batch.
 */
record Pack(String name, String version, String mode) {
    /** {@code BuiltinKubeJSPlugin.GLOBAL}, the map a script's {@code global} is. */
    private static final String KUBEJS_PLUGIN = "dev.latvian.mods.kubejs.BuiltinKubeJSPlugin";
    /** Set by Monifactory's {@code kubejs/startup_scripts/_packmode.js}: Normal, Hard or Expert. */
    private static final String PACKMODE_KEY = "packmode";
    /** Rhino's interface for a JS value wrapping a Java object. */
    private static final String RHINO_WRAPPER = "dev.latvian.mods.rhino.Wrapper";

    private static final String FANCYMENU_LAYOUTS = "de.keksuccino.fancymenu.customization.layout.LayoutHandler";
    private static final String FANCYMENU_PARSER = "de.keksuccino.fancymenu.customization.placeholder.PlaceholderParser";
    /** FancyMenu's name for vanilla's TitleScreen, as in the {@code identifier} of the pack's moni-main.txt layout. */
    private static final String TITLE_SCREEN = "title_screen";
    /**
     * The pack's translation key for the title screen's "Version" label (config/fancymenu/custom_locals). The text
     * elements that show the version put it right before the placeholder that reads the version, so whatever follows
     * it in the element's source is the version, however the pack chooses to look it up.
     */
    private static final String VERSION_LABEL_KEY = "moni_titlescreen_version";
    /**
     * FancyMenu reads the version file on a worker thread the first time the placeholder is asked for, and answers
     * with nothing until then. It takes milliseconds; this is for a file that never arrives.
     */
    private static final long VERSION_TIMEOUT = TimeUnit.SECONDS.toMillis(30);

    /**
     * Resolves the pack a frame at a time, on the game thread, since FancyMenu's layouts and placeholders are not
     * meant to be touched from anywhere else. {@link #poll} returns null until the version has come in.
     */
    static final class Resolver {
        private String mode;
        private List<String> versionSources;
        private long since;

        Pack poll() throws ReflectiveOperationException, IOException {
            if (mode == null) {
                mode = packMode();
                versionSources = versionSources();
                since = System.currentTimeMillis();
            }
            TreeSet<String> versions = new TreeSet<>();
            for (String source : versionSources) versions.add(resolve(source).trim());
            if (versions.contains("")) {
                if (System.currentTimeMillis() - since > VERSION_TIMEOUT) {
                    throw new IllegalStateException("FancyMenu resolved the title screen's version to nothing for "
                            + VERSION_TIMEOUT + " ms; the placeholders were " + versionSources);
                }
                return null;
            }
            if (versions.size() != 1) {
                throw new IllegalStateException("the title screen's version elements disagree: " + versions);
            }
            Pack pack = withManifest(versions.first(), mode);
            LOG.info("[dumper] pack {} {} in {} mode, by the running game", pack.name(), pack.version(), pack.mode());
            return pack;
        }
    }

    /** The name from the manifest, checking its version against the game's. */
    private static Pack withManifest(String version, String mode) throws IOException {
        Path manifest = FMLPaths.GAMEDIR.get().resolve("manifest.json");
        // The build installs it next to the mods and names the artifact directory after it, so a run without one is
        // not a run the build started, and an artifact without a name or a checked version is not one to hand out.
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("no " + manifest + "; the pack's name comes from it, and the game's version"
                    + " is checked against it");
        }
        JsonObject json = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        String listed = json.get("version").getAsString();
        if (!listed.equals(version)) {
            throw new IllegalStateException("the game says it is version " + version + " (its title screen), but "
                    + manifest + " says " + listed + ", and the artifact directory is named after the manifest");
        }
        return new Pack(json.get("name").getAsString(), version, mode);
    }

    private static Class<?> modClass(String name, String mod) {
        try {
            return Class.forName(name, true, Pack.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name + " is not loaded; the pack mode and version need " + mod, e);
        }
    }

    private static String packMode() throws ReflectiveOperationException {
        Map<?, ?> global = (Map<?, ?>) modClass(KUBEJS_PLUGIN, "KubeJS").getField("GLOBAL").get(null);
        Object value = global.get(PACKMODE_KEY);
        // A JS string may arrive as a String or as one of Rhino's CharSequences (a concatenation is a ConsString),
        // and anything else wrapped, so the wrapper is peeled first.
        Class<?> wrapper = modClass(RHINO_WRAPPER, "Rhino");
        while (wrapper.isInstance(value)) value = wrapper.getMethod("unwrap").invoke(value);
        if (!(value instanceof CharSequence text) || text.toString().isBlank()) {
            throw new IllegalStateException("KubeJS's global." + PACKMODE_KEY + " is " + value
                    + (value == null ? "" : " (" + value.getClass().getName() + ")")
                    + ", not a mode name; did kubejs/startup_scripts/_packmode.js run?");
        }
        return text.toString().trim();
    }

    /**
     * What follows the "Version" label in each enabled title-screen text element that has one. The pack has three,
     * one per mode, and shows one of them.
     */
    private static List<String> versionSources() throws ReflectiveOperationException {
        Class<?> layouts = modClass(FANCYMENU_LAYOUTS, "FancyMenu");
        Class<?> parser = modClass(FANCYMENU_PARSER, "FancyMenu");
        Method find = parser.getMethod("findPlaceholders", String.class, HashMap.class, boolean.class);
        List<?> screens = (List<?>) layouts.getMethod("getEnabledLayoutsForScreenIdentifier", String.class,
                boolean.class).invoke(null, TITLE_SCREEN, false);
        List<String> sources = new ArrayList<>();
        for (Object layout : screens) {
            for (Object element : (List<?>) layout.getClass().getField("serializedElements").get(layout)) {
                String source = (String) element.getClass().getMethod("getValue", String.class).invoke(element,
                        "source");
                if (source == null) continue;
                for (Object placeholder : (List<?>) find.invoke(null, source, new HashMap<String, String>(), false)) {
                    Class<?> type = placeholder.getClass();
                    Map<?, ?> values = (Map<?, ?>) type.getMethod("getValues").invoke(placeholder);
                    if (!"local".equals(type.getMethod("getIdentifier").invoke(placeholder)) || values == null
                            || !VERSION_LABEL_KEY.equals(values.get("key"))) {
                        continue;
                    }
                    Field end = type.getField("endIndex");
                    sources.add(source.substring(end.getInt(placeholder)));
                }
            }
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("FancyMenu has " + screens.size() + " enabled " + TITLE_SCREEN
                    + " layouts, and no text element in them shows the " + VERSION_LABEL_KEY + " label");
        }
        return sources;
    }

    /** One FancyMenu placeholder string, resolved the way the title screen resolves it. */
    private static String resolve(String source) throws ReflectiveOperationException {
        Method replace = modClass(FANCYMENU_PARSER, "FancyMenu").getMethod("replacePlaceholders", String.class);
        String resolved = (String) replace.invoke(null, source);
        if (resolved.contains("{\"placeholder\"")) {
            throw new IllegalStateException("FancyMenu left a placeholder unresolved: " + source + " -> " + resolved);
        }
        return resolved;
    }
}
