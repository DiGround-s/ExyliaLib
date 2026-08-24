package net.exylia.lib.input;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * Builder factory bound to one plugin owner.
 *
 * <p>Every request created here carries the exact Bukkit plugin name into its
 * session. That ownership is what lets {@link Inputs#release(String)} complete
 * pending futures before a disabled plugin's classloader can be retained.
 *
 * @since 1.31.0
 */
public final class PluginInputs {

    private final Plugin plugin;
    private final String pluginName;

    PluginInputs(Plugin plugin) {
        this.plugin = Inputs.require(plugin, "plugin");
        this.pluginName = Inputs.requireText(plugin.getName(), "plugin name");
    }

    /** Returns the plugin that owns requests created by this view. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /** Starts a free-form text request. */
    public @NotNull TextInput text(@NotNull Player player, @NotNull String prompt) {
        return new TextInput(pluginName, player, prompt, InputParser.text());
    }

    /** Starts a strict lowercase identifier request. */
    public @NotNull TextInput id(@NotNull Player player, @NotNull String prompt) {
        return new TextInput(pluginName, player, prompt, InputParser.id());
    }

    /** Starts a forgiving slug request derived from display text. */
    public @NotNull TextInput slug(@NotNull Player player, @NotNull String prompt) {
        return new TextInput(pluginName, player, prompt, InputParser.slug());
    }

    /** Starts a whole-number request. */
    public @NotNull NumberInput<Long> integer(@NotNull Player player, @NotNull String prompt) {
        return new NumberInput<>(pluginName, player, prompt, InputParser.integer());
    }

    /** Starts an exact-decimal request, avoiding binary floating-point loss. */
    public @NotNull NumberInput<BigDecimal> decimal(@NotNull Player player,
                                                     @NotNull String prompt) {
        return new NumberInput<>(pluginName, player, prompt, InputParser.decimal());
    }

    /** Starts a player-formatted precise amount request. */
    public @NotNull AmountInput amount(@NotNull Player player, @NotNull String prompt) {
        return new AmountInput(pluginName, player, prompt);
    }

    /** Starts a duration request. */
    public @NotNull DurationInput duration(@NotNull Player player, @NotNull String prompt) {
        return new DurationInput(pluginName, player, prompt);
    }

    /** Starts a non-destructive yes-or-no request. */
    public @NotNull FlagInput flag(@NotNull Player player, @NotNull String prompt) {
        return new FlagInput(pluginName, player, prompt);
    }

    /** Starts an explicit confirmation that transports may render prominently. */
    public @NotNull ConfirmInput confirm(@NotNull Player player, @NotNull String prompt) {
        return new ConfirmInput(pluginName, player, prompt);
    }

    /** Starts a typed single-choice request over a non-empty snapshot. */
    public <T> @NotNull ChoiceInput<T> choice(@NotNull Player player,
                                               @NotNull String prompt,
                                               @NotNull Collection<T> choices) {
        return new ChoiceInput<>(pluginName, player, prompt, choices);
    }

    /** Starts a typed searchable request over a non-empty snapshot. */
    public <T> @NotNull SearchInput<T> search(@NotNull Player player,
                                               @NotNull String prompt,
                                               @NotNull Collection<T> choices) {
        return new SearchInput<>(pluginName, player, prompt, choices);
    }

    /**
     * Starts a request for an icon: a material, the item in hand, or a head.
     *
     * <p>Answers with a {@code material} value, which is what a menu file
     * writes and what a column stores.
     */
    public @NotNull IconInput icon(@NotNull Player player, @NotNull String prompt) {
        return new IconInput(this, player, prompt);
    }

    /** Starts a multi-field form. */
    public @NotNull FormInput form(@NotNull Player player, @NotNull String prompt) {
        return new FormInput(pluginName, player, prompt);
    }
}
