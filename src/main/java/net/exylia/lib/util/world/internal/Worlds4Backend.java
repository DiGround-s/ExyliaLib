package net.exylia.lib.util.world.internal;

import net.exylia.lib.util.world.internal.WorldsReflection.BackendUnavailableException;
import net.kyori.adventure.key.Key;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;

/**
 * The Worlds 4.x generation ({@code net.thenextlvl.worlds.*}), which targets
 * Minecraft 26.1.2 and 26.2 and ships as Java 25 bytecode.
 *
 * <p>4.x is a rewrite of 3.x rather than a step forward from it: the {@code api}
 * package segment is gone, {@code Presets} became constants on {@code Preset},
 * the void is now expressed as
 * {@code GeneratorType.FLAT.with(Preset.THE_VOID)} instead of a preset on its
 * own, and creation moved from {@code Level#createAsync()} to
 * {@code Level#create()}. Deletion left {@code LevelView} for
 * {@code WorldsAccess#delete(World)}, and already answers with a boolean.
 *
 * <p>{@code Level.Builder#legacyName(String)} only exists from 4.1.0 onwards, so
 * it is resolved optionally: on 4.0.0 the level is created under its key-derived
 * name instead of the backend being rejected.
 *
 * <p>Because those classes are Java 25 bytecode, this library cannot put them on
 * its compile classpath under a Java 21 toolchain. Every member is bound
 * reflectively, which also keeps this class loadable on a server that has no
 * Worlds at all.
 */
final class Worlds4Backend implements WorldsBackend {

    private static final String ACCESS = "net.thenextlvl.worlds.WorldsAccess";
    private static final String LEVEL = "net.thenextlvl.worlds.Level";
    private static final String BUILDER = "net.thenextlvl.worlds.Level$Builder";
    private static final String PRESET = "net.thenextlvl.worlds.preset.Preset";
    private static final String GENERATOR_TYPE = "net.thenextlvl.worlds.generator.GeneratorType";
    private static final String FLAT = "net.thenextlvl.worlds.generator.GeneratorType$Flat";

    private final Object access;
    private final Object voidGenerator;

    private final MethodHandle levelBuilder;
    private final MethodHandle builderStructures;
    private final MethodHandle builderGeneratorType;
    /** Added in 4.1.0, so {@code null} on 4.0.0. */
    private final MethodHandle builderLegacyName;
    private final MethodHandle builderBuild;
    private final MethodHandle levelCreate;
    private final MethodHandle accessDelete;

    /**
     * Binds against the installed Worlds 4.x API.
     *
     * @param plugin the enabled Worlds plugin
     * @throws BackendUnavailableException when Worlds is absent, belongs to a
     *                                     different generation, or is a 4.x
     *                                     release whose signatures moved
     */
    Worlds4Backend(Plugin plugin) {
        Class<?> accessClass = WorldsReflection.require(plugin, ACCESS);
        Class<?> levelClass = WorldsReflection.require(plugin, LEVEL);
        Class<?> builderClass = WorldsReflection.require(plugin, BUILDER);
        Class<?> presetClass = WorldsReflection.require(plugin, PRESET);
        Class<?> generatorTypeClass = WorldsReflection.require(plugin, GENERATOR_TYPE);
        Class<?> flatClass = WorldsReflection.require(plugin, FLAT);

        // WorldsAccess extends Plugin and is handed out by a StaticBinder whose
        // lookup can fail on a half-initialised server. The plugin instance is
        // the access implementation, so prefer it and keep the static accessor
        // as the fallback.
        if (accessClass.isInstance(plugin)) {
            this.access = plugin;
        } else {
            try {
                this.access = WorldsReflection
                        .staticMethod(accessClass, "access", accessClass)
                        .invoke();
            } catch (Throwable t) {
                throw new BackendUnavailableException("WorldsAccess.access() failed", t);
            }
        }
        if (this.access == null) {
            throw new BackendUnavailableException("WorldsAccess is unavailable");
        }

        // GeneratorType.FLAT is typed as Flat and carries CLASSIC_FLAT by
        // default; with(preset) returns a new Flat bound to the void. Building
        // it here means a 4.x release that renamed either constant is turned
        // away before any world is built, rather than mid-creation.
        Object flat = WorldsReflection.staticField(generatorTypeClass, "FLAT", flatClass);
        Object theVoid = WorldsReflection.staticField(presetClass, "THE_VOID", presetClass);
        try {
            this.voidGenerator = WorldsReflection
                    .virtual(flatClass, "with", flatClass, presetClass)
                    .invoke(flat, theVoid);
        } catch (Throwable t) {
            throw new BackendUnavailableException("GeneratorType.Flat#with(Preset) failed", t);
        }

        this.levelBuilder = WorldsReflection.staticMethod(
                levelClass, "builder", builderClass, Key.class);
        this.builderStructures = WorldsReflection.virtual(
                builderClass, "structures", builderClass, Boolean.class);
        this.builderGeneratorType = WorldsReflection.virtual(
                builderClass, "generatorType", builderClass, generatorTypeClass);
        this.builderLegacyName = WorldsReflection.optionalVirtual(
                builderClass, "legacyName", builderClass, String.class);
        this.builderBuild = WorldsReflection.virtual(builderClass, "build", levelClass);
        this.levelCreate = WorldsReflection.virtual(levelClass, "create", CompletableFuture.class);
        this.accessDelete = WorldsReflection.virtual(
                accessClass, "delete", CompletableFuture.class, World.class);
    }

    @Override
    public String name() {
        return builderLegacyName != null ? "Worlds 4.x" : "Worlds 4.0.x";
    }

    @Override
    @SuppressWarnings("unchecked") // The API's own declared return type, erased by reflection.
    public CompletableFuture<World> createWorld(Key key, String legacyName, boolean voidWorld) {
        try {
            Object builder = levelBuilder.invoke(key);
            builder = builderStructures.invoke(builder, Boolean.FALSE);
            if (builderLegacyName != null) {
                builder = builderLegacyName.invoke(builder, legacyName);
            }
            if (voidWorld) {
                builder = builderGeneratorType.invoke(builder, voidGenerator);
            }
            Object level = builderBuild.invoke(builder);
            return (CompletableFuture<World>) levelCreate.invoke(level);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    @SuppressWarnings("unchecked") // The API's own declared return type, erased by reflection.
    public CompletableFuture<Boolean> deleteWorld(World world) {
        try {
            return (CompletableFuture<Boolean>) accessDelete.invoke(access, world);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }
}
