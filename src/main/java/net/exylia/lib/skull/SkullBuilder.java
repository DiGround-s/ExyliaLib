package net.exylia.lib.skull;

import net.exylia.lib.skull.internal.Handles;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Describes the head to build.
 *
 * <p>Names and lore go through {@link Text}, so palette tokens, MiniMessage
 * and placeholders all work exactly as they do everywhere else in the library.
 * ExyliaCommons used legacy strings here, which meant a head was the one place
 * a gradient did not work.
 *
 * <p>Obtained from {@link Skulls}; not constructed directly.
 *
 * @since 1.19.0
 */
public final class SkullBuilder {

    private final SkullSource source;

    private String name;
    private List<String> lore;
    private int amount = 1;
    private boolean glow;
    private Player viewer;
    private ItemFlag[] flags;

    SkullBuilder(SkullSource source) {
        this.source = source;
    }

    /**
     * Sets the display name.
     *
     * <p>Italic by default in vanilla, which nobody wants; the italics are
     * turned off unless the text asks for them.
     *
     * @param name the name, as Exylia text
     * @return this builder
     */
    public @NotNull SkullBuilder name(@NotNull String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the lore.
     *
     * @param lines the lines, as Exylia text
     * @return this builder
     */
    public @NotNull SkullBuilder lore(@NotNull String... lines) {
        this.lore = Arrays.asList(lines);
        return this;
    }

    /**
     * Sets the lore.
     *
     * @param lines the lines, as Exylia text
     * @return this builder
     */
    public @NotNull SkullBuilder lore(@NotNull List<String> lines) {
        this.lore = new ArrayList<>(lines);
        return this;
    }

    /**
     * Sets the stack size.
     *
     * @param amount the size, clamped to 1..64
     * @return this builder
     */
    public @NotNull SkullBuilder amount(int amount) {
        this.amount = Math.clamp(amount, 1, 64);
        return this;
    }

    /**
     * Makes the head glow, without showing an enchantment in the tooltip.
     *
     * @return this builder
     */
    public @NotNull SkullBuilder glow() {
        this.glow = true;
        return this;
    }

    /**
     * Hides parts of the tooltip.
     *
     * @param flags what to hide
     * @return this builder
     */
    public @NotNull SkullBuilder hide(@NotNull ItemFlag... flags) {
        this.flags = flags;
        return this;
    }

    /**
     * Whose screen this head is for.
     *
     * <p>Two things: text is rendered for them, so their placeholders resolve,
     * and the late swap runs on the thread that owns them, which is what makes
     * this correct on Folia.
     *
     * @param viewer the player
     * @return this builder
     */
    public @NotNull SkullBuilder viewer(@Nullable Player viewer) {
        this.viewer = viewer;
        return this;
    }

    /**
     * Builds the handle.
     *
     * <p>Returns immediately. When the texture is known the handle is already
     * finished; when it is not, the lookup starts here and the handle reports
     * back through {@link SkullHandle#onReady}.
     *
     * @return the handle
     */
    public @NotNull SkullHandle build() {
        return Handles.create(source, decorator(), viewer);
    }

    /**
     * Builds the item directly, ignoring anything not yet fetched.
     *
     * <p>For heads whose texture cannot need a lookup — a base64 value or a
     * URL from a config — where a handle would be ceremony around a value that
     * is already complete. A player head that has not been fetched comes back
     * plain.
     *
     * @return the item
     */
    public @NotNull ItemStack item() {
        return build().item();
    }

    /**
     * Builds the head, calling back when it is complete.
     *
     * <p>The shortest form for a menu slot: draw what comes back now, and let
     * the callback replace it later.
     *
     * @param action what to do with the head, now and again when it lands
     * @return the handle
     */
    public @NotNull SkullHandle build(@NotNull Consumer<ItemStack> action) {
        SkullHandle handle = build();
        action.accept(handle.item());
        if (!handle.isReady()) {
            handle.onReady(action);
        }
        return handle;
    }

    /**
     * Turns the builder's settings into something that can decorate any head,
     * whenever it arrives.
     *
     * <p>Captured once rather than re-read: the late swap happens after the
     * builder is long out of scope.
     */
    private UnaryOperator<ItemStack> decorator() {
        String capturedName = name;
        List<String> capturedLore = lore;
        int capturedAmount = amount;
        boolean capturedGlow = glow;
        ItemFlag[] capturedFlags = flags;
        Player capturedViewer = viewer;

        if (capturedName == null && capturedLore == null && capturedAmount == 1
                && !capturedGlow && capturedFlags == null) {
            // Nothing to do: skip the metadata round trip entirely, which on a
            // menu of plain heads is the whole cost of building it.
            return head -> head;
        }

        return head -> {
            head.setAmount(capturedAmount);
            ItemMeta meta = head.getItemMeta();
            if (meta == null) {
                return head;
            }
            if (capturedName != null) {
                meta.displayName(render(capturedName, capturedViewer));
            }
            if (capturedLore != null && !capturedLore.isEmpty()) {
                List<Component> lines = new ArrayList<>(capturedLore.size());
                for (String line : capturedLore) {
                    lines.add(render(line, capturedViewer));
                }
                meta.lore(lines);
            }
            if (capturedGlow) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            if (capturedFlags != null) {
                meta.addItemFlags(capturedFlags);
            }
            head.setItemMeta(meta);
            return head;
        };
    }

    /**
     * Renders text for an item.
     *
     * <p>Italics off unless asked for: vanilla italicises item names and lore,
     * and every plugin that forgets this ends up with a slanted menu.
     */
    private static Component render(String text, Player viewer) {
        Text built = Text.of(text);
        if (viewer != null) {
            built = built.forPlayer(viewer);
        }
        return built.build().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
