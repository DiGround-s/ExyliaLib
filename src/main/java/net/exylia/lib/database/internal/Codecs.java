package net.exylia.lib.database.internal;

import net.exylia.lib.database.Codec;
import org.bukkit.Bukkit;
import net.exylia.lib.util.teleport.ExyliaLocation;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The codecs the library ships with.
 *
 * <h2>Why these formats and not better ones</h2>
 * Every one of these reproduces byte for byte what ExyliaCommons wrote. There
 * are millions of rows already stored in these formats across the ecosystem's
 * ninety-six tables, and a server swapping one library for the other must read
 * them unchanged. A tidier encoding would be a data migration, which is a
 * different and much more dangerous job than a library upgrade.
 *
 * <p>Where a format is genuinely poor — {@code Location} losing precision below
 * two decimals — it is kept anyway and the loss is documented, because the rows
 * that already exist have already lost it.
 */
public final class Codecs {

    /**
     * How many decimals a stored {@code Location} keeps.
     *
     * <p>Commons wrote {@code %.2f}, so every coordinate already in a database
     * is rounded to a centimetre. Writing more precision now would produce rows
     * that differ from the old ones for the same location, which is exactly the
     * sort of difference that turns into "the spawn moved slightly".
     */
    private static final String LOCATION_FORMAT = "%s,%.2f,%.2f,%.2f,%.2f,%.2f";

    private static final Map<Class<?>, Codec<?>> BUILT_IN = new ConcurrentHashMap<>();

    private Codecs() {
    }

    static {
        register(UUID.class, Codec.of(UUID::toString, Codecs::parseUuid));
        register(ItemStack.class, Codec.of(Codecs::encodeItem, Codecs::decodeItem));
        register(ItemStack[].class, Codec.of(Codecs::encodeItems, Codecs::decodeItems));
        register(Location.class, Codec.of(Codecs::encodeLocation, Codecs::decodeLocation));
        // A place that knows its server. Stored as ExyliaLocation writes it,
        // {@code server,world,x,y,z,yaw,pitch}, and read back from that or
        // from the six-part Location format above, so a column that used to
        // be a Location can become one of these without touching its rows.
        register(ExyliaLocation.class, Codec.of(ExyliaLocation::toString, Codecs::decodePlace));
    }

    private static <T> void register(Class<T> type, Codec<T> codec) {
        BUILT_IN.put(type, codec);
    }

    /** The built-in codec for a type, or {@code null} when there is none. */
    @SuppressWarnings("unchecked")
    public static <T> Codec<T> builtIn(Class<T> type) {
        Codec<T> exact = (Codec<T>) BUILT_IN.get(type);
        if (exact != null) {
            return exact;
        }
        // An enum is stored by name rather than by ordinal: reordering the
        // constants of an enum is a normal refactor, and with ordinals it
        // silently reinterprets every stored row as a different value.
        if (type.isEnum()) {
            return enumCodec(type);
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Codec<T> enumCodec(Class<T> type) {
        return Codec.of(
                value -> ((Enum<?>) value).name(),
                stored -> {
                    try {
                        return (T) Enum.valueOf((Class<Enum>) type, stored);
                    } catch (IllegalArgumentException unknown) {
                        // A constant that no longer exists. Reported by the
                        // caller as an unreadable value rather than throwing,
                        // so one stale row does not take a whole load down.
                        return null;
                    }
                });
    }

    private static UUID parseUuid(String stored) {
        try {
            return UUID.fromString(stored);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    // ------------------------------------------------------------- ItemStack

    /**
     * One item, as Commons wrote it.
     *
     * <p>{@code serializeAsBytes} is Bukkit's own versioned format, so an item
     * saved on one Minecraft version still reads on the next. An air item
     * encodes as absent, matching Commons and saving a row's worth of text for
     * every empty slot.
     */
    private static String encodeItem(ItemStack value) {
        if (value.getType().isAir()) {
            return null;
        }
        return Base64.getEncoder().encodeToString(value.serializeAsBytes());
    }

    private static ItemStack decodeItem(String stored) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(stored));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * A whole inventory, as Commons wrote it.
     *
     * <p>The format is a {@code BukkitObjectOutputStream}: a length, then each
     * item written as a Java object. It is not the same as encoding each item
     * with {@link #encodeItem} and joining them, which is why it has its own
     * codec rather than falling out of the list handling.
     */
    private static String encodeItems(ItemStack[] value) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             org.bukkit.util.io.BukkitObjectOutputStream out =
                     new org.bukkit.util.io.BukkitObjectOutputStream(bytes)) {
            out.writeInt(value.length);
            for (ItemStack item : value) {
                out.writeObject(item);
            }
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception unwritable) {
            return null;
        }
    }

    /**
     * Reads an inventory back.
     *
     * <p>A slot that will not read becomes empty rather than taking the whole
     * inventory with it. Commons substituted a stone block, which is worse: a
     * player opening their kit cannot tell a corrupted slot from one somebody
     * really did put stone in.
     */
    private static ItemStack[] decodeItems(String stored) {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(stored));
             org.bukkit.util.io.BukkitObjectInputStream in =
                     new org.bukkit.util.io.BukkitObjectInputStream(bytes)) {
            int length = in.readInt();
            if (length < 0 || length > 1024) {
                return null;
            }
            ItemStack[] items = new ItemStack[length];
            for (int index = 0; index < length; index++) {
                try {
                    items[index] = (ItemStack) in.readObject();
                } catch (Exception unreadableSlot) {
                    items[index] = null;
                }
            }
            return items;
        } catch (Exception unreadable) {
            return null;
        }
    }

    // ---------------------------------------------------------- ExyliaLocation

    /** Reads a place; an unreadable one is {@code null}, like an unreadable Location. */
    private static ExyliaLocation decodePlace(String stored) {
        try {
            return ExyliaLocation.fromString(stored);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    // -------------------------------------------------------------- Location

    /**
     * A location, as Commons wrote it: {@code world,x,y,z,yaw,pitch}.
     *
     * <p>Formatted in {@link Locale#ROOT} on purpose. Commons used the default
     * locale, so on a server running in a locale that writes decimals with a
     * comma this produced {@code world,10,50,20,50,30,00,0,00,0,00} — a string
     * that reads back as a different place, or not at all. Rows written that
     * way are already broken; rows written from here never are.
     */
    private static String encodeLocation(Location value) {
        World world = value.getWorld();
        if (world == null) {
            return null;
        }
        return String.format(Locale.ROOT, LOCATION_FORMAT, world.getName(),
                value.getX(), value.getY(), value.getZ(), value.getYaw(), value.getPitch());
    }

    /**
     * Reads a location back.
     *
     * <p>A world that is not loaded yields nothing rather than a location whose
     * world is null, which is the value that turns into a
     * {@code NullPointerException} several frames away from the cause.
     */
    private static Location decodeLocation(String stored) {
        String[] parts = stored.split(",");
        if (parts.length < 4) {
            return null;
        }
        World world = findWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            return new Location(world,
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    parts.length > 4 ? Float.parseFloat(parts[4]) : 0f,
                    parts.length > 5 ? Float.parseFloat(parts[5]) : 0f);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /**
     * Finds a world by the name a row stored.
     *
     * <p>Falls back to a case-insensitive match, as Commons did: worlds have
     * been renamed between capitalisations often enough that the rows exist.
     */
    private static World findWorld(String name) {
        World exact = Bukkit.getWorld(name);
        if (exact != null) {
            return exact;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getName().equalsIgnoreCase(name)) {
                return world;
            }
        }
        return null;
    }
}
