package net.exylia.lib.replay.internal;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * What a non-player actor looks like beyond its type.
 *
 * <p>A dropped stack drawn without its item is invisible, a splash potion
 * without its item is the default colour, a falling block without its block is
 * sand. These are the few things the client cannot guess and the recording has
 * to carry. Everything else about a mob &mdash; its variant, its collar, its
 * saddle &mdash; is left to the client's defaults, because keeping it would be
 * a copy of the game's own entity model.
 */
@ApiStatus.Internal
public final class Appearance {

    private static final int ITEM = 1;
    private static final int BLOCK = 2;
    private static final int BABY = 4;
    private static final int NAME = 8;
    private static final int NAME_VISIBLE = 16;

    private final @Nullable ItemStack item;
    private final @Nullable BlockData block;
    private final boolean baby;
    private final @Nullable String name;
    private final boolean nameVisible;

    private Appearance(@Nullable ItemStack item, @Nullable BlockData block, boolean baby,
                       @Nullable String name, boolean nameVisible) {
        this.item = item;
        this.block = block;
        this.baby = baby;
        this.name = name;
        this.nameVisible = nameVisible;
    }

    /** Reads it off an entity; {@code null} when there is nothing to keep. */
    @SuppressWarnings("deprecation")
    public static byte @Nullable [] of(Entity entity) {
        try {
            ItemStack item = null;
            if (entity instanceof Item dropped) item = dropped.getItemStack();
            else if (entity instanceof ThrownPotion potion) item = potion.getItem();
            else if (entity instanceof ThrowableProjectile thrown) item = thrown.getItem();
            else if (entity instanceof Firework firework) item = firework.getItem();
            BlockData block = entity instanceof FallingBlock falling ? falling.getBlockData() : null;
            boolean baby = entity instanceof Ageable ageable && !ageable.isAdult();
            String name = entity.getCustomName();
            if (item == null && block == null && !baby && name == null) return null;

            ByteArrayOutputStream raw = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(raw);
            int mask = 0;
            if (item != null && !item.getType().isAir()) mask |= ITEM;
            if (block != null) mask |= BLOCK;
            if (baby) mask |= BABY;
            if (name != null) mask |= NAME;
            if (name != null && entity.isCustomNameVisible()) mask |= NAME_VISIBLE;
            out.writeByte(mask);
            if ((mask & ITEM) != 0) {
                byte[] bytes = item.serializeAsBytes();
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            if ((mask & BLOCK) != 0) out.writeUTF(block.getAsString());
            if ((mask & NAME) != 0) out.writeUTF(name);
            return raw.toByteArray();
        } catch (IOException | RuntimeException unreadable) {
            // A plugin's own item that will not serialise. Drawn by type alone.
            return null;
        }
    }

    /** Reads what {@link #of} wrote; a blank appearance when there is none. */
    static Appearance read(byte @Nullable [] data) {
        if (data == null || data.length == 0) return new Appearance(null, null, false, null, false);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int mask = in.readUnsignedByte();
            ItemStack item = null;
            BlockData block = null;
            String name = null;
            if ((mask & ITEM) != 0) {
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                try {
                    item = ItemStack.deserializeBytes(bytes);
                } catch (RuntimeException gone) {
                    item = null;
                }
            }
            if ((mask & BLOCK) != 0) {
                String state = in.readUTF();
                try {
                    block = Bukkit.createBlockData(state);
                } catch (IllegalArgumentException gone) {
                    block = null;
                }
            }
            if ((mask & NAME) != 0) name = in.readUTF();
            return new Appearance(item, block, (mask & BABY) != 0, name, (mask & NAME_VISIBLE) != 0);
        } catch (IOException broken) {
            return new Appearance(null, null, false, null, false);
        }
    }

    @Nullable ItemStack item() {
        return item;
    }

    @Nullable BlockData block() {
        return block;
    }

    boolean baby() {
        return baby;
    }

    @Nullable String name() {
        return name;
    }

    boolean nameVisible() {
        return nameVisible;
    }
}
