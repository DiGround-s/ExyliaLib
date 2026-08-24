package net.exylia.lib.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the rule that makes a click safe to trust.
 *
 * <p>State belongs on the window a player has open, resolved through its
 * holder. A static map from player to session answers the wrong question the
 * moment somebody opens a chest on top of a panel — the map still says they
 * have one, so a click in the chest is handed to a panel that is not on screen.
 * That is not a hypothetical: it is why {@code MenuRuntime} reads the holder.
 *
 * <p>The sweep is over compiled fields rather than a hand-written list, so a map
 * added tomorrow is caught without anybody remembering to come back here.
 */
class PanelNoStaticStateTest {

    private static final List<String> SWEPT_PACKAGES =
            List.of("net.exylia.lib.panel", "net.exylia.lib.panel.internal");

    @Test
    @DisplayName("no static field is a collection or cache keyed by player")
    void noStaticStateIsKeyedByPlayer() {
        List<String> offenders = new ArrayList<>();

        for (Class<?> type : typesOf(SWEPT_PACKAGES)) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (isKeyedByPlayer(field.getGenericType())) {
                    offenders.add(type.getName() + "#" + field.getName()
                            + " is " + field.getGenericType().getTypeName());
                }
            }
        }

        assertEquals(List.of(), offenders,
                "panel state must live on the window holder, never in a static map keyed by player");
    }

    /**
     * The sweep must actually sweep.
     *
     * <p>Written because the sibling sweep in {@code PublicSignatureSweepTest}
     * passed all three of its assertions while examining zero production types:
     * it read one classpath root and found the test one. An absence assertion
     * that examined nothing is indistinguishable from one that passed.
     */
    @Test
    @DisplayName("the sweep actually looks at the production classes")
    void sweepIsNotVacuous() {
        List<Class<?>> types = typesOf(SWEPT_PACKAGES);
        List<String> names = types.stream().map(Class::getName).toList();

        assertTrue(names.contains("net.exylia.lib.panel.Panels"),
                "the sweep must reach the public entry point, found: " + names);
        assertTrue(names.contains("net.exylia.lib.panel.internal.PanelRuntime"),
                "the sweep must reach the runtime, found: " + names);
        assertTrue(names.contains("net.exylia.lib.panel.internal.Session"),
                "the sweep must reach the session, found: " + names);

        long staticFields = types.stream()
                .flatMap(type -> Stream.of(type.getDeclaredFields()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .count();
        assertTrue(staticFields >= 3,
                "the sweep must have static fields to examine, found: " + staticFields);
    }

    /**
     * Proves the detector detects.
     *
     * <p>Without this, "no offender was found" could mean the matcher never
     * matches anything. A known-bad shape is fed straight to it.
     */
    @Test
    @DisplayName("the detector recognises a map keyed by player when it sees one")
    void detectorRecognisesTheBannedShape() throws NoSuchFieldException {
        assertTrue(isKeyedByPlayer(Banned.class.getDeclaredField("byPlayer").getGenericType()),
                "a Map<UUID, ?> must be recognised as per-player state");
        assertTrue(isKeyedByPlayer(Banned.class.getDeclaredField("players").getGenericType()),
                "a Set<UUID> must be recognised as per-player state");
        assertTrue(isKeyedByPlayer(Banned.class.getDeclaredField("byName").getGenericType()),
                "a Map<Player, ?> must be recognised as per-player state");
    }

    /** Shapes the rule forbids, so the detector can be shown to detect them. */
    @SuppressWarnings("unused")
    private static final class Banned {
        static Map<UUID, Object> byPlayer;
        static Set<UUID> players;
        static Map<org.bukkit.entity.Player, Object> byName;
    }

    // ------------------------------------------------------------------

    /**
     * Whether a declared type is a collection or cache keyed by a player.
     *
     * <p>A {@code Map<String, Runtime>} keyed by plugin name is fine and is the
     * established pattern; a {@code Map<UUID, ?>} or any collection of players
     * is what this forbids.
     */
    private static boolean isKeyedByPlayer(Type type) {
        if (!(type instanceof ParameterizedType parameterized)) {
            return false;
        }
        Type raw = parameterized.getRawType();
        if (!(raw instanceof Class<?> rawType)) {
            return false;
        }
        boolean container = Map.class.isAssignableFrom(rawType)
                || Collection.class.isAssignableFrom(rawType)
                || rawType.getName().contains("Cache");
        if (!container) {
            return false;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length == 0) {
            return false;
        }
        // The key: the first argument of a Map, the element of a Collection.
        return isPlayerLike(arguments[0]);
    }

    private static boolean isPlayerLike(Type argument) {
        if (!(argument instanceof Class<?> type)) {
            return false;
        }
        return type == UUID.class || org.bukkit.entity.Player.class.isAssignableFrom(type)
                || org.bukkit.OfflinePlayer.class.isAssignableFrom(type);
    }

    private static List<Class<?>> typesOf(List<String> packages) {
        List<Class<?>> types = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String packageName : packages) {
            // Every classpath root, not the first: production and test classes
            // share a package name, and taking one hides the other.
            for (Path directory : directoriesOf(packageName)) {
                try (Stream<Path> entries = Files.list(directory)) {
                    for (Path entry : entries.sorted().toList()) {
                        String file = entry.getFileName().toString();
                        if (!file.endsWith(".class")) {
                            continue;
                        }
                        String name = packageName + "."
                                + file.substring(0, file.length() - ".class".length());
                        if (isTestClass(name) || !seen.add(name)) {
                            // Test classes share these package names on a second
                            // classpath root. They are swept over — which is how
                            // this sweep proves it reads every root — but the
                            // rule is about production types, and the fixtures
                            // below are deliberately of the banned shape.
                            continue;
                        }
                        types.add(load(name));
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }
        return types;
    }

    /** Whether a compiled name belongs to a test rather than to the module. */
    private static boolean isTestClass(String name) {
        return name.contains("Test");
    }

    private static List<Path> directoriesOf(String packageName) {
        try {
            List<Path> roots = new ArrayList<>();
            var found = PanelNoStaticStateTest.class.getClassLoader()
                    .getResources(packageName.replace('.', '/'));
            while (found.hasMoreElements()) {
                roots.add(Path.of(found.nextElement().toURI()));
            }
            if (roots.isEmpty()) {
                throw new AssertionError("package not on the classpath: " + packageName);
            }
            return roots;
        } catch (IOException | URISyntaxException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, PanelNoStaticStateTest.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new AssertionError(exception);
        }
    }
}
