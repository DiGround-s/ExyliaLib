package net.exylia.lib.effect;

import net.exylia.lib.effect.internal.ActionBarBuilder;
import net.exylia.lib.effect.internal.BossBarBuilder;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.effect.internal.FireworkBuilder;
import net.exylia.lib.effect.internal.ParticleBuilder;
import net.exylia.lib.effect.internal.SoundBuilder;
import net.exylia.lib.effect.internal.TitleBuilder;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point for everything a player sees or hears.
 *
 * <h2>One-off effects</h2>
 * <pre>{@code
 * Effects.title("{primary}&lVICTORY").subtitle("{letters}Well played").show(player);
 * Effects.actionBar("{success}Checkpoint reached").show(player);
 * Effects.sound("ENTITY_PLAYER_LEVELUP").show(player);
 * Effects.particle("FLAME").count(20).at(location).showAll();
 * }</pre>
 *
 * <h2>Timed effects</h2>
 * Anything that shows text can be driven by a timer, and {@code %time%} inside
 * that text is the timer's own clock:
 *
 * <pre>{@code
 * Effects.bossBar("{primary}Starting in {highlight}%time%s")
 *         .countdown(10)
 *         .colour("PURPLE")
 *         .onEnd(this::startMatch)
 *         .show(player);
 * }</pre>
 *
 * <p>Time is written in seconds with decimals, so {@code countdown(3.3)} is
 * exactly that, and {@code %time%} renders {@code 3.3} rather than jumping
 * between whole seconds.
 *
 * <h2>Effects that stay</h2>
 * Without a timer, a boss bar, title or action bar stays until it is stopped:
 *
 * <pre>{@code
 * Display bar = Effects.bossBar("{letters}Waiting for players").show(player);
 * bar.stop();
 * }</pre>
 *
 * <h2>From config</h2>
 * Effects are usually not written in Java at all. A server owner declares them
 * in YAML and the plugin plays whatever is configured:
 *
 * <pre>{@code
 * Effects.play(config.get().onWin(), player);
 * }</pre>
 *
 * See {@link EffectConfig}.
 *
 * <h2>How it reaches the player</h2>
 * Effects are sent as packets when PacketEvents is installed, so the server
 * holds no state: no boss bar object in a registry, no entity to tick, nothing
 * to clean up if a player disconnects. Without PacketEvents everything still
 * works through the Bukkit API.
 *
 * <p>Text goes through the colour palette and the placeholder module, so
 * {@code {primary}} and {@code %player_name%} work everywhere an effect takes
 * text.
 *
 * @since 1.4.0
 */
public final class Effects {

    private Effects() {
        throw new AssertionError("No instances.");
    }

    /**
     * Tells the module which plugin owns the effects it creates.
     *
     * <p>Called once, in {@code onEnable}. Effects are stopped automatically
     * when that plugin is disabled, which is what stops a boss bar surviving the
     * plugin that put it there.
     *
     * @param plugin the plugin creating effects
     */
    public static void owner(@NotNull Plugin plugin) {
        EffectRuntime.owner(plugin);
    }

    /**
     * Starts a title.
     *
     * @param text the title text, in any supported notation
     * @return a builder
     */
    public static @NotNull TitleBuilder title(@NotNull String text) {
        return new TitleBuilder(text);
    }

    /**
     * Starts an action bar.
     *
     * @param text the text, in any supported notation
     * @return a builder
     */
    public static @NotNull ActionBarBuilder actionBar(@NotNull String text) {
        return new ActionBarBuilder(text);
    }

    /**
     * Starts a boss bar.
     *
     * @param text the bar title, in any supported notation
     * @return a builder
     */
    public static @NotNull BossBarBuilder bossBar(@NotNull String text) {
        return new BossBarBuilder(text);
    }

    /**
     * Starts a particle effect.
     *
     * @param name the particle, as a Bukkit name such as {@code FLAME} or a key
     *             such as {@code minecraft:flame}
     * @return a builder
     */
    public static @NotNull ParticleBuilder particle(@NotNull String name) {
        return new ParticleBuilder(name);
    }

    /**
     * Starts a sound.
     *
     * @param name the sound, as a Bukkit name such as
     *             {@code ENTITY_PLAYER_LEVELUP} or a key such as
     *             {@code minecraft:entity.player.levelup}
     * @return a builder
     */
    public static @NotNull SoundBuilder sound(@NotNull String name) {
        return new SoundBuilder(name);
    }

    /**
     * Starts a firework.
     *
     * <p>Fireworks are the one effect that cannot be a pure packet on every
     * version, so this spawns one that is removed immediately after detonating:
     * the explosion is seen, but nothing is left ticking.
     *
     * @return a builder
     */
    public static @NotNull FireworkBuilder firework() {
        return new FireworkBuilder();
    }

    /**
     * Plays an effect declared in a config file.
     *
     * @param effect the configured effect
     * @param viewer who should see it
     * @return the display when the effect stays on screen, otherwise
     *         {@code null}
     */
    public static Display play(@NotNull EffectConfig effect, @NotNull org.bukkit.entity.Player viewer) {
        return EffectRuntime.play(effect, viewer);
    }

    /**
     * Plays a configured effect for everybody online.
     *
     * @param effect the configured effect
     */
    public static void playAll(@NotNull EffectConfig effect) {
        EffectRuntime.playAll(effect);
    }

    /**
     * Stops every effect a plugin started.
     *
     * <p>Called automatically when the plugin is disabled.
     *
     * @param pluginName the plugin's name
     * @return how many effects were stopped
     */
    public static int stopAll(@NotNull String pluginName) {
        return EffectRuntime.stopAll(pluginName);
    }

    /**
     * Stops every effect showing to one player.
     *
     * <p>Worth calling when a player leaves an arena or changes state, so a bar
     * from the previous state cannot linger.
     *
     * @param viewer the player
     * @return how many effects were stopped
     */
    public static int stopFor(@NotNull org.bukkit.entity.Player viewer) {
        return EffectRuntime.stopFor(viewer);
    }

    /**
     * Returns how many effects are currently showing.
     *
     * <p>For diagnostics: a number that climbs and never falls means something
     * is creating displays and not stopping them.
     *
     * @return the count of active displays
     */
    public static int active() {
        return EffectRuntime.active();
    }
}
