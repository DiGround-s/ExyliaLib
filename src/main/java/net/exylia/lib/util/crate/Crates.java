package net.exylia.lib.util.crate;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A crate a plugin gets by handing over its catalogue: one key in, one random
 * reward out, with the reels, the keys, the blocks and the payout shared.
 *
 * <pre>{@code
 * PluginCrates crates = Crates.of(this).start(catalogue,
 *         () -> config.get().crate(),
 *         () -> messages.get().crate(),
 *         bound -> config.update(c -> c.withCrateBlocks(bound)),
 *         menus, actions, "crate", "crate_open");
 *
 * // An admin command
 * crates.addKeys(target.getUniqueId(), 5).thenAccept(keys -> ...);
 * crates.bindBlock(admin.getTargetBlockExact(6));
 *
 * // Anywhere the plugin asks whether somebody owns something
 * if (crates.owns(player.getUniqueId(), trim.id())) ...
 *
 * // On reload
 * crates.rebuild();
 * }</pre>
 *
 * <h2>What is shared and what is not</h2>
 * A kill effect, an armour trim and a shield design are different rewards;
 * what a crate does with them is the same. The roll (a rarity by weight over
 * those with something in them, then a reward inside it), the duplicate refund,
 * the keys on the account and in the hand, the reels and their payout, the
 * blocks that open it and the player rows are this module. What a reward is
 * — its name, its icon, its token item — is the plugin's {@link CrateCatalogue},
 * and so are its commands and its placeholders, which call
 * {@link PluginCrates}.
 *
 * <h2>Where state lives</h2>
 * Keys and unlocked ids are stored by the library, in the
 * {@code exylia_crate_players} table of the plugin's own database, one row per
 * player and plugin. Online players are read on join and answered from memory.
 *
 * @since 1.189.0
 */
public final class Crates {

    private static final Map<String, PluginCrates> BY_PLUGIN = new ConcurrentHashMap<>();

    private Crates() {
    }

    /**
     * This plugin's crate.
     *
     * @param plugin the plugin
     * @return its crate, the same instance every time
     */
    public static @NotNull PluginCrates of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new PluginCrates(plugin));
    }

    /**
     * Stops one plugin's crate and forgets it: reels in the air are paid out
     * the way a quit pays them, and its actions, blocks and listeners go.
     *
     * <p>Called by the library when the plugin is disabled, while its database
     * and its scheduler are still there.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        PluginCrates crates = BY_PLUGIN.remove(pluginName);
        if (crates != null) crates.stop();
    }

    /** Stops every plugin's crate, on shutdown. */
    public static void releaseAll() {
        for (String pluginName : Map.copyOf(BY_PLUGIN).keySet()) {
            release(pluginName);
        }
    }
}
