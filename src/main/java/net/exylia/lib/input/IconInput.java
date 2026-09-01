package net.exylia.lib.input;

import net.exylia.lib.input.internal.InsertWindow;
import net.exylia.lib.item.Source;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.head.Heads;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Asks somebody what an icon should be.
 *
 * <pre>{@code
 * inputs.icon(player, "{warning}Choose an icon")
 *       .open(icon -> arenas.save(arena.withIcon(icon)));
 * }</pre>
 *
 * <p>The answer is a {@code material} value — the same string a menu file
 * writes, read by the same grammar — so whatever comes back can be stored in a
 * column and put straight into {@code material: "%arena_icon%"}. See
 * {@link Source}.
 *
 * <h2>Four ways, because an icon is four different things</h2>
 * A material is chosen from a list nobody can spell from memory; a custom item
 * is easiest to point at by holding it; a head is a string somebody copied off
 * a texture site and can only be pasted. ExyliaCommons had a menu of its own
 * for each of those, in every plugin that needed one. Here they are four
 * answers to one question:
 *
 * <ul>
 *   <li>{@link Way#MATERIAL} — the {@link SearchInput} picker: the admin types,
 *       the matches refilter as they do, and the list is every item the server
 *       has rather than a hand-picked page.</li>
 *   <li>{@link Way#INSERT} — a window with one slot. Put the item in and that
 *       item, exactly as it is, is the answer: its model, its colour, its
 *       enchantments. It comes straight back to you afterwards.</li>
 *   <li>{@link Way#HEAD} — the one case that has to be pasted, so it is the
 *       only one that asks for text.</li>
 *   <li>{@link Way#BROWSE} — the same picker again, over the head catalogue:
 *       for the head nobody has the base64 of, because they have not found it
 *       yet. Needs the server to reach the catalogue; when it cannot, the
 *       window says so and the other ways are untouched.</li>
 * </ul>
 *
 * <p>Offering one way only skips the question entirely: a plugin that wants a
 * head and nothing else asks for a head.
 *
 * <h2>What ends it</h2>
 * Every ending of {@link InputRequest} is passed through unchanged, so a
 * timeout stays a timeout and a replaced request stays replaced — a caller
 * reopening its menu on a cancel keeps behaving the same way. An empty hand
 * ends as {@link InputOutcome#CANCELLED} after saying so, rather than asking
 * again: there is nothing to ask, the item is either held or it is not.
 *
 * @since 1.51.0
 */
public final class IconInput {

    /**
     * How long an icon may be by default.
     *
     * <p>Every table in the ecosystem that stores one gives it 512 characters,
     * so that is what is checked before an answer is handed back. A serialised
     * item can be longer than that, and finding out at the database is finding
     * out after the screen already said yes.
     */
    public static final int DEFAULT_MAX_LENGTH = 512;

    private final PluginInputs inputs;
    private final Player player;
    private final String prompt;

    private List<Way> ways = List.of(Way.values());
    private int maxLength = DEFAULT_MAX_LENGTH;
    private Duration timeout;

    IconInput(PluginInputs inputs, Player player, String prompt) {
        this.inputs = inputs;
        this.player = Inputs.require(player, "player");
        this.prompt = Inputs.requireText(prompt, "prompt");
    }

    /**
     * Restricts which ways are offered, in the order given.
     *
     * <p>One way is not a question: it is asked directly.
     *
     * @param ways the ways to offer; at least one
     * @return this builder
     */
    public @NotNull IconInput ways(@NotNull Way... ways) {
        if (ways == null || ways.length == 0) {
            throw new InputException("an icon request must offer at least one way");
        }
        List<Way> ordered = new ArrayList<>(ways.length);
        for (Way way : ways) {
            if (way == null) {
                throw new InputException("ways must not contain null");
            }
            if (!ordered.contains(way)) {
                ordered.add(way);
            }
        }
        this.ways = List.copyOf(ordered);
        return this;
    }

    /**
     * Sets how long an answer may be.
     *
     * @param maxLength the limit, in characters; must be positive
     * @return this builder
     */
    public @NotNull IconInput maxLength(int maxLength) {
        if (maxLength <= 0) {
            throw new InputException("maxLength must be positive");
        }
        this.maxLength = maxLength;
        return this;
    }

    /**
     * Sets how long the player has to answer each step.
     *
     * @param timeout the positive timeout
     * @return this builder
     */
    public @NotNull IconInput timeout(@NotNull Duration timeout) {
        this.timeout = Inputs.requirePositive(timeout, "timeout");
        return this;
    }

    /**
     * Asks, and completes once with whatever happened.
     *
     * @return the answer, as a {@code material} value
     */
    public @NotNull CompletionStage<InputResult<String>> open() {
        if (ways.size() == 1) {
            return ask(ways.getFirst());
        }
        return timed(inputs.choice(player, prompt, ways)
                .label(Way::label)
                .key(Enum::name)
                .icon(Way::icon))
                .open()
                .thenCompose(chosen -> chosen.completed()
                        ? ask(chosen.value())
                        : CompletableFuture.completedFuture(ended(chosen)));
    }

    /**
     * The same, running an action only when an icon was chosen.
     *
     * @param completed what to do with the icon
     * @return the answer, as a {@code material} value
     */
    public @NotNull CompletionStage<InputResult<String>> open(
            @NotNull Consumer<? super String> completed) {
        Inputs.require(completed, "completed");
        return open().thenApply(result -> result.ifCompleted(completed));
    }

    /** Asks for the icon itself, whichever way it is being given. */
    private CompletionStage<InputResult<String>> ask(Way way) {
        return switch (way) {
            case MATERIAL -> timed(inputs.search(player, "{warning}Search a material", Items.ALL)
                    .label(Material::name)
                    .key(Material::name)
                    .icon(material -> material))
                    .open()
                    .thenApply(result -> map(result, Material::name));
            case INSERT -> insert();
            case BROWSE -> timed(Heads.browse(inputs, player, "{warning}Browse a head"))
                    .open()
                    .thenApply(result -> result.completed()
                            ? stored(result.value().icon())
                            : ended(result));
            case HEAD -> timed(inputs.text(player, "{warning}Paste a head"
                    + " {muted}(playerhead-Notch, basehead-<base64>, urlhead-<url>)")
                    .validate(IconInput::isHead, "{error}That is not a head.")
                    .validate(value -> value.length() <= maxLength,
                            "{error}That head is too long to store."))
                    .open()
                    .thenApply(result -> map(result, Function.identity()));
        };
    }

    /**
     * Asks for the item in a window with one slot.
     *
     * <p>What this replaced was reading the player's main hand, which meant
     * closing whatever screen you were on, finding the item, holding it and
     * reopening &mdash; and from inside a menu it could not be done at all. A
     * slot works from inside the screen that asked.
     *
     * <p>The item is described, never taken: it goes back to the player on
     * every ending, including the plugin being disabled.
     */
    private CompletionStage<InputResult<String>> insert() {
        return InsertWindow.open(inputs.plugin(), player, prompt)
                .thenApply(inserted -> inserted
                        .map(item -> stored(Source.of(item).raw()))
                        .orElseGet(() -> InputResult.ended(InputOutcome.CANCELLED)));
    }

    /** Checks an icon against the column it is going into before saying yes. */
    private InputResult<String> stored(String icon) {
        if (icon.length() > maxLength) {
            Text.of("{error}That item is too big to store as an icon."
                    + " Pick a material or a head instead.").send(player);
            return InputResult.ended(InputOutcome.CANCELLED);
        }
        return InputResult.completed(icon);
    }

    /** Whether a value names a head, which is all the head prompt accepts. */
    static boolean isHead(String value) {
        Source source = Source.of(value);
        // A head naming a player who does not exist is still a head, and a
        // texture is only checked when it is drawn. What is rejected here is a
        // material name typed into the head prompt.
        return source instanceof Source.OfHead || source instanceof Source.OfHeadTemplate;
    }

    /** Applies the timeout this request was given, if it was given one. */
    private <T, S extends InputRequest<T, S>> S timed(S request) {
        return timeout == null ? request : request.timeout(timeout);
    }

    /** Turns an answer of one type into the icon it stands for. */
    private static <T> InputResult<String> map(InputResult<T> result, Function<T, String> icon) {
        return result.completed()
                ? InputResult.completed(icon.apply(result.value()))
                : ended(result);
    }

    /** Carries an ending across a change of type. */
    private static InputResult<String> ended(InputResult<?> result) {
        return InputResult.ended(result.outcome());
    }

    /** How an icon is being given. */
    public enum Way {

        /** Chosen from every item the server has. */
        MATERIAL("Material", Material.GRASS_BLOCK),

        /**
         * Put in a slot, read exactly as it is, and handed straight back.
         *
         * @since 1.59.0
         */
        INSERT("Insert an item", Material.HOPPER),

        /** A head, pasted as a texture, a URL or a player name. */
        HEAD("A head", Material.PLAYER_HEAD),

        /**
         * Searched for by name in the head catalogue.
         *
         * @since 1.82.0
         */
        BROWSE("Browse a head", Material.SPYGLASS);

        private final String label;
        private final Material icon;

        Way(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }

        /** What this way is called on screen. */
        public @NotNull String label() {
            return label;
        }

        /** What this way is drawn as. */
        public @NotNull Material icon() {
            return icon;
        }
    }

    /**
     * Every material that can be an icon.
     *
     * <p>Its own class so the list is built the first time somebody picks an
     * icon and not when this one is loaded: reading it asks the server's
     * registry what is an item, and a library class must not need a running
     * server to be loaded at all.
     */
    private static final class Items {

        private static final List<Material> ALL = Arrays.stream(Material.values())
                .filter(Material::isItem)
                .filter(material -> !material.isLegacy())
                .filter(material -> material != Material.AIR)
                .toList();

        private Items() {
            throw new AssertionError("No instances.");
        }
    }
}
