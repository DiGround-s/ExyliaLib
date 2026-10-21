package net.exylia.lib.schematic.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.HangingSign;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The part of a block entity Bukkit lets a plugin reach without NMS.
 *
 * <p>Bukkit has no generic block-entity NBT API, so the built-in engine keeps
 * what matters for an arena and says so in {@code docs/schematics.md}:
 * <ul>
 *   <li><b>Container contents</b>, every {@link Container}: chests, barrels,
 *   shulker boxes, hoppers, furnaces, dispensers, droppers, brewing stands.
 *   Each item goes through Paper's own versioned item bytes
 *   ({@link ItemStack#serializeAsBytes()} / {@link ItemStack#deserializeBytes}),
 *   which are gzipped vanilla item NBT plus a {@code DataVersion}. The Sponge
 *   file holds vanilla item NBT, so the two are the same compound with or
 *   without that one key, and Paper's data fixer upgrades an item saved by an
 *   older server on the way in.</li>
 *   <li><b>Sign text</b>, both sides, with colour, glow and wax.</li>
 *   <li><b>Spawner type.</b> Delays and ranges go back to the server's
 *   defaults.</li>
 * </ul>
 * Anything else with a block entity — banners' patterns, heads' owners,
 * lecterns' books — keeps its block state and loses its data.
 *
 * <p>Every method here runs on the thread that owns the block or entity.
 */
final class NativeCodec {

    /**
     * The first data version (1.21.5) that stores sign lines as text components
     * in NBT rather than as JSON strings.
     */
    static final int COMPONENTS_AS_NBT = 4325;

    private NativeCodec() {
        throw new AssertionError("No instances.");
    }

    /** The server's data version, which a saved file declares. */
    @SuppressWarnings({"deprecation", "removal"})
    static int dataVersion() {
        // UnsafeValues is deprecated as a whole, and is still the only API that
        // says which data version the server writes.
        return Bukkit.getUnsafe().getDataVersion();
    }

    // ------------------------------------------------------------------
    // Block entities
    // ------------------------------------------------------------------

    /**
     * What to save of a live block entity.
     *
     * @param state the block's state, live or snapshot
     * @param dataVersion the server's data version
     * @return the entry, or {@code null} when it is not one this engine keeps
     * @throws IOException when an item's bytes do not parse
     */
    static SpongeSchematic.@Nullable BlockEntity capture(@NotNull BlockState state, int dataVersion)
            throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        if (state instanceof Container container) {
            Inventory inventory = container.getSnapshotInventory();
            List<Object> items = new ArrayList<>();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.isEmpty()) {
                    continue;
                }
                Map<String, Object> tag = Nbt.gunzip(item.serializeAsBytes());
                tag.remove("DataVersion");
                tag.put("Slot", (byte) slot);
                items.add(tag);
            }
            data.put("Items", new Nbt.ListTag(Nbt.COMPOUND, items));
            return new SpongeSchematic.BlockEntity(blockEntityId(state), data);
        }
        if (state instanceof Sign sign) {
            data.put("is_waxed", (byte) (sign.isWaxed() ? 1 : 0));
            data.put("front_text", side(sign.getSide(Side.FRONT), dataVersion));
            data.put("back_text", side(sign.getSide(Side.BACK), dataVersion));
            return new SpongeSchematic.BlockEntity(blockEntityId(state), data);
        }
        if (state instanceof CreatureSpawner spawner) {
            EntityType type = spawner.getSpawnedType();
            if (type != null) {
                Map<String, Object> entity = new LinkedHashMap<>();
                entity.put("id", type.getKey().toString());
                Map<String, Object> spawnData = new LinkedHashMap<>();
                spawnData.put("entity", entity);
                data.put("SpawnData", spawnData);
            }
            return new SpongeSchematic.BlockEntity(blockEntityId(state), data);
        }
        return null;
    }

    /**
     * Puts a saved block entity back.
     *
     * <p>Dispatched on what the block is <em>now</em> — the paste has already
     * written its state — and not on the saved id, which another tool may have
     * spelled differently. A container is always set to exactly what was
     * saved, emptied first, which is what refills a looted chest whose block
     * state never changed.
     *
     * @param block       the block, already holding the schematic's state
     * @param entry       what was saved
     * @param dataVersion the data version of the file
     * @return {@code false} when part of it could not be restored
     */
    static boolean restore(@NotNull Block block, SpongeSchematic.@NotNull BlockEntity entry,
                           int dataVersion) {
        BlockState state = block.getState();
        Map<String, Object> data = entry.data();
        boolean complete = true;
        if (state instanceof Container container) {
            Inventory inventory = container.getSnapshotInventory();
            inventory.clear();
            for (Map<String, Object> tag : SpongeSchematic.compounds(data.get("Items"))) {
                int slot = (int) SpongeSchematic.number(tag, "Slot", -1) & 0xFF;
                if (slot >= inventory.getSize()) {
                    continue;
                }
                try {
                    inventory.setItem(slot, item(tag, dataVersion));
                } catch (Throwable failure) {
                    complete = false;
                }
            }
        } else if (state instanceof Sign sign) {
            sign.setWaxed(SpongeSchematic.number(data, "is_waxed", 0) != 0);
            Map<String, Object> front = SpongeSchematic.compound(data, "front_text");
            if (front != null) {
                complete &= side(sign.getSide(Side.FRONT), front, dataVersion);
            } else if (data.containsKey("Text1")) {
                // Before 1.20 a sign had one side and four keys.
                SignSide side = sign.getSide(Side.FRONT);
                for (int line = 0; line < 4; line++) {
                    Object text = data.get("Text" + (line + 1));
                    side.line(line, text instanceof String json ? fromJson(json) : Component.empty());
                }
            }
            Map<String, Object> back = SpongeSchematic.compound(data, "back_text");
            if (back != null) {
                complete &= side(sign.getSide(Side.BACK), back, dataVersion);
            }
        } else if (state instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(spawnedType(data));
        } else {
            return true;
        }
        state.update(true, false);
        return complete;
    }

    /**
     * The vanilla block entity id, which is not always the block's id: every
     * sign shares one, every shulker box colour shares one, and a spawner is a
     * {@code mob_spawner}.
     */
    private static String blockEntityId(BlockState state) {
        if (state instanceof Sign) {
            return state instanceof HangingSign ? "minecraft:hanging_sign" : "minecraft:sign";
        }
        if (state instanceof CreatureSpawner) {
            return "minecraft:mob_spawner";
        }
        if (state instanceof ShulkerBox) {
            return "minecraft:shulker_box";
        }
        return state.getType().getKey().toString();
    }

    private static ItemStack item(Map<String, Object> tag, int dataVersion) throws IOException {
        Map<String, Object> copy = new LinkedHashMap<>(tag);
        copy.remove("Slot");
        copy.put("DataVersion", dataVersion);
        return net.exylia.lib.item.internal.ItemBytes.read(Nbt.gzip(copy));
    }

    private static @Nullable EntityType spawnedType(Map<String, Object> data) {
        String id = null;
        Map<String, Object> spawnData = SpongeSchematic.compound(data, "SpawnData");
        if (spawnData != null) {
            Map<String, Object> entity = SpongeSchematic.compound(spawnData, "entity");
            // 1.18 nested the entity one level down; before that it was flat.
            id = SpongeSchematic.string(entity != null ? entity : spawnData, "id");
        }
        if (id == null) {
            id = SpongeSchematic.string(data, "EntityId");
        }
        NamespacedKey key = id == null ? null : NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.ENTITY_TYPE.get(key);
    }

    // ------------------------------------------------------------------
    // Sign text
    // ------------------------------------------------------------------

    private static Map<String, Object> side(SignSide side, int dataVersion) {
        List<Object> messages = new ArrayList<>(4);
        for (Component line : side.lines()) {
            JsonElement json = GsonComponentSerializer.gson().serializeToTree(line);
            messages.add(dataVersion >= COMPONENTS_AS_NBT ? toNbt(json) : json.toString());
        }
        int type = messages.stream().allMatch(String.class::isInstance) ? Nbt.STRING : Nbt.COMPOUND;
        if (type == Nbt.COMPOUND) {
            messages.replaceAll(message -> message instanceof String text ? textCompound(text) : message);
        }
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("messages", new Nbt.ListTag(type, messages));
        DyeColor color = side.getColor();
        tag.put("color", (color == null ? DyeColor.BLACK : color).name().toLowerCase(Locale.ROOT));
        tag.put("has_glowing_text", (byte) (side.isGlowingText() ? 1 : 0));
        return tag;
    }

    private static boolean side(SignSide side, Map<String, Object> tag, int dataVersion) {
        boolean complete = true;
        if (tag.get("messages") instanceof Nbt.ListTag messages) {
            for (int line = 0; line < Math.min(4, messages.items().size()); line++) {
                Object message = messages.items().get(line);
                try {
                    side.line(line, message instanceof String text
                            ? (dataVersion >= COMPONENTS_AS_NBT ? Component.text(text) : fromJson(text))
                            : GsonComponentSerializer.gson().deserializeFromTree(toJson(message)));
                } catch (Throwable failure) {
                    complete = false;
                }
            }
        }
        String color = SpongeSchematic.string(tag, "color");
        if (color != null) {
            try {
                side.setColor(DyeColor.valueOf(color.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                complete = false;
            }
        }
        side.setGlowingText(SpongeSchematic.number(tag, "has_glowing_text", 0) != 0);
        return complete;
    }

    /** A JSON line as vanilla wrote it before 1.21.5; a string that is not JSON is literal text. */
    private static Component fromJson(String json) {
        try {
            return GsonComponentSerializer.gson().deserializeFromTree(JsonParser.parseString(json));
        } catch (RuntimeException notJson) {
            return Component.text(json);
        }
    }

    private static Map<String, Object> textCompound(String text) {
        Map<String, Object> compound = new LinkedHashMap<>();
        compound.put("text", text);
        return compound;
    }

    /**
     * A text component's JSON as 1.21.5 NBT.
     *
     * <p>A JSON array can mix strings and objects; an NBT list cannot, so a
     * mixed one has its strings promoted to {@code {text: ...}}, which is the
     * same component.
     */
    static Object toNbt(JsonElement json) {
        if (json instanceof JsonObject object) {
            Map<String, Object> compound = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                compound.put(entry.getKey(), toNbt(entry.getValue()));
            }
            return compound;
        }
        if (json instanceof JsonArray array) {
            List<Object> items = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                items.add(toNbt(element));
            }
            int type = items.isEmpty() ? Nbt.END : Nbt.typeOf(items.get(0));
            boolean uniform = items.stream().allMatch(item -> Nbt.typeOf(item) == Nbt.typeOf(items.get(0)));
            if (!uniform) {
                items.replaceAll(item -> item instanceof Map<?, ?> ? item : textCompound(String.valueOf(item)));
                type = Nbt.COMPOUND;
            }
            return new Nbt.ListTag(type, items);
        }
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return (byte) (primitive.getAsBoolean() ? 1 : 0);
        }
        if (primitive.isNumber()) {
            double value = primitive.getAsDouble();
            return value == Math.rint(value) && Math.abs(value) <= Integer.MAX_VALUE
                    ? (Object) (int) value : (Object) value;
        }
        return primitive.getAsString();
    }

    /**
     * 1.21.5 NBT back to a component's JSON.
     *
     * <p>A byte is a boolean: the only bytes a text component has are its
     * style flags, which vanilla writes as {@code 1b}.
     */
    static JsonElement toJson(Object nbt) {
        if (nbt instanceof Map<?, ?> compound) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : compound.entrySet()) {
                object.add((String) entry.getKey(), toJson(entry.getValue()));
            }
            return object;
        }
        if (nbt instanceof Nbt.ListTag list) {
            JsonArray array = new JsonArray();
            for (Object item : list.items()) {
                array.add(toJson(item));
            }
            return array;
        }
        if (nbt instanceof Byte flag) {
            return new JsonPrimitive(flag != 0);
        }
        if (nbt instanceof Number number) {
            return new JsonPrimitive(number);
        }
        return new JsonPrimitive(String.valueOf(nbt));
    }

    // ------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------

    /**
     * What to save of a loose entity, through Paper's versioned entity bytes.
     *
     * @return the entry, positioned relative to the box's minimum corner
     * @throws IOException when the bytes do not parse
     */
    @SuppressWarnings({"deprecation", "removal"})
    static SpongeSchematic.@Nullable EntityEntry capture(@NotNull Entity entity, int minX, int minY,
                                                         int minZ) throws IOException {
        Map<String, Object> data = Nbt.gunzip(Bukkit.getUnsafe().serializeEntity(entity));
        Object id = data.remove("id");
        if (!(id instanceof String type)) {
            return null;
        }
        data.remove("DataVersion");
        data.remove("Pos");
        // A copy is a new entity: two arenas pasted from one file must not
        // fight over one UUID.
        data.remove("UUID");
        Location at = entity.getLocation();
        return new SpongeSchematic.EntityEntry(type, at.getX() - minX, at.getY() - minY,
                at.getZ() - minZ, data);
    }

    /**
     * Spawns a saved entity.
     *
     * @param world       the world to spawn in
     * @param entry       what was saved
     * @param x           its absolute position
     * @param y           its absolute position
     * @param z           its absolute position
     * @param dataVersion the data version of the file
     * @throws IOException when the entry cannot be re-encoded
     */
    @SuppressWarnings({"deprecation", "removal"})
    static void spawn(@NotNull World world, SpongeSchematic.@NotNull EntityEntry entry,
                      double x, double y, double z, int dataVersion) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>(entry.data());
        data.put("id", entry.id());
        data.put("Pos", new Nbt.ListTag(Nbt.DOUBLE, List.of(x, y, z)));
        data.put("DataVersion", dataVersion);
        float yaw = 0;
        float pitch = 0;
        if (data.get("Rotation") instanceof Nbt.ListTag rotation && rotation.items().size() == 2
                && rotation.items().get(0) instanceof Float first
                && rotation.items().get(1) instanceof Float second) {
            yaw = first;
            pitch = second;
        }
        Entity entity = Bukkit.getUnsafe().deserializeEntity(Nbt.gzip(data), world);
        entity.spawnAt(new Location(world, x, y, z, yaw, pitch));
    }
}
