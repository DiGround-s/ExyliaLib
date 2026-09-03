package net.exylia.lib.effect;

import net.exylia.lib.effect.internal.ActionBarBuilder;
import net.exylia.lib.effect.internal.BossBarBuilder;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.effect.internal.FireworkBuilder;
import net.exylia.lib.effect.internal.ParticleBuilder;
import net.exylia.lib.effect.internal.SoundBuilder;
import net.exylia.lib.effect.internal.TitleBuilder;
import net.exylia.lib.text.Text;
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
 * Boss bars, sounds and particles are sent as packets when PacketEvents is
 * installed, so the server holds no state: no boss bar object in a registry, no
 * entity to tick, nothing to clean up if a player disconnects. Without
 * PacketEvents everything still works through the Bukkit API.
 *
 * <p>Titles and action bars always take the server's own Adventure API. They
 * leave no server state either way, so packets bought nothing there and cost a
 * component-to-NBT serialisation on the server thread for every redraw — which
 * an action bar does about three times a second, per viewer, forever.
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
     * Returns effects bound to a plugin, the form that survives sharing a
     * server.
     *
     * <p>The static builders resolve their owner only when exactly one plugin
     * registered; a second plugin on the server makes that a guess, and a
     * wrong guess is a display killed by another plugin's disable.
     * {@code Effects.of(plugin)} stamps the owner on every display instead.
     *
     * @param plugin the plugin the effects belong to
     * @return effects bound to that plugin
     */
    public static @NotNull PluginEffects of(@NotNull Plugin plugin) {
        return new PluginEffects(plugin);
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
     * Starts an action bar from text with its values already in it.
     *
     * <p>For a bar whose numbers are known before it is shown: the values are
     * substituted after the parse, so the template is what gets cached and a
     * bar shown a thousand times with a thousand values parses once.
     *
     * @param text the text with its values
     * @return a builder
     */
    public static @NotNull ActionBarBuilder actionBar(@NotNull Text text) {
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
     * Starts a sound from a config line.
     *
     * <p>A single field of a class or kit YAML often carries the whole sound,
     * so the caller should not have to split it:
     *
     * <pre>{@code
     * Effects.soundFrom("BLOCK_ANVIL_PLACE|1|1").show(player);
     * }</pre>
     *
     * <p>The notation is {@code NAME|volume|pitch}, pipe-separated the way
     * production configs already write it; a namespaced key with a colon
     * survives, since the colon is never a separator here. Missing parts fall
     * back to full volume and pitch.
     *
     * @param line the config line
     * @return a builder, already configured
     */
    public static @NotNull SoundBuilder soundFrom(@NotNull String line) {
        String[] parts = splitFields(line);
        SoundBuilder builder = new SoundBuilder(parts[0]);
        if (parts.length > 1) builder.volume(parseDouble(parts[1], 1.0));
        if (parts.length > 2) builder.pitch(parseDouble(parts[2], 1.0));
        return builder;
    }

    /**
     * Starts a particle effect from a config line.
     *
     * <p>The notation is {@code NAME|count|dx|dy|dz|speed}; a lone spread value
     * is also accepted as {@code NAME|count|spread|speed}, matching the shapes
     * already written in production configs.
     *
     * <pre>{@code
     * Effects.particleFrom("CLOUD|80|1.5|1.5|1.5|1.5").at(location).show(player);
     * Effects.particleFrom("FLAME|20|0.5").at(location).show(player);
     * }</pre>
     *
     * @param line the config line
     * @return a builder, already configured
     */
    public static @NotNull ParticleBuilder particleFrom(@NotNull String line) {
        String[] parts = splitFields(line);
        ParticleBuilder builder = new ParticleBuilder(parts[0]);
        if (parts.length > 1) builder.count(parseInt(parts[1], 1));

        if (parts.length >= 5) {
            // NAME|count|dx|dy|dz[|speed]
            builder.spread(parseDouble(parts[2], 0.0),
                    parseDouble(parts[3], 0.0),
                    parseDouble(parts[4], 0.0));
            if (parts.length > 5) builder.speed(parseDouble(parts[5], 0.0));
        } else if (parts.length > 2) {
            // NAME|count|spread[|speed]
            builder.spread(parseDouble(parts[2], 0.0));
            if (parts.length > 3) builder.speed(parseDouble(parts[3], 0.0));
        }
        return builder;
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
     * Plays a configured effect that counts down, writing the time into
     * {@code %time%}.
     *
     * <p>How long it counts for is the caller's: it is the same number the
     * match, the warmup or the tag is already counting, so it is not a key an
     * owner can set to disagree with what is happening. The file keeps how it
     * looks — the text, the fades, {@code time-style}.
     *
     * @param effect  the configured effect
     * @param viewer  who should see it
     * @param seconds how long it counts for
     * @return the display when the effect stays on screen, otherwise
     *         {@code null}
     */
    public static Display play(@NotNull EffectConfig effect, @NotNull org.bukkit.entity.Player viewer,
                               double seconds) {
        return EffectRuntime.play(effect, viewer, null, seconds);
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

    // --- config-line parsing for soundFrom / particleFrom ---

    /**
     * Splits {@code NAME|a|b|c} on pipes only.
     *
     * <p>Pipe is the one separator: it is what every production config already
     * writes, and a namespaced key such as {@code minecraft:flame} carries a
     * colon that must survive the split.
     */
    private static @NotNull String[] splitFields(@NotNull String line) {
        String[] parts = line.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
