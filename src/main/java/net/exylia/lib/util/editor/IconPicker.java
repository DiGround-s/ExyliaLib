package net.exylia.lib.util.editor;

import net.exylia.lib.input.Inputs;
import net.exylia.lib.item.Source;
import net.exylia.lib.util.editor.internal.InsertWindow;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Asks what something should be drawn as.
 *
 * <pre>{@code
 * editors.icon()
 *        .title("{primary}&lARENA ICON")
 *        .open(player, icon -> arenas.save(arena.withIcon(icon)));
 * }</pre>
 *
 * <p>The answer is a {@code material} value — the same string a menu file writes
 * and the same grammar {@link Source} reads — so it can go straight into a column
 * and straight into {@code material: "%arena_icon%"}.
 *
 * <h2>Three ways, because an icon is three different things</h2>
 * <ul>
 *   <li>{@link Way#MATERIAL} — the searchable list of everything the server has.
 *       Nobody spells {@code POLISHED_BLACKSTONE_BRICK_SLAB} from memory.
 *   <li>{@link Way#INSERT} — a window with one slot. Put the item in and that
 *       item, exactly as it is, is the answer: its model, its colour, its
 *       enchantments. It comes straight back to you afterwards.
 *   <li>{@link Way#HEAD} — a texture pasted from a head site, which is the one
 *       case that can only be typed.
 * </ul>
 *
 * <p>ExyliaCommons read the item out of the player's hand instead of asking for
 * one. That meant closing whatever screen you were on, finding the item, holding
 * it, and reopening — and it could not be done at all from a menu. A slot works
 * from inside the screen that asked.
 *
 * <p>Offering one way skips the question entirely: a plugin that wants a head
 * and nothing else asks for a head.
 *
 * @since 1.56.0
 */
public final class IconPicker {

    /** How an icon is chosen. */
    public enum Way {
        /** Picked from the searchable list of every item the server has. */
        MATERIAL,
        /** Put in a slot, read exactly as it is, and handed straight back. */
        INSERT,
        /** A base64 texture or a skin URL, pasted. */
        HEAD
    }

    private final Plugin plugin;
    private String title = "{primary}&lCHOOSE AN ICON";
    private List<Way> ways = List.of(Way.MATERIAL, Way.INSERT, Way.HEAD);

    IconPicker(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The prompt shown while choosing, in Exylia text notation.
     *
     * @param title the prompt
     * @return this picker
     */
    public @NotNull IconPicker title(@NotNull String title) {
        this.title = Objects.requireNonNull(title, "title");
        return this;
    }

    /**
     * Restricts which ways are offered, in the order given.
     *
     * <p>One way is not a question: the picker goes straight to it.
     *
     * @param ways the ways to offer; at least one
     * @return this picker
     */
    public @NotNull IconPicker ways(@NotNull Way... ways) {
        if (ways.length == 0) {
            throw new IllegalArgumentException("an icon picker needs at least one way");
        }
        this.ways = List.of(ways);
        return this;
    }

    /**
     * Asks, and tells the action only when there is an answer.
     *
     * @param viewer who is choosing
     * @param picked told the icon source
     */
    public void open(@NotNull Player viewer, @NotNull java.util.function.Consumer<String> picked) {
        Objects.requireNonNull(picked, "picked");
        open(viewer).thenAccept(answer -> answer.ifPresent(picked));
    }

    /**
     * Asks.
     *
     * @param viewer who is choosing
     * @return the icon source, or nothing when they backed out
     */
    public @NotNull CompletionStage<Optional<String>> open(@NotNull Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        if (ways.size() == 1) {
            return ask(viewer, ways.get(0));
        }
        return Inputs.of(plugin).choice(viewer, title, ways)
                .label(IconPicker::labelOf)
                .icon(IconPicker::iconOf)
                .key(way -> way.name().toLowerCase(Locale.ROOT))
                .open()
                .thenCompose(result -> result.completed()
                        ? ask(viewer, result.value())
                        : CompletableFuture.completedFuture(Optional.<String>empty()));
    }

    private CompletionStage<Optional<String>> ask(Player viewer, Way way) {
        return switch (way) {
            case MATERIAL -> material(viewer);
            case INSERT -> InsertWindow.open(plugin, viewer, title);
            case HEAD -> head(viewer);
        };
    }

    /**
     * The searchable list of everything the server can hold.
     *
     * <p>Built once per question rather than kept: it depends on which data
     * packs are loaded, and a list cached at startup goes stale the first time
     * somebody reloads one.
     */
    private CompletionStage<Optional<String>> material(Player viewer) {
        List<Material> materials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isLegacy() && material.isItem() && material != Material.AIR) {
                materials.add(material);
            }
        }
        return Inputs.of(plugin).search(viewer, title, materials)
                .label(material -> readable(material.name()))
                .key(Material::name)
                .icon(material -> material)
                .open()
                .thenApply(result -> result.completed()
                        ? Optional.of(result.value().name())
                        : Optional.empty());
    }

    /**
     * A head, pasted.
     *
     * <p>Accepts what the head sites hand out — a base64 texture property, a
     * {@code textures.minecraft.net} URL — and also a value already written in
     * the item module's own grammar, so pasting back something the picker
     * produced earlier does the obvious thing.
     */
    private CompletionStage<Optional<String>> head(Player viewer) {
        return Inputs.of(plugin)
                .text(viewer, "{primary}Paste the texture or skin URL")
                .lines(4)
                .open()
                .thenApply(result -> result.completed()
                        ? Optional.of(headSource(result.value().trim()))
                        : Optional.empty());
    }

    private static String headSource(String pasted) {
        if (Source.of(pasted) instanceof Source.OfHead || pasted.indexOf('-') > 0) {
            return pasted;
        }
        return pasted.startsWith("http") ? "urlhead-" + pasted : "basehead-" + pasted;
    }

    private static String labelOf(Way way) {
        return switch (way) {
            case MATERIAL -> "{primary}&lFROM THE LIST";
            case INSERT -> "{primary}&lINSERT AN ITEM";
            case HEAD -> "{primary}&lPASTE A HEAD";
        };
    }

    private static Material iconOf(Way way) {
        return switch (way) {
            case MATERIAL -> Material.BOOK;
            case INSERT -> Material.HOPPER;
            case HEAD -> Material.PLAYER_HEAD;
        };
    }

    private static String readable(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
