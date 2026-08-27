package net.exylia.lib.schematic.internal;

import net.exylia.lib.platform.Platform;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The door FastAsyncWorldEdit comes in through, and the seam tests use instead.
 *
 * <p>Nothing above this class names a FAWE type, and {@link FaweEngine} is
 * never mentioned outside the one guarded call below, so a server without FAWE
 * loads neither. The guard is against {@link Throwable} rather than
 * {@link Exception} because that is exactly the shape the failure takes: a
 * missing or moved class is a {@link NoClassDefFoundError}, which a
 * {@code catch (Exception)} does not see.
 */
@ApiStatus.Internal
public final class Engines {

    /** Why there is no engine, when there is none. */
    static final String NO_FAWE =
            "FastAsyncWorldEdit is not installed, so schematics cannot be saved or pasted.";

    static final String FOLIA =
            "FastAsyncWorldEdit is not loaded on this Folia server, so schematics "
                    + "cannot be saved or pasted. Folia only loads plugins that "
                    + "declare folia-supported, so a FAWE build with Folia support "
                    + "is what is missing.";

    static final String BOUND = "FastAsyncWorldEdit is bound.";

    private static volatile SchematicEngine engine;
    private static volatile String reason = NO_FAWE;

    private Engines() {
        throw new AssertionError("No instances.");
    }

    /**
     * Binds an engine, if the server has one.
     *
     * <p>Called once, by the library. On Folia the engine is bound only when
     * the server actually loaded FAWE: Folia refuses to load a plugin that does
     * not declare {@code folia-supported}, so a FAWE the plugin manager knows
     * about is a FAWE built for region threading. That makes the version check
     * the server's job rather than a hardcoded number here, and it keeps the
     * refusal for the builds that really cannot run.
     */
    static void bind() {
        if (Platform.isFolia() && !isFaweLoaded()) {
            engine = null;
            reason = FOLIA;
            return;
        }
        SchematicEngine bound = null;
        try {
            if (FaweEngine.ready()) {
                bound = new FaweEngine();
            }
        } catch (Throwable absent) {
            // FAWE missing, or a version whose API moved. Either way there is
            // no engine, and the module says so rather than failing later.
            bound = null;
        }
        engine = bound;
        reason = bound == null ? NO_FAWE : BOUND;
    }

    /**
     * Whether the server registered FastAsyncWorldEdit at all.
     *
     * <p>Asked of the plugin manager rather than of a class, because a class is
     * present on a Folia server that refused to enable the plugin owning it.
     * Guards {@link Throwable} for the same reason everything else here does: a
     * server without a plugin manager is a test, not a failure.
     */
    private static boolean isFaweLoaded() {
        try {
            return Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        } catch (Throwable absent) {
            return false;
        }
    }

    /** The bound engine, or {@code null} when there is none. */
    static @Nullable SchematicEngine engine() {
        return engine;
    }

    /** Whether anything can be saved or pasted. */
    static boolean isSupported() {
        return engine != null;
    }

    /** Why not, or that one is bound. */
    static @NotNull String reason() {
        return reason;
    }

    /** Drops whatever the engine holds. Called when the server stops. */
    static void unbind() {
        SchematicEngine current = engine;
        if (current != null) {
            current.releaseAll();
        }
        engine = null;
        reason = NO_FAWE;
    }

    /**
     * Test seam: installs an engine directly.
     *
     * <p>A fake here replaces FastAsyncWorldEdit, so <em>everything</em> the
     * module decides — the name, the folder, the order of the stages, what
     * happens when one of them fails — is exercised with no FAWE and no server.
     *
     * @param replacement the engine, or {@code null} to make the module
     *                    unsupported
     */
    public static void install(@Nullable SchematicEngine replacement) {
        engine = replacement;
        reason = replacement == null ? NO_FAWE : BOUND;
    }
}
