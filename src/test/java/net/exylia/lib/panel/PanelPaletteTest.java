package net.exylia.lib.panel;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quality-bar point 8, said out loud rather than left silent.
 *
 * <p>The panel module deliberately has no {@code invalidateAll()} and no hook in
 * {@code ExyliaLib.loadPalette}. The reason is that it caches nothing derived
 * from the palette: it holds {@code Item} definitions and {@code UiDefinition}s,
 * which carry raw strings such as {@code {primary}&lSAVE}, and it renders them
 * through {@code PluginItems.render}, whose {@code ItemCache} the palette
 * listener already drops.
 *
 * <p>Adding a second cache here would recreate the 1.16.0 static-effect bug, in
 * which a permanent boss bar kept last week's colours because it was drawn once
 * and never re-parsed. So the claim is not "we forgot": it is a design decision,
 * and this test is what keeps it true.
 *
 * <p>The exemption is also stated in {@code docs/reload.md} and
 * {@code docs/panels.md}, because the spec says silence is not acceptable.
 */
class PanelPaletteTest {

    private static final List<String> SWEPT_PACKAGES =
            List.of("net.exylia.lib.panel", "net.exylia.lib.panel.internal");

    @Test
    @DisplayName("no field in the panel module retains a rendered Component")
    void noFieldRetainsAComponent() {
        List<String> retained = new ArrayList<>();

        for (Class<?> type : typesOf(SWEPT_PACKAGES)) {
            for (Field field : type.getDeclaredFields()) {
                if (mentionsComponent(field.getGenericType())) {
                    retained.add(type.getName() + "#" + field.getName()
                            + " is " + field.getGenericType().getTypeName());
                }
            }
        }

        assertEquals(List.of(), retained,
                "a retained Component outlives a palette reload; the panel must re-render instead, "
                        + "or it must expose invalidateAll() and be hooked into loadPalette");
    }

    /**
     * The sweep must actually sweep.
     *
     * <p>The sibling sweep in {@code PublicSignatureSweepTest} once passed all
     * of its assertions while examining zero production types: it read one
     * classpath root and found the test one. "No Component was retained" and
     * "no class was examined" look identical from the outside, so the second is
     * ruled out explicitly.
     */
    @Test
    @DisplayName("the sweep actually looks at the production classes and their fields")
    void sweepIsNotVacuous() {
        List<Class<?>> types = typesOf(SWEPT_PACKAGES);
        List<String> names = types.stream().map(Class::getName).toList();

        assertTrue(names.contains("net.exylia.lib.panel.internal.Layouts"),
                "the sweep must reach the layouts, which are the most likely place to cache text; "
                        + "found: " + names);
        assertTrue(names.contains("net.exylia.lib.panel.internal.Session"),
                "the sweep must reach the session, found: " + names);

        long fields = types.stream()
                .flatMap(type -> Stream.of(type.getDeclaredFields()))
                .count();
        assertTrue(fields >= 10,
                "the sweep must have fields to examine; found only " + fields);
    }

    /**
     * Proves the detector detects.
     *
     * <p>Without this, an empty offender list could equally mean the matcher
     * never matches. A field of exactly the banned shape is fed to it.
     */
    @Test
    @DisplayName("the detector recognises a retained Component when it sees one")
    void detectorRecognisesTheBannedShape() throws NoSuchFieldException {
        assertTrue(mentionsComponent(Banned.class.getDeclaredField("title").getGenericType()),
                "a bare Component field must be recognised");
        assertTrue(mentionsComponent(Banned.class.getDeclaredField("lore").getGenericType()),
                "a List<Component> must be recognised");
        assertTrue(mentionsComponent(Banned.class.getDeclaredField("byName").getGenericType()),
                "a Map<String, Component> must be recognised");
    }

    /** Shapes the exemption forbids, so the detector can be shown to detect them. */
    @SuppressWarnings("unused")
    private static final class Banned {
        Component title;
        List<Component> lore;
        java.util.Map<String, Component> byName;
    }

    /**
     * The layout the module does hold is raw text, not parsed text.
     *
     * <p>This is the positive half of the claim: the reason nothing needs
     * invalidating is that what is cached still says {@code {primary}}, and what
     * that resolves to is decided when it is drawn.
     */
    @Test
    @DisplayName("the built-in layout holds palette tokens, not resolved colours")
    void builtInLayoutHoldsRawTokens() {
        var builtIn = net.exylia.lib.panel.internal.Layouts.BUILT_IN;

        assertTrue(builtIn.title().contains("{primary}"),
                "the title must still carry its palette token when cached, was: " + builtIn.title());

        List<String> names = builtIn.items().values().stream()
                .map(item -> item.item().name())
                .filter(java.util.Objects::nonNull)
                .toList();
        assertTrue(names.stream().anyMatch(name -> name.contains("{success}")),
                "the save button must still carry its palette token when cached, found: " + names);
        assertTrue(names.stream().noneMatch(name -> name.contains("#")),
                "no button may hold a resolved hex colour, found: " + names);
    }

    // ------------------------------------------------------------------

    /** Whether a declared type is, or contains, an Adventure component. */
    private static boolean mentionsComponent(Type type) {
        if (type instanceof Class<?> raw) {
            Class<?> element = raw.isArray() ? raw.componentType() : raw;
            return Component.class.isAssignableFrom(element);
        }
        if (type instanceof ParameterizedType parameterized) {
            if (mentionsComponent(parameterized.getRawType())) {
                return true;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (mentionsComponent(argument)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Class<?>> typesOf(List<String> packages) {
        List<Class<?>> types = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String packageName : packages) {
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
            var found = PanelPaletteTest.class.getClassLoader()
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
            return Class.forName(name, false, PanelPaletteTest.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new AssertionError(exception);
        }
    }
}
