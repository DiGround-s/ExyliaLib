package net.exylia.lib.effect;

import net.exylia.lib.effect.internal.ActionBarBuilder;
import net.exylia.lib.effect.internal.BossBarBuilder;
import net.exylia.lib.effect.internal.TitleBuilder;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Effects bound to one plugin.
 *
 * <p>Every display this creates is stamped with the plugin's name: it ticks on
 * that plugin's scheduler and stops when that plugin disables, no matter how
 * many other plugins own effects on the same server. The static builders in
 * {@link Effects} only know their owner when exactly one plugin registered,
 * which a second plugin on the server quietly breaks — this is the form that
 * keeps working.
 *
 * <pre>{@code
 * effects = Effects.of(this);
 * effects.bossBar(energyBar.text()).colour("PURPLE").show(player);
 * }</pre>
 *
 * @since 1.18.3
 */
public final class PluginEffects {

    private final Plugin plugin;

    PluginEffects(Plugin plugin) {
        this.plugin = plugin;
        // The instance is right here, so there is nothing to look up later and
        // nothing for the caller to remember doing in onEnable.
        net.exylia.lib.effect.internal.EffectRuntime.owner(plugin);
    }

    /** A title bound to this plugin. */
    public @NotNull TitleBuilder title(@NotNull String text) {
        return Effects.title(text).ownedBy(plugin.getName());
    }

    /** An action bar bound to this plugin. */
    public @NotNull ActionBarBuilder actionBar(@NotNull String text) {
        return Effects.actionBar(text).ownedBy(plugin.getName());
    }

    /** A boss bar bound to this plugin. */
    public @NotNull BossBarBuilder bossBar(@NotNull String text) {
        return Effects.bossBar(text).ownedBy(plugin.getName());
    }

    /**
     * Plays an effect declared in a config file, bound to this plugin.
     *
     * @param effect the configured effect
     * @param viewer who should see it
     * @return the display when the effect stays on screen, otherwise
     *         {@code null}
     */
    public Display play(@NotNull EffectConfig effect, @NotNull org.bukkit.entity.Player viewer) {
        return net.exylia.lib.effect.internal.EffectRuntime.play(effect, viewer, plugin.getName());
    }

    /**
     * Plays a configured effect for everybody online, bound to this plugin.
     *
     * @param effect the configured effect
     */
    public void playAll(@NotNull EffectConfig effect) {
        net.exylia.lib.effect.internal.EffectRuntime.playAll(effect, plugin.getName());
    }
}
