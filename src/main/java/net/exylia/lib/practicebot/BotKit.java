package net.exylia.lib.practicebot;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The gear a bot fights with, sent by whoever started the fight.
 *
 * <h2>Why a whole inventory</h2>
 * A mode ships with a loadout of its own, which is right for the plugin's own
 * {@code /bot}: somebody asking for crystal PvP in a sandbox wants whatever
 * crystal PvP is balanced around. It is wrong for a practice match, where both
 * sides are supposed to be fighting the same kit - the one the server's admins
 * built, with their armour, their enchantments, their potion count. A bot
 * carrying its own idea of a kit is a different fight from the one the arena
 * says it is.
 *
 * <p>So the caller sends the kit verbatim, in the layout Bukkit gives a player:
 * 0-35 storage (0-8 being the hotbar), 36-39 armour boots-first as
 * {@link org.bukkit.inventory.PlayerInventory#getArmorContents()} orders it, 40
 * the off hand. That is the same array a practice plugin already stores per kit,
 * so nothing has to be translated on the way out.
 *
 * <p>What the bot does with it is the bot's business, and deliberately so: it
 * knows which of those items it can hold, which it can drink, and which mean
 * nothing to it. A sender that had to name the main hand would be guessing at
 * the answer to a question the other side is better placed to answer, and would
 * have to be updated every time the bot learns to use something new.
 *
 * @since 1.84.0
 */
public record BotKit(ItemStack[] contents) {

    /** Slots in a player inventory: 36 storage, 4 armour, 1 off hand. */
    public static final int SIZE = 41;

    private static final int ARMOUR_START = 36;
    private static final int OFF_HAND = 40;

    public BotKit {
        Objects.requireNonNull(contents, "contents");
        if (contents.length != SIZE) {
            throw new IllegalArgumentException("a kit is " + SIZE + " slots, got " + contents.length);
        }
        // Copied on the way in and on the way out: a kit is a description of a
        // fight, and a caller that keeps editing the array it handed over is
        // editing a fight that has already started.
        contents = deepCopy(contents);
    }

    /**
     * A kit from a player-layout array.
     *
     * @param playerLayout 41 slots, nulls allowed for empty ones
     */
    public static BotKit of(ItemStack[] playerLayout) {
        return new BotKit(playerLayout);
    }

    /**
     * The kit a player is carrying right now.
     *
     * <p>Handy for "fight me with what I have on", and the reason the layout is
     * the one it is.
     */
    public static BotKit ofInventory(org.bukkit.inventory.PlayerInventory inventory) {
        ItemStack[] layout = new ItemStack[SIZE];
        for (int slot = 0; slot < ARMOUR_START; slot++) {
            layout[slot] = inventory.getItem(slot);
        }
        ItemStack[] armour = inventory.getArmorContents();
        System.arraycopy(armour, 0, layout, ARMOUR_START, Math.min(armour.length, 4));
        layout[OFF_HAND] = inventory.getItemInOffHand();
        return new BotKit(layout);
    }

    /** Everything, in player layout. A copy: editing it changes nothing. */
    @Override
    public ItemStack[] contents() {
        return deepCopy(contents);
    }

    /** Slots 0-35, in order, empties included as null. */
    public List<ItemStack> storage() {
        return copies(Arrays.copyOfRange(contents, 0, ARMOUR_START));
    }

    /** Slots 0-8. Where a weapon worth holding is going to be. */
    public List<ItemStack> hotbar() {
        return copies(Arrays.copyOfRange(contents, 0, 9));
    }

    /** Boots, leggings, chestplate, helmet - the order Bukkit uses. */
    public ItemStack[] armour() {
        return deepCopy(Arrays.copyOfRange(contents, ARMOUR_START, OFF_HAND));
    }

    public ItemStack boots() {
        return copy(contents[ARMOUR_START]);
    }

    public ItemStack leggings() {
        return copy(contents[ARMOUR_START + 1]);
    }

    public ItemStack chestplate() {
        return copy(contents[ARMOUR_START + 2]);
    }

    public ItemStack helmet() {
        return copy(contents[ARMOUR_START + 3]);
    }

    public ItemStack offHand() {
        return copy(contents[OFF_HAND]);
    }

    /**
     * How many of something the kit holds, counting stack sizes.
     *
     * <p>What a bot that models a finite kit runs its resource game on: eight
     * golden apples is a different fight from thirty-two.
     *
     * @param material what to count
     * @return the total amount across every slot
     */
    public int count(Material material) {
        int total = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() == material) total += item.getAmount();
        }
        return total;
    }

    /** Whether the kit holds any of something at all. */
    public boolean has(Material material) {
        return count(material) > 0;
    }

    /** Whether every slot is empty, which is a kit nobody should be sent into. */
    public boolean isEmpty() {
        return Arrays.stream(contents).allMatch(BotKit::blank);
    }

    private static boolean blank(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    private static List<ItemStack> copies(ItemStack[] items) {
        List<ItemStack> list = new ArrayList<>(items.length);
        for (ItemStack item : items) list.add(copy(item));
        return list;
    }

    private static ItemStack[] deepCopy(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) out[i] = copy(items[i]);
        return out;
    }

    private static ItemStack copy(ItemStack item) {
        return blank(item) ? null : item.clone();
    }
}
