package net.exylia.lib.objectives;

import net.exylia.lib.objectives.internal.ObjectiveRuntime;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Objective tracking shared by every plugin: one set of listeners turns what
 * players do into {@link ObjectiveProgress}, handed only to the subscribers
 * that want that player now.
 *
 * <pre>{@code
 * Objectives.of(this).listen(ObjectiveType.KILL_MOB, wants, progress -> {
 *     if (progress.farmed()) return;
 *     if (Objectives.matches(step.filter(), progress.thing())) step.add(progress.amount());
 * });
 * Objectives.fire(player, "exylia:mines:break", "gold", 1);   // a plugin's own objective
 * }</pre>
 *
 * <p>The listeners are registered the first time anybody subscribes, so a
 * server with no objective plugin pays nothing. Guide: {@code docs/objectives.md}.
 *
 * @since 1.620.0
 */
public final class Objectives {

    private static final Map<String, PluginObjectives> BY_PLUGIN = new ConcurrentHashMap<>();

    private Objectives() {
    }

    /** One plugin's subscriptions; released when the plugin is disabled. */
    public static @NotNull PluginObjectives of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new PluginObjectives(plugin));
    }

    /**
     * Fires a {@link ObjectiveType#CUSTOM} objective, for something only the
     * calling plugin knows about (a mine broken, a dungeon cleared). From any
     * thread: subscribers get it on the player's thread.
     *
     * @param customType what happened, namespaced ({@code exylia:mines:break})
     * @param thing      what it was about ({@code gold}), or empty
     * @param amount     how many, at least 1
     */
    public static void fire(@NotNull Player player, @NotNull String customType, @NotNull String thing, int amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(customType, "customType");
        Objects.requireNonNull(thing, "thing");
        ObjectiveRuntime.fire(player, customType, thing, amount);
    }

    /**
     * Whether a filter accepts a thing, ignoring case and a {@code minecraft:}
     * prefix on either side.
     *
     * <ul>
     *   <li>{@code *} or empty: anything</li>
     *   <li>{@code diamond_ore}: that one</li>
     *   <li>{@code *_ore}, {@code oak_?og}: a glob, {@code *} any run and {@code ?} one character</li>
     *   <li>{@code #minecraft:logs}: a block, item or entity type tag</li>
     * </ul>
     */
    public static boolean matches(@Nullable String filter, @Nullable String thing) {
        String want = normal(filter);
        if (want.isEmpty() || want.equals("*")) return true;
        if (thing == null) return false;
        String got = normal(thing);
        if (want.startsWith("#")) return tagged(want.substring(1), got);
        if (want.indexOf('*') < 0 && want.indexOf('?') < 0) return want.equals(got);
        return glob(want, got);
    }

    /** Whether any filter accepts the thing; an empty list accepts anything. */
    public static boolean matchesAny(@NotNull Collection<String> filters, @Nullable String thing) {
        if (filters.isEmpty()) return true;
        for (String filter : filters) {
            if (matches(filter, thing)) return true;
        }
        return false;
    }

    /** A {@link ObjectiveType#RUN_COMMAND} line, compared by whole-word prefix, ignoring case and the leading slash. */
    public static boolean command(@Nullable String expected, @NotNull String typed) {
        String want = command(expected);
        String got = command(typed);
        return !want.isEmpty() && (got.equals(want) || got.startsWith(want + " "));
    }

    /** A {@link ObjectiveType#CHAT} line that contains the phrase, ignoring case. */
    public static boolean chat(@Nullable String phrase, @NotNull String message) {
        if (phrase == null || phrase.isEmpty()) return false;
        return message.toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT));
    }

    /**
     * A {@link ObjectiveType#CUSTOM} key, ignoring case. The fired key may be
     * more specific than the expected one, never less: {@code exylia:mines:break}
     * counts {@code exylia:mines:break:gold}.
     */
    public static boolean key(@Nullable String expected, @NotNull String fired) {
        if (expected == null) return false;
        String want = expected.toLowerCase(Locale.ROOT);
        String got = fired.toLowerCase(Locale.ROOT);
        return got.equals(want) || got.startsWith(want + ":");
    }

    /** An {@link ObjectiveType#INTERACT_NPC} id, ignoring case; {@code plugin:id} matches the bare id too. */
    public static boolean npc(@Nullable String expected, @NotNull String clicked) {
        if (expected == null) return false;
        String want = expected.toLowerCase(Locale.ROOT);
        String got = clicked.toLowerCase(Locale.ROOT);
        return got.equals(want) || got.endsWith(":" + want) || want.endsWith(":" + got);
    }

    /** Drops a disabled plugin's subscriptions. Called by the library. */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
        ObjectiveRuntime.release(pluginName);
    }

    private static String normal(@Nullable String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("minecraft:")) value = value.substring(10);
        return value;
    }

    private static String command(@Nullable String line) {
        if (line == null) return "";
        String trimmed = line.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    /** {@code *} and {@code ?} only, no regex, so a filter never costs more than its length times the thing's. */
    static boolean glob(String pattern, String text) {
        int p = 0, t = 0, star = -1, mark = 0;
        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(t))) {
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = t;
            } else if (star >= 0) {
                p = star + 1;
                t = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    private static boolean tagged(String tag, String thing) {
        NamespacedKey key = NamespacedKey.fromString(tag);
        if (key == null) return false;
        String upper = thing.toUpperCase(Locale.ROOT);
        try {
            Material material = Material.getMaterial(upper);
            if (material != null) {
                Tag<Material> blocks = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (blocks != null && blocks.isTagged(material)) return true;
                Tag<Material> items = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
                return items != null && items.isTagged(material);
            }
            EntityType type = EntityType.valueOf(upper);
            Tag<EntityType> entities = Bukkit.getTag(Tag.REGISTRY_ENTITY_TYPES, key, EntityType.class);
            return entities != null && entities.isTagged(type);
        } catch (IllegalArgumentException | NullPointerException | UnsupportedOperationException notKnown) {
            return false;
        }
    }
}
