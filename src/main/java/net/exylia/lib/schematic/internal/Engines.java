package net.exylia.lib.schematic.internal;

import net.exylia.lib.platform.Platform;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Which engine moves the blocks, and the seam tests use instead.
 *
 * <h2>FastAsyncWorldEdit first, the built-in engine otherwise</h2>
 * FAWE wins whenever it is installed and enabled: it is faster on full
 * rewrites and keeps every byte of block-entity NBT. Without it —
 * a hybrid server where FAWE cannot run, or a server that does not have it —
 * {@link NativeEngine} reads and writes the same {@code .schem} files with the
 * Paper API alone, so an arena saved by either is pasted by the other.
 *
 * <h2>Chosen late, on purpose</h2>
 * ExyliaLib is {@code load: STARTUP} and binds from its own {@code onEnable},
 * which can run before FAWE's. So the choice is provisional until FAWE says it
 * is enabled: every {@link #engine()} call that finds the built-in engine bound
 * asks the plugin manager again — one map lookup, on an operation that is
 * about to read a file — and switches to FAWE the moment it is. Once FAWE is
 * bound nothing is asked again. That is also why there is no
 * {@code softdepend} on FAWE in {@code plugin.yml}: load order would only
 * decide something this already decides correctly either way.
 *
 * <h2>Class isolation</h2>
 * Nothing above this class names a FAWE type, and {@link FaweEngine} is never
 * mentioned outside the one guarded call below — not even in an
 * {@code instanceof} — so a server without FAWE loads neither. The guard is
 * against {@link Throwable} rather than {@link Exception} because that is
 * exactly the shape the failure takes: a missing or moved class is a
 * {@link NoClassDefFoundError}, which a {@code catch (Exception)} does not see.
 */
@ApiStatus.Internal
public final class Engines {

    /** Why there is no engine, when there is none: the library is not running. */
    static final String NO_ENGINE =
            "No schematic engine is bound: ExyliaLib is not enabled.";

    static final String BOUND = "FastAsyncWorldEdit is bound.";

    static final String NATIVE =
            "Using the built-in engine: FastAsyncWorldEdit is not installed.";

    static final String NATIVE_FOLIA =
            "Using the built-in engine: FastAsyncWorldEdit is not loaded on this "
                    + "Folia server. Folia only loads plugins that declare "
                    + "folia-supported, so a FAWE build with Folia support is what "
                    + "would replace it.";

    private static volatile SchematicEngine engine;
    private static volatile String reason = NO_ENGINE;
    private static volatile Plugin library;

    /** Whether FAWE is bound, which makes the choice final. */
    private static volatile boolean faweBound;

    /** Whether a test installed the engine, which no lookup may replace. */
    private static volatile boolean pinned;

    private Engines() {
        throw new AssertionError("No instances.");
    }

    /**
     * Binds an engine. Called once, by the library.
     *
     * <p>On Folia the plugin manager is the version check: Folia refuses to
     * load a plugin that does not declare {@code folia-supported}, so a FAWE it
     * knows about is a FAWE built for region threading, and one it does not is
     * replaced by the built-in engine, which is Folia-safe by construction.
     *
     * @param plugin the library plugin, which owns the built-in engine's tasks
     */
    static synchronized void bind(@NotNull Plugin plugin) {
        library = plugin;
        pinned = false;
        resolve();
    }

    /**
     * The bound engine, or {@code null} when the library is not running.
     *
     * <p>Upgrades to FAWE first if it has enabled since the last call.
     */
    static @Nullable SchematicEngine engine() {
        if (!faweBound && !pinned) {
            resolve();
        }
        return engine;
    }

    /** Whether anything can be saved or pasted. */
    static boolean isSupported() {
        return engine != null;
    }

    /** Which engine is bound, or why none is. */
    static @NotNull String reason() {
        if (!faweBound && !pinned) {
            resolve();
        }
        return reason;
    }

    /** Whether the bound engine is the built-in one. */
    static boolean isNative() {
        return engine instanceof NativeEngine;
    }

    private static synchronized void resolve() {
        Plugin owner = library;
        if (pinned || faweBound || owner == null) {
            return;
        }
        if (isFaweEnabled()) {
            try {
                if (FaweEngine.ready()) {
                    SchematicEngine previous = engine;
                    engine = new FaweEngine();
                    faweBound = true;
                    reason = BOUND;
                    if (previous != null) {
                        previous.releaseAll();
                    }
                    return;
                }
            } catch (Throwable absent) {
                // A FAWE whose API moved: the built-in engine below is the
                // answer, rather than a module that fails later.
            }
        }
        if (engine == null) {
            engine = new NativeEngine(owner);
            reason = Platform.isFolia() ? NATIVE_FOLIA : NATIVE;
        }
    }

    /**
     * Whether the server registered and enabled FastAsyncWorldEdit.
     *
     * <p>Asked of the plugin manager rather than of a class: a class is present
     * on a Folia server that refused to enable the plugin owning it, and on any
     * server whose FAWE failed to start. Guards {@link Throwable} for the same
     * reason everything else here does: a server without a plugin manager is a
     * test, not a failure.
     */
    private static boolean isFaweEnabled() {
        try {
            Plugin fawe = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
            return fawe != null && fawe.isEnabled();
        } catch (Throwable absent) {
            return false;
        }
    }

    /** Drops whatever the engine holds. Called when the server stops. */
    static synchronized void unbind() {
        SchematicEngine current = engine;
        if (current != null) {
            current.releaseAll();
        }
        engine = null;
        library = null;
        faweBound = false;
        pinned = false;
        reason = NO_ENGINE;
    }

    /**
     * Test seam: installs an engine directly.
     *
     * <p>A fake here replaces both real engines, so <em>everything</em> the
     * module decides — the name, the folder, the order of the stages, what
     * happens when one of them fails — is exercised with no FAWE and no server.
     *
     * @param replacement the engine, or {@code null} to make the module
     *                    unsupported
     */
    public static synchronized void install(@Nullable SchematicEngine replacement) {
        engine = replacement;
        pinned = true;
        faweBound = false;
        reason = replacement == null ? NO_ENGINE : BOUND;
    }
}
