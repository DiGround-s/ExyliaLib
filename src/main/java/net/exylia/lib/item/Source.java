package net.exylia.lib.item;

import net.exylia.lib.skull.SkullSource;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Base64;
import java.util.Locale;

/**
 * What object an item starts as, before anything is written on it.
 *
 * <p>Configuration expresses all of this through one {@code material} key, and
 * has done for years:
 *
 * <pre>{@code
 * material: DIAMOND_SWORD                    # a material
 * material: "basehead-eyJ0ZXh0dXJlcyI6..."   # a head, by texture
 * material: "playerhead-%player_name%"       # a head, whose owner depends on the row
 * material: "bytes:rO0ABXNy..."              # a serialised item
 * }</pre>
 *
 * <p>A source is what that string means, worked out once when the file is read
 * rather than guessed with {@code startsWith} on every render. The distinction
 * matters because the four kinds cost wildly different things: a material is a
 * constructor call, a texture head is a cache lookup, and a head whose owner is
 * a placeholder cannot be resolved until somebody is looking at it.
 *
 * @since 1.22.0
 */
public sealed interface Source {

    /**
     * Returns whether this source can produce a different object per viewer.
     *
     * <p>A static source is turned into an item once and shared; a dynamic one
     * is rebuilt for whoever is looking. This is the difference between a menu
     * of decorations costing nothing and costing a render per slot per player.
     *
     * @return {@code true} when the object depends on who is looking
     */
    boolean isDynamic();

    /**
     * The name of this source as a person reads it.
     *
     * <p>For the screens where an admin is choosing an icon and the lore has to
     * say which one is set: {@code NETHER_STAR} reads as {@code Nether Star},
     * and a head reads as what makes it that head rather than as four hundred
     * characters of base64.
     *
     * <pre>{@code
     * Source.of(config.icon()).label();   // "Nether Star"
     * }</pre>
     *
     * <p>This is for a human, never for storage: {@link #raw()} is what goes
     * back into a config or a column. A source whose object depends on the
     * viewer is named by what kind of thing it is, since nobody is looking yet.
     *
     * @return a label to show, never blank
     * @since 1.61.0
     */
    @NotNull String label();

    /**
     * The text this source was written as.
     *
     * @return the original {@code material} value
     */
    @NotNull String raw();

    /**
     * Reads a {@code material} value.
     *
     * <p>Every spelling deployed configurations use is accepted, because there
     * are hundreds of these files and migrating them was never on the table:
     *
     * <table border="1">
     *   <caption>Recognised prefixes</caption>
     *   <tr><th>Written as</th><th>Means</th></tr>
     *   <tr><td>{@code basehead-}, {@code headbase-}</td><td>a base64 texture</td></tr>
     *   <tr><td>{@code urlhead-}, {@code headurl-}</td><td>a skin URL</td></tr>
     *   <tr><td>{@code playerhead-}</td><td>a player name</td></tr>
     *   <tr><td>{@code bytes:}</td><td>a serialised item</td></tr>
     *   <tr><td>anything else</td><td>a material name</td></tr>
     * </table>
     *
     * <p>Both {@code -} and {@code :} separate a prefix from its payload, and
     * the prefix is matched case-insensitively. Neither character appears in a
     * material name, so there is nothing to disambiguate.
     *
     * @param raw the value as written
     * @return what it means
     */
    static @NotNull Source of(@NotNull String raw) {
        String trimmed = raw.trim();
        if (startsWith(trimmed, "bytes:")) {
            return new OfSnapshot(trimmed, trimmed.substring("bytes:".length()));
        }
        int separator = separator(trimmed);
        Kind kind = separator <= 0 ? null : Kind.byPrefix(trimmed.substring(0, separator));
        if (kind == null) {
            return new OfMaterial(trimmed);
        }
        String payload = trimmed.substring(separator + 1);
        // Whose head it is is only known when the row is drawn, so the string
        // is kept whole and resolved per render.
        if (payload.indexOf('%') >= 0) {
            return new OfHeadTemplate(trimmed, kind);
        }
        return new OfHead(trimmed, kind.sourceOf(payload));
    }

    /**
     * Reads an item somebody is holding, so it can be stored as a
     * {@code material} value and drawn again later.
     *
     * <p>This is the other half of {@link #of(String)}, and it exists because
     * every icon picker in the ecosystem needs it: an admin holds the item they
     * want a kit, an arena or a reward to be drawn as, and what gets written to
     * the database has to be readable by the same grammar the menus already
     * speak.
     *
     * <p>A plain item is stored as its material name — {@code STONE}, not four
     * hundred characters of base64 — so the common case stays short enough to
     * fit a column and legible enough to edit by hand. Anything carrying meta,
     * which is to say a textured head or a custom model, is stored whole as a
     * {@code bytes:} snapshot, because that is the only spelling that keeps it.
     *
     * @param item the item, which may be nothing at all
     * @return what to store; never a value {@link #of(String)} cannot read
     */
    static @NotNull Source of(@NotNull ItemStack item) {
        Material type = item.getType();
        // Compared against the constants rather than asked isAir(): that answer
        // comes from the block registry, which only a running server has, and
        // an item module that cannot be tested without one is how the render
        // path went unchecked in the first place.
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            // What ExyliaCommons wrote for an empty hand, and still readable:
            // "AIR" is a material name like any other.
            return new OfMaterial("AIR");
        }
        if (!item.hasItemMeta()) {
            return new OfMaterial(type.name());
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(icon(item).serializeAsBytes());
            return new OfSnapshot("bytes:" + base64, base64);
        } catch (RuntimeException unwritable) {
            // A stack the server cannot serialise. Its type is still true, and
            // an icon that lost its custom model beats an icon that is nothing.
            return new OfMaterial(type.name());
        }
    }

    /**
     * Reads an item whole, name and lore included.
     *
     * <p>The difference from {@link #of(ItemStack)} is what is thrown away, and
     * it is the difference between an icon and a thing. An icon is drawn under
     * a name its own configuration writes, so the one the item was carrying is
     * dead weight and is dropped. A <em>payload</em> — the item a reward
     * actually hands over, the item a kit puts in a slot — is the item itself:
     * the name it was given, the lore under it, its enchantments, its
     * attributes. Storing one through the icon path is how a legendary sword
     * arrives as a plain diamond one.
     *
     * <p>Anything with meta is stored whole as a {@code bytes:} snapshot, which
     * is long: a name and lore written in gradients and palette tokens
     * serialise as component JSON. The caller is the one that knows how much
     * room its column has.
     *
     * @param item the item, as it is
     * @return what to store; never a value {@link #of(String)} cannot read
     * @since 1.111.0
     */
    static @NotNull Source whole(@NotNull ItemStack item) {
        Material type = item.getType();
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            return new OfMaterial("AIR");
        }
        if (!item.hasItemMeta()) {
            return new OfMaterial(type.name());
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(item.serializeAsBytes());
            return new OfSnapshot("bytes:" + base64, base64);
        } catch (RuntimeException unwritable) {
            return new OfMaterial(type.name());
        }
    }

    /**
     * The part of an item that is worth storing as an icon.
     *
     * <p>Everything the item looks like is kept — its model, its colour, its
     * patterns, its glint — and the two things an icon never uses are dropped:
     * the name and the lore. Whatever draws this writes its own, from its own
     * configuration; the ones the item was carrying are never seen again.
     *
     * <p>They are also nearly all of its size. A kit sword's name and lore are
     * gradients and palette tokens, which serialise as component JSON and run
     * to well over a thousand characters — past the 512 an icon column allows,
     * so holding a real kit item and pressing the button answered "that item
     * carries too much" for text that would have been thrown away on the way to
     * the screen.
     *
     * @param item the item as held
     * @return a copy with nothing written on it; the original is untouched
     */
    private static ItemStack icon(ItemStack item) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            return copy;
        }
        meta.displayName(null);
        meta.lore(null);
        copy.setItemMeta(meta);
        return copy;
    }

    /** A plain material, possibly named by a placeholder. */
    record OfMaterial(@NotNull String raw) implements Source {
        @Override
        public boolean isDynamic() {
            return raw.indexOf('%') >= 0;
        }

        @Override
        public @NotNull String label() {
            // A placeholder is shown as written: prettifying %icon_material%
            // would name a material nobody chose.
            return isDynamic() ? raw : words(raw);
        }
    }

    /**
     * A head whose texture is already decided.
     *
     * <p>Free to draw: {@link net.exylia.lib.skull.Skulls} holds it, and a
     * texture or URL never touches the network at all.
     */
    record OfHead(@NotNull String raw, @NotNull SkullSource head) implements Source {
        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public @NotNull String label() {
            return switch (head) {
                case SkullSource.PlayerName player -> player.name() + "'s Head";
                case SkullSource.PlayerId ignored -> "Player Head";
                case SkullSource.Texture ignored -> "Custom Head";
                case SkullSource.Url ignored -> "Custom Head";
            };
        }
    }

    /**
     * A head whose owner is a placeholder, such as
     * {@code playerhead-%player_name%}.
     *
     * <p>The only source that has to be resolved per viewer, and the reason
     * this kind exists separately: knowing that at load time is what lets a
     * menu skip the work for every other slot.
     */
    record OfHeadTemplate(@NotNull String raw, @NotNull Kind kind) implements Source {
        @Override
        public boolean isDynamic() {
            return true;
        }

        @Override
        public @NotNull String label() {
            return kind == Kind.PLAYER ? "Player Head" : "Custom Head";
        }
    }

    /** A serialised item, carried whole in the config. */
    record OfSnapshot(@NotNull String raw, @NotNull String base64) implements Source {
        @Override
        public boolean isDynamic() {
            return false;
        }

        /**
         * The material inside the snapshot, or that it is a custom item.
         *
         * <p>Decoded on demand rather than at parse time: a snapshot is drawn
         * far more often than it is named, and the icon path already has the
         * item. This runs once per screen that asks, not per render.
         */
        @Override
        public @NotNull String label() {
            try {
                ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
                return words(item.getType().name());
            } catch (RuntimeException unreadable) {
                // An icon nobody can name is still an icon somebody set. Saying
                // so beats an exception thrown while drawing a lore line.
                return "Custom Item";
            }
        }
    }

    /** How the payload of a head prefix should be read. */
    enum Kind {
        /** A base64 texture property. */
        TEXTURE,
        /** A skin URL, or its trailing hash. */
        URL,
        /** A player name. */
        PLAYER;

        /**
         * Matches a head prefix.
         *
         * @param prefix the part before the separator
         * @return how to read the payload, or {@code null} when it is not a head
         */
        static Kind byPrefix(String prefix) {
            return switch (prefix.toLowerCase(Locale.ROOT)) {
                case "basehead", "headbase" -> TEXTURE;
                case "urlhead", "headurl" -> URL;
                case "playerhead" -> PLAYER;
                default -> null;
            };
        }

        /**
         * Turns a resolved payload into a skull source.
         *
         * @param payload the part after the prefix
         * @return the source
         */
        public @NotNull SkullSource sourceOf(@NotNull String payload) {
            return switch (this) {
                case TEXTURE -> SkullSource.texture(payload);
                case URL -> SkullSource.url(payload);
                case PLAYER -> SkullSource.player(payload);
            };
        }
    }

    /**
     * Finds the prefix separator.
     *
     * <p>Whichever of {@code -} and {@code :} comes first, since a base64
     * payload contains neither but a URL payload contains both.
     */
    private static int separator(String value) {
        int dash = value.indexOf('-');
        int colon = value.indexOf(':');
        if (dash < 0) {
            return colon;
        }
        if (colon < 0) {
            return dash;
        }
        return Math.min(dash, colon);
    }

    private static boolean startsWith(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * Turns a registry name into words: {@code NETHER_STAR} to
     * {@code Nether Star}.
     *
     * <p>Built in one pass over the characters. The obvious spelling — split,
     * stream, capitalise, join — allocates an array, a stream and a string per
     * word for a value that is nearly always two words long.
     */
    private static String words(String name) {
        StringBuilder label = new StringBuilder(name.length());
        boolean starting = true;
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character == '_') {
                label.append(' ');
                starting = true;
                continue;
            }
            label.append(starting
                    ? Character.toUpperCase(character)
                    : Character.toLowerCase(character));
            starting = false;
        }
        return label.toString();
    }
}
