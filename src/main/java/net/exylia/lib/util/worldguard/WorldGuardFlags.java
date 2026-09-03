package net.exylia.lib.util.worldguard;

import net.exylia.lib.util.worldguard.internal.WorldGuardFlagAccess;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldGuard region flags owned by Exylia plugins.
 *
 * <pre>{@code
 * // Once, from onLoad:
 * WorldGuardFlags.register("kill-effects", true);
 *
 * // Then, wherever the effect would play:
 * if (!WorldGuardFlags.allows(WorldGuardFlags.KILL_EFFECTS, victim.getLocation())) {
 *     return;
 * }
 * }</pre>
 *
 * <h2>Why registration has one moment</h2>
 * WorldGuard locks its flag registry the instant it enables, and a flag
 * registered after that throws. Every plugin's {@code onLoad} runs before any
 * plugin's {@code onEnable}, so {@code onLoad} is the only point in a server's
 * life when a flag can still be added — whatever order the plugins happen to
 * load in.
 *
 * <p>That is why ExyliaLib registers the ecosystem's flags from its own
 * {@link net.exylia.lib.ExyliaLib#onLoad()} rather than each plugin registering
 * its own: the Exylia plugins are loaded by a licence loader that only hands
 * control over at enable, which is already too late.
 *
 * <h2>What a query costs</h2>
 * Two field reads when WorldGuard is not installed or the flag was never
 * registered, and one WorldGuard region lookup otherwise — the same lookup
 * WorldGuard itself does for every block broken and every blow landed. It is
 * still worth asking last: check whether an effect would play at all first, so
 * a death that draws nothing never reaches this.
 *
 * <h2>Default</h2>
 * Every flag here defaults to <em>allow</em>. A server that never touches
 * WorldGuard behaves exactly as it did before the flag existed, and a region
 * only ever takes something away.
 *
 * @since 1.90.0
 */
public final class WorldGuardFlags {

    /** Whether a kill inside the region plays the killer's kill effect. */
    public static final String KILL_EFFECTS = "kill-effects";

    /** Whether a blow landed inside the region plays the attacker's hit effect. */
    public static final String HIT_EFFECTS = "hit-effects";

    /** Whether a shot inside the region draws its arrow effect. */
    public static final String ARROWS_EFFECTS = "arrows-effects";

    /**
     * The flags ExyliaLib registers for the ecosystem, and what each defaults
     * to.
     *
     * <p>A new flag is one line here. It has to be declared where every server
     * loads it rather than in the plugin that reads it, for the timing reason
     * above.
     */
    private static final Map<String, Boolean> DEFAULTS = Map.of(
            KILL_EFFECTS, true,
            HIT_EFFECTS, true,
            ARROWS_EFFECTS, true);

    /** The names that were accepted, so a query can answer without WorldGuard. */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private WorldGuardFlags() {
        throw new AssertionError("No instances.");
    }

    /**
     * Registers every flag the Exylia ecosystem defines.
     *
     * <p>Called by ExyliaLib from {@code onLoad}. A server without WorldGuard
     * registers nothing and every query answers "allowed".
     *
     * @return how many flags are usable afterwards
     */
    public static int registerDefaults() {
        int usable = 0;
        for (Map.Entry<String, Boolean> flag : DEFAULTS.entrySet()) {
            if (register(flag.getKey(), flag.getValue())) {
                usable++;
            }
        }
        return usable;
    }

    /**
     * Registers one state flag.
     *
     * <p>Must be called from a plugin's {@code onLoad}. A flag another plugin
     * already registered under the same name is adopted rather than refused, so
     * two plugins asking for the same gate share one flag instead of one of
     * them failing to start.
     *
     * @param name             the flag name, as a region owner types it
     * @param allowedByDefault what a region that never sets it means
     * @return whether the flag can be queried afterwards
     */
    public static boolean register(@NotNull String name, boolean allowedByDefault) {
        // Installed, not enabled. This runs from onLoad, which is before
        // WorldGuard has enabled and is the whole reason it runs there: asking
        // whether WorldGuard is *available* would answer no about the very
        // WorldGuard whose registry is being written to.
        if (!WorldGuardRegions.installed()) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (REGISTERED.contains(key)) {
            return true;
        }
        if (!WorldGuardFlagAccess.register(key, allowedByDefault)) {
            return false;
        }
        REGISTERED.add(key);
        return true;
    }

    /** Whether this flag was registered and can be queried. */
    public static boolean registered(@NotNull String name) {
        return REGISTERED.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a flag permits something at a place.
     *
     * <p>Answers {@code true} without touching WorldGuard when it is not
     * installed, when the flag was never registered, or when the location has
     * no world — an absent gate never withholds anything.
     *
     * @param name  the flag
     * @param where the place being asked about
     * @return whether it is allowed there
     */
    public static boolean allows(@NotNull String name, @NotNull Location where) {
        if (REGISTERED.isEmpty() || where.getWorld() == null) {
            return true;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (!REGISTERED.contains(key) || !WorldGuardRegions.available()) {
            return true;
        }
        return WorldGuardFlagAccess.allows(key, where);
    }

    /** The names of every flag registered, for diagnostics and tab completion. */
    public static @NotNull Set<String> names() {
        return Set.copyOf(REGISTERED);
    }
}
