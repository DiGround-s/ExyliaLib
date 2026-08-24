package net.exylia.lib.util.editor;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a player copied, waiting to be pasted somewhere else.
 *
 * <p>This is the feature admins actually asked for: a loot table configured on
 * one chest, pasted onto the next twelve. It works across screens, across
 * plugins and across menus being closed and reopened, because it belongs to the
 * player rather than to a session.
 *
 * <pre>{@code
 * Clipboard.copy(player, "loot", List.of(entry));   // the copy button
 * List<LootEntry> pending = Clipboard.take(player, "loot", LootEntry.class);
 * }</pre>
 *
 * <h2>One bucket, not two</h2>
 * ExyliaCommons had a clipboard for one element and a second for a whole list,
 * with four buttons between them. There is one bucket here holding however many
 * elements were copied, so copying one and copying forty are the same button and
 * pasting is the same button — and the paste button can say how many are coming.
 *
 * <h2>Typed by key, not by class</h2>
 * The key comes from {@link EditorDescriptor#typeKey()}, so two editors over the
 * same type can share a clipboard on purpose and two over different types can
 * never paste into each other by accident.
 *
 * <h2>Nothing outlives the player</h2>
 * A player who leaves takes their clipboard with them. It is memory, not
 * storage: what is copied is a handful of small values, and a clipboard that
 * survived a restart would paste last week's rewards into this week's event.
 *
 * @since 1.56.0
 */
public final class Clipboard {

    private static final Map<UUID, Map<String, List<Object>>> BY_PLAYER = new ConcurrentHashMap<>();

    private Clipboard() {
        throw new AssertionError("No instances.");
    }

    /**
     * Puts elements on a player's clipboard, replacing whatever was there.
     *
     * @param player   whose clipboard
     * @param typeKey  which bucket
     * @param elements what was copied; an empty list clears the bucket
     * @param <T>      the element type
     */
    public static <T> void copy(@NotNull Player player, @NotNull String typeKey,
                                @NotNull List<T> elements) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(typeKey, "typeKey");
        Objects.requireNonNull(elements, "elements");
        if (elements.isEmpty()) {
            clear(player, typeKey);
            return;
        }
        BY_PLAYER.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .put(typeKey, List.copyOf(elements));
    }

    /**
     * Reads a bucket without emptying it.
     *
     * <p>Pasting does not consume: an admin pasting the same loot table onto
     * twelve chests presses paste twelve times, and a clipboard that emptied
     * itself would make that eleven trips back to the first chest.
     *
     * @param player  whose clipboard
     * @param typeKey which bucket
     * @param type    what the elements are, checked per element
     * @param <T>     the element type
     * @return the elements, never {@code null}
     */
    public static <T> @NotNull List<T> take(@NotNull Player player, @NotNull String typeKey,
                                            @NotNull Class<T> type) {
        List<Object> stored = bucket(player, typeKey);
        if (stored.isEmpty()) {
            return List.of();
        }
        List<T> elements = new ArrayList<>(stored.size());
        for (Object element : stored) {
            // Checked one by one rather than trusted: a bucket key is a string,
            // and a plugin is free to reuse one for a type we have never seen.
            if (type.isInstance(element)) {
                elements.add(type.cast(element));
            }
        }
        return List.copyOf(elements);
    }

    /**
     * How many elements are waiting in a bucket.
     *
     * @param player  whose clipboard
     * @param typeKey which bucket
     * @return the count, {@code 0} when nothing was copied
     */
    public static int size(@NotNull Player player, @NotNull String typeKey) {
        return bucket(player, typeKey).size();
    }

    /**
     * Whether a bucket holds anything.
     *
     * @param player  whose clipboard
     * @param typeKey which bucket
     * @return whether there is something to paste
     */
    public static boolean has(@NotNull Player player, @NotNull String typeKey) {
        return size(player, typeKey) > 0;
    }

    /**
     * Empties one bucket.
     *
     * @param player  whose clipboard
     * @param typeKey which bucket
     */
    public static void clear(@NotNull Player player, @NotNull String typeKey) {
        Map<String, List<Object>> buckets = BY_PLAYER.get(player.getUniqueId());
        if (buckets == null) {
            return;
        }
        buckets.remove(typeKey);
        if (buckets.isEmpty()) {
            BY_PLAYER.remove(player.getUniqueId(), buckets);
        }
    }

    /**
     * Forgets everything a player copied.
     *
     * <p>Called when they leave. Consumers do not need to call this.
     *
     * @param playerId who left
     */
    public static void forget(@NotNull UUID playerId) {
        BY_PLAYER.remove(playerId);
    }

    /** Forgets every clipboard. Called on shutdown. */
    public static void forgetAll() {
        BY_PLAYER.clear();
    }

    private static List<Object> bucket(Player player, String typeKey) {
        Map<String, List<Object>> buckets = BY_PLAYER.get(player.getUniqueId());
        if (buckets == null) {
            return List.of();
        }
        return buckets.getOrDefault(typeKey, List.of());
    }
}
