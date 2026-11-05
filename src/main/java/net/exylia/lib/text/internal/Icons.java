package net.exylia.lib.text.internal;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Expands {@code {icon:...}} into a game sprite, or into the glyph that stands
 * in for it where sprites cannot be drawn.
 *
 * <p>Written forms:
 * <ul>
 *   <li>{@code {icon:clock}}: a named icon from {@link #NAMED}, with its own
 *       glyph;</li>
 *   <li>{@code {icon:clock|✦}}: the same icon with another glyph;</li>
 *   <li>{@code {icon:items:item/diamond|♦}}: any sprite, as atlas and id;
 *       {@code items}, {@code blocks}, {@code particles} and {@code gui} are
 *       the vanilla atlases, and a namespaced atlas is written in full;</li>
 *   <li>a trailing {@code |tint} draws the sprite in the colour around it
 *       rather than in its own; the named particle icons are tinted already.</li>
 * </ul>
 *
 * <p>Sprites are text objects, added in Minecraft 1.21.9. A server older than
 * that, or whose Adventure has no sprite tag, gets the glyph. A newer server
 * draws the sprite, and the packet layer swaps it for the glyph on its way to
 * a client too old to draw it. The glyph takes the colour around it.
 *
 * <p>Item textures moved from the {@code blocks} atlas to a new {@code items}
 * atlas in 1.21.11; the server's own version decides which one an
 * {@code items} sprite is read from.
 */
public final class Icons {

    /** One icon: where its sprite lives, the glyph for it, and whether it takes the colour around it. */
    public record Icon(String atlas, String sprite, String glyph, boolean tinted) {
    }

    private static final String TOKEN = "icon:";

    /**
     * The named icons. Every sprite here exists from 1.21.9 on; the glyph of
     * each is one the vanilla font draws.
     */
    static final Map<String, Icon> NAMED;

    static {
        Map<String, Icon> named = new LinkedHashMap<>();
        // Time and progress.
        named.put("clock", new Icon("items", "item/clock_00", "⌚", false));
        named.put("xp", new Icon("items", "item/experience_bottle", "✦", false));
        // Combat.
        named.put("sword", new Icon("items", "item/iron_sword", "⚔", false));
        named.put("kills", new Icon("items", "item/diamond_sword", "⚔", false));
        named.put("deaths", new Icon("items", "item/bone", "☠", false));
        named.put("heart", new Icon("gui", "hud/heart/full", "❤", false));
        named.put("armor", new Icon("gui", "hud/armor_full", "⛨", false));
        named.put("food", new Icon("gui", "hud/food_full", "♨", false));
        named.put("apple", new Icon("items", "item/golden_apple", "❤", false));
        named.put("totem", new Icon("items", "item/totem_of_undying", "✦", false));
        named.put("arrow", new Icon("items", "item/arrow", "➹", false));
        named.put("crit", new Icon("particles", "critical_hit", "✦", true));
        // Rank, rewards and economy.
        named.put("star", new Icon("items", "item/nether_star", "✦", false));
        named.put("diamond", new Icon("items", "item/diamond", "♦", false));
        named.put("emerald", new Icon("items", "item/emerald", "♦", false));
        named.put("gold", new Icon("items", "item/gold_ingot", "$", false));
        named.put("coin", new Icon("items", "item/gold_nugget", "$", false));
        named.put("key", new Icon("items", "item/trial_key", "✦", false));
        named.put("shard", new Icon("items", "item/amethyst_shard", "✦", false));
        // Information and navigation.
        named.put("book", new Icon("items", "item/book", "✎", false));
        named.put("quill", new Icon("items", "item/writable_book", "✎", false));
        named.put("paper", new Icon("items", "item/paper", "✉", false));
        named.put("tag", new Icon("items", "item/name_tag", "✎", false));
        named.put("sign", new Icon("items", "item/oak_sign", "✎", false));
        named.put("map", new Icon("items", "item/map", "✉", false));
        named.put("compass", new Icon("items", "item/compass_16", "➤", false));
        named.put("eye", new Icon("items", "item/ender_eye", "◉", false));
        named.put("pearl", new Icon("items", "item/ender_pearl", "◉", false));
        named.put("spyglass", new Icon("items", "item/spyglass", "◉", false));
        named.put("feather", new Icon("items", "item/feather", "➹", false));
        named.put("info", new Icon("gui", "icon/info", "ℹ", false));
        named.put("search", new Icon("gui", "icon/search", "◉", false));
        named.put("check", new Icon("gui", "icon/checkmark", "✔", false));
        named.put("barrier", new Icon("items", "item/barrier", "✘", false));
        named.put("ping", new Icon("gui", "icon/ping_5", "▮", false));
        // Work and settings.
        named.put("pickaxe", new Icon("items", "item/diamond_pickaxe", "⛏", false));
        named.put("redstone", new Icon("items", "item/redstone", "⚡", false));
        named.put("hopper", new Icon("items", "item/hopper", "⬇", false));
        named.put("iron", new Icon("items", "item/iron_ingot", "■", false));
        named.put("blaze", new Icon("items", "item/blaze_powder", "☀", false));
        // Tinted with the colour around them, so they follow the palette.
        named.put("spark", new Icon("particles", "spark_4", "✦", true));
        named.put("glint", new Icon("particles", "glint", "✧", true));
        named.put("note", new Icon("particles", "note", "♪", true));
        named.put("flame", new Icon("particles", "flame", "☀", false));
        NAMED = Collections.unmodifiableMap(named);
    }

    /** Glyphs a downgraded sprite is drawn as, keyed by {@code atlas|sprite} as the server sent it. */
    private static final Map<String, String> FALLBACKS = new ConcurrentHashMap<>();

    private static final boolean SPRITES = net.exylia.lib.platform.Features.sprites();

    private static final boolean ITEMS_ATLAS = serverAtLeast(1, 21, 11);

    private Icons() {
    }

    /** Whether this server draws sprites: Minecraft 1.21.9+ with an Adventure that has the sprite tag. */
    public static boolean sprites() {
        return SPRITES;
    }

    /** Whether any sprite has been written yet, so the packet layer knows there may be one to swap. */
    public static boolean any() {
        return !FALLBACKS.isEmpty();
    }

    /** The named icons, for documentation and checks. */
    public static Set<String> names() {
        return NAMED.keySet();
    }

    /**
     * The glyph for a sprite the server drew, or {@code null} when it did not
     * come from an icon token.
     *
     * @param atlas  the atlas key as sent, such as {@code minecraft:items}
     * @param sprite the sprite key as sent, such as {@code minecraft:item/clock_00}
     */
    public static @Nullable String fallback(String atlas, String sprite) {
        return FALLBACKS.get(atlas + '|' + sprite);
    }

    /**
     * Replaces every icon token in a line with what this server draws.
     *
     * @param text the text, palette tokens not yet expanded
     * @return the text with icons expanded; unknown icons stay as written
     */
    static String expand(String text) {
        int open = text.indexOf("{" + TOKEN);
        if (open < 0) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length() + 32);
        int copied = 0;
        while (open >= 0) {
            int close = text.indexOf('}', open);
            if (close < 0) {
                break;
            }
            String drawn = draw(text.substring(open + 1 + TOKEN.length(), close));
            if (drawn != null) {
                result.append(text, copied, open).append(drawn);
                copied = close + 1;
            }
            open = text.indexOf("{" + TOKEN, close + 1);
        }
        return copied == 0 ? text : result.append(text, copied, text.length()).toString();
    }

    /** What one token draws, or {@code null} when it names nothing. */
    static @Nullable String draw(String body) {
        Icon icon = parse(body);
        if (icon == null) {
            return null;
        }
        if (!SPRITES) {
            return icon.glyph();
        }
        String atlas = atlasKey(icon.atlas());
        String sprite = icon.sprite().contains(":") ? icon.sprite() : "minecraft:" + icon.sprite();
        FALLBACKS.put(atlas + '|' + sprite, icon.glyph());
        String tag = "<sprite:\"" + atlas + "\":" + icon.sprite() + ">";
        return icon.tinted() ? tag : "<white>" + tag + "</white>";
    }

    /** The icon a token names, or {@code null}. */
    static @Nullable Icon parse(String body) {
        String[] parts = body.split("\\|", -1);
        String reference = parts[0].trim();
        if (reference.isEmpty()) {
            return null;
        }
        String glyph = null;
        boolean tint = false;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.equals("tint")) {
                tint = true;
            } else if (glyph == null) {
                glyph = part;
            }
        }
        Icon named = NAMED.get(reference);
        if (named != null) {
            return new Icon(named.atlas(), named.sprite(), glyph == null ? named.glyph() : glyph,
                    named.tinted() || tint);
        }
        int split = reference.lastIndexOf(':');
        if (split <= 0 || split == reference.length() - 1) {
            return null;
        }
        return new Icon(reference.substring(0, split), reference.substring(split + 1),
                glyph == null ? "" : glyph, tint);
    }

    /** The full atlas key, reading {@code items} as {@code blocks} where item textures still live there. */
    static String atlasKey(String atlas) {
        String full = atlas.contains(":") ? atlas : "minecraft:" + atlas;
        return full.equals("minecraft:items") && !ITEMS_ATLAS ? "minecraft:blocks" : full;
    }

    private static boolean serverAtLeast(int major, int minor, int patch) {
        String version;
        try {
            version = org.bukkit.Bukkit.getBukkitVersion();
        } catch (Throwable noServer) {
            return true;
        }
        if (version == null) {
            return true;
        }
        String[] numbers = version.split("-")[0].split("\\.");
        int[] wanted = {major, minor, patch};
        for (int i = 0; i < wanted.length; i++) {
            int have = i < numbers.length ? number(numbers[i]) : 0;
            if (have != wanted[i]) {
                return have > wanted[i];
            }
        }
        return true;
    }

    private static int number(String number) {
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
