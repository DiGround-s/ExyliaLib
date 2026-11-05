package net.exylia.lib.input;

import net.exylia.lib.platform.AttributeLines;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Phrases;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Asks somebody for a colour: one of the palette's, or a hex value they type.
 *
 * <pre>{@code
 * inputs.colour(player, "{primary}&lGLOW COLOUR")
 *       .current(tier.glow())
 *       .open(colour -> tiers.save(tier.withGlow(colour)));
 * }</pre>
 *
 * <p>The answer is what a config writes and every coloured key in the library
 * reads: a palette token such as {@code {primary}}, which follows the server's
 * palette when it is restyled, or a {@code #rrggbb} value. Each palette colour
 * is drawn in its own colour, named with its hex, and the last option asks for
 * a hex in a text box.
 *
 * <p>Vanilla colour names (a team colour, a glow outline drawn by the client)
 * are a different set: {@code Editors.pick().colour} offers those.
 *
 * @since 1.458.0
 */
public final class ColourInput {

    /** The option that asks for a hex instead of choosing a palette colour. */
    private static final String HEX = "#";

    private final PluginInputs inputs;
    private final Player player;
    private final String prompt;
    private String current;
    private Duration timeout;

    ColourInput(PluginInputs inputs, Player player, String prompt) {
        this.inputs = inputs;
        this.player = Inputs.require(player, "player");
        this.prompt = Inputs.requireText(prompt, "prompt");
    }

    /**
     * Opens on the colour being edited: its palette colour is ticked, on the
     * page that holds it, or the hex box holds its hex.
     *
     * @param colour the stored value, or {@code null} for none
     * @return this
     */
    public @NotNull ColourInput current(@Nullable String colour) {
        this.current = colour == null || colour.isBlank() ? null : colour.trim();
        return this;
    }

    /** How long the player has to answer each step; must be positive. */
    public @NotNull ColourInput timeout(@NotNull Duration timeout) {
        this.timeout = Inputs.requirePositive(timeout, "timeout");
        return this;
    }

    /**
     * Asks, and completes once with whatever happened.
     *
     * @return the colour, as {@code {token}} or {@code #rrggbb}
     */
    public @NotNull CompletionStage<InputResult<String>> open() {
        List<String> options = new ArrayList<>();
        for (String token : Colors.names()) {
            // Only the snake_case spellings: the camelCase aliases are the same colours.
            if (token.equals(token.toLowerCase(Locale.ROOT))) options.add('{' + token + '}');
        }
        options.sort(null);
        options.add(HEX);
        String ticked = current == null ? null : isHex(current) ? HEX : options.contains(current) ? current : null;
        SearchInput<String> choice = inputs.search(player, prompt, options)
                .label(ColourInput::label)
                .key(option -> option)
                .icon(option -> Material.NAME_TAG)
                .iconItem(ColourInput::swatch)
                .defaultValue(ticked);
        if (timeout != null) choice.timeout(timeout);
        return choice.open().thenCompose(result -> {
            if (!result.completed()) return CompletableFuture.completedFuture(result);
            return result.value().equals(HEX) ? hex() : CompletableFuture.completedFuture(result);
        });
    }

    /** The same, running an action only when a colour was chosen. */
    public @NotNull CompletionStage<InputResult<String>> open(@NotNull Consumer<? super String> completed) {
        Inputs.require(completed, "completed");
        return open().thenApply(result -> result.ifCompleted(completed));
    }

    private CompletionStage<InputResult<String>> hex() {
        TextInput text = inputs.text(player, Phrases.tr("{primary}Type a colour as #rrggbb"))
                .defaultValue(current != null && isHex(current) ? current : null)
                .validate(ColourInput::isHex, Phrases.tr("Write it as #rrggbb, such as #8a51c4."))
                .transform(value -> value.trim().toLowerCase(Locale.ROOT));
        if (timeout != null) text.timeout(timeout);
        return text.open();
    }

    private static String label(String option) {
        if (option.equals(HEX)) return Phrases.tr("TYPE A HEX VALUE");
        TextColor colour = Colors.get(option.substring(1, option.length() - 1));
        String name = option.substring(1, option.length() - 1).replace('_', ' ').toUpperCase(Locale.ROOT);
        return colour == null ? name : name + " " + colour.asHexString();
    }

    /** A swatch drawn in the colour itself: dyed leather, its armour lines hidden. */
    private static @Nullable ItemStack swatch(String option) {
        if (option.equals(HEX)) return null;
        TextColor colour = Colors.get(option.substring(1, option.length() - 1));
        if (colour == null) return null;
        ItemStack item = new ItemStack(Material.LEATHER_CHESTPLATE);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(colour.value()));
            meta.addItemFlags(ItemFlag.HIDE_DYE);
            AttributeLines.hide(meta, item.getType());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Whether a value is {@code #rrggbb}. */
    static boolean isHex(String value) {
        String text = value.trim();
        if (text.length() != 7 || text.charAt(0) != '#') return false;
        for (int index = 1; index < 7; index++) {
            if (Character.digit(text.charAt(index), 16) < 0) return false;
        }
        return true;
    }
}
