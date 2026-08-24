package net.exylia.lib.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the boundary the schema projection exists to protect.
 *
 * <p>A projection that quietly hands back a {@code SchemaNode}, or the canonical
 * {@link Constructor} inside it, would pass every fidelity test in
 * {@code SchemaProjectionTest} and still export the internals. Only a sweep over
 * the compiled signatures can catch that, because it is a fact about the API
 * surface rather than about any one call.
 *
 * <p>The sweep walks the public types of the packages below and rejects any
 * {@code .internal} type or {@code Constructor} reachable from a public
 * constructor, method return or parameter.
 */
class PublicSignatureSweepTest {

    /**
     * The packages whose public surface is swept.
     *
     * <p>{@code panel} is here because it is the consumer the projection exists
     * for: a panel that handed back a {@code SchemaNode}, or grew a public method
     * taking one of its own {@code internal} types, would defeat the boundary
     * from the other side.
     */
    private static final List<String> GUARDED_PACKAGES =
            List.of("net.exylia.lib.config", "net.exylia.lib.panel");

    @Test
    @DisplayName("no internal type appears in a public signature of the guarded packages")
    void noInternalTypeIsPublic() {
        List<String> leaks = new ArrayList<>();

        for (Class<?> type : publicTypesOf(GUARDED_PACKAGES)) {
            for (Executable member : publicMembersOf(type)) {
                for (Class<?> used : signatureOf(member)) {
                    if (isInternal(used)) {
                        leaks.add(type.getName() + "#" + member.getName() + " exposes " + used.getName());
                    }
                }
            }
        }

        assertEquals(List.of(), leaks, "public API must not name a type from an .internal package");
    }

    @Test
    @DisplayName("no java.lang.reflect.Constructor is reachable from Schema or Schema.Field")
    void noConstructorIsReachableFromTheProjection() {
        List<String> leaks = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(List.of(Schema.class, Schema.Field.class));

        while (!pending.isEmpty()) {
            Class<?> type = pending.pop();
            if (!seen.add(type) || !type.getName().startsWith("net.exylia.lib.")) {
                continue;
            }
            for (Executable member : publicMembersOf(type)) {
                for (Class<?> used : signatureOf(member)) {
                    if (Constructor.class.isAssignableFrom(used)) {
                        leaks.add(type.getName() + "#" + member.getName() + " exposes " + used.getName());
                    }
                    pending.push(used);
                }
            }
        }

        assertEquals(List.of(), leaks,
                "the canonical constructor must not be reachable through the projection");
    }

    @Test
    @DisplayName("SchemaNode and SchemaComponent stay package-private")
    void analysisTypesStayPackagePrivate() throws ClassNotFoundException {
        for (String name : List.of("net.exylia.lib.config.internal.SchemaNode",
                "net.exylia.lib.config.internal.SchemaNode$SchemaComponent")) {
            Class<?> type = Class.forName(name);
            assertFalse(Modifier.isPublic(type.getModifiers()),
                    name + " must stay package-private; the public projection is Schema");
        }
    }

    @Test
    @DisplayName("the sweep actually looks at something")
    void sweepIsNotVacuous() {
        List<Class<?>> types = publicTypesOf(GUARDED_PACKAGES);

        assertTrue(types.contains(Schema.class), "the sweep must cover Schema, found: " + types);
        assertTrue(types.contains(ConfigFile.class), "the sweep must cover ConfigFile, found: " + types);
        assertTrue(types.size() >= 7, "expected the whole config package, found: " + types);

        // The second guarded package must be reached too. Listing a package that
        // is never read would pass all three leak assertions while covering half
        // the contract, which is exactly the failure this test class was written
        // after: the first sweep here read one classpath root and swept nothing.
        List<String> names = types.stream().map(Class::getName).toList();
        assertTrue(names.contains("net.exylia.lib.panel.Panels"),
                "the sweep must cover the panel entry point, found: " + names);
        assertTrue(names.contains("net.exylia.lib.panel.PanelSession"),
                "the sweep must cover PanelSession, found: " + names);
    }

    // ------------------------------------------------------------------

    private static boolean isInternal(Class<?> type) {
        Class<?> element = type.isArray() ? type.componentType() : type;
        Package declared = element.getPackage();
        return declared != null
                && (declared.getName().endsWith(".internal") || declared.getName().contains(".internal."));
    }

    private static List<Class<?>> signatureOf(Executable member) {
        Stream<Class<?>> used = Stream.of(member.getParameterTypes());
        if (member instanceof Method method) {
            used = Stream.concat(used, Stream.of(method.getReturnType()));
        }
        return used.toList();
    }

    private static List<Executable> publicMembersOf(Class<?> type) {
        List<Executable> members = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                members.add(method);
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers()) && !constructor.isSynthetic()) {
                members.add(constructor);
            }
        }
        return members;
    }

    /**
     * Lists the public types declared directly in the given packages.
     *
     * <p>Read from the compiled output rather than from a hand-written list, so
     * a public type added tomorrow is swept without anyone remembering to add it
     * here.
     */
    private static List<Class<?>> publicTypesOf(List<String> packages) {
        List<Class<?>> types = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String packageName : packages) {
            // Every root, not the first: production and test classes share the
            // package, and taking one hides the other. The first sweep written
            // here found only the test root and swept nothing at all.
            for (Path directory : directoriesOf(packageName)) {
                try (Stream<Path> entries = Files.list(directory)) {
                    for (Path entry : entries.sorted().toList()) {
                        String file = entry.getFileName().toString();
                        if (!file.endsWith(".class")) {
                            continue;
                        }
                        String name = packageName + "." + file.substring(0, file.length() - ".class".length());
                        if (!seen.add(name)) {
                            continue;
                        }
                        Class<?> type = load(name);
                        if (Modifier.isPublic(type.getModifiers())) {
                            types.add(type);
                        }
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }
        return types;
    }

    private static List<Path> directoriesOf(String packageName) {
        try {
            List<Path> roots = new ArrayList<>();
            var found = PublicSignatureSweepTest.class.getClassLoader()
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
            return Class.forName(name, false, PublicSignatureSweepTest.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new AssertionError(exception);
        }
    }
}
