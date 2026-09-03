package net.exylia.lib.util.snapshot.internal;

import net.exylia.lib.util.snapshot.Snapshot;
import net.exylia.lib.util.snapshot.SnapshotCodec;
import net.exylia.lib.util.snapshot.SnapshotPart;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reading a live player into a snapshot, and writing one back.
 *
 * <p>Everything here runs on the thread that owns the player. Nothing here
 * touches the database or a scheduler: this is the one place that talks to
 * Bukkit, which is what lets the codec, the store and the row all be tested
 * without a server.
 */
@ApiStatus.Internal
public final class PlayerState {

    private PlayerState() {
        throw new AssertionError("No instances.");
    }

    /**
     * The attribute that holds maximum health, resolved once and guarded.
     *
     * <p>It was {@code GENERIC_MAX_HEALTH} before 1.21 and {@code MAX_HEALTH}
     * after, and in 1.21 {@code Attribute} stopped being an enum, so a wrong
     * guess is an {@code IncompatibleClassChangeError} at runtime rather than a
     * compile error. Resolving it here means a server where the lookup fails
     * loses the health part of a restore instead of the whole restore.
     */
    private static final @Nullable Attribute MAX_HEALTH = resolveMaxHealth();

    private static @Nullable Attribute resolveMaxHealth() {
        try {
            return Attribute.MAX_HEALTH;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /**
     * Every attribute this server has, resolved once.
     *
     * <p>Read from the registry rather than {@code Attribute.values()}, which
     * stopped existing when the type stopped being an enum. A server that
     * cannot answer at all leaves this empty, and a snapshot taken there simply
     * carries no attributes rather than failing to be taken.
     */
    private static final List<Attribute> ATTRIBUTES = resolveAttributes();

    private static List<Attribute> resolveAttributes() {
        try {
            List<Attribute> all = new ArrayList<>();
            for (Attribute attribute : Registry.ATTRIBUTE) {
                all.add(attribute);
            }
            return List.copyOf(all);
        } catch (RuntimeException | LinkageError unavailable) {
            return List.of();
        }
    }

    /** An attribute's instance on this player, or {@code null} when it has none. */
    private static @Nullable AttributeInstance instanceOf(Player player, Attribute attribute) {
        try {
            return player.getAttribute(attribute);
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    // ------------------------------------------------------------- capturing

    /**
     * Reads a player exactly as they are.
     *
     * @param player the player
     * @return their state
     */
    public static @NotNull Snapshot capture(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();

        ItemStack offHand = inventory.getItemInOffHand();
        if (SnapshotCodec.isEmpty(offHand)) {
            // An empty off hand is AIR rather than null, and storing AIR would
            // write a Base64 string for nothing in every row on the server.
            offHand = null;
        }

        List<Snapshot.Effect> effects = new ArrayList<>(captureEffects(player));

        Vector velocity = player.getVelocity();
        Snapshot.Physical physical = new Snapshot.Physical(
                player.getFireTicks(), player.getRemainingAir(),
                velocity.getX(), velocity.getY(), velocity.getZ(),
                player.getWalkSpeed(), player.isInvulnerable(), player.isGlowing());

        return new Snapshot(
                player.getGameMode(),
                // The same sizes ExyliaCommons captured, so a row written here
                // and read by a server still running it lines up slot for slot.
                Arrays.copyOf(inventory.getContents(), Snapshot.INVENTORY_SLOTS),
                Arrays.copyOf(inventory.getArmorContents(), Snapshot.ARMOR_SLOTS),
                offHand,
                player.getEnderChest().getContents().clone(),
                player.getHealth(),
                baseMaxHealth(player),
                player.getFoodLevel(), player.getSaturation(),
                player.getLevel(), player.getExp(),
                effects,
                player.getAllowFlight(), player.isFlying(), player.getFlySpeed(),
                physical,
                captureAttributes(player));
    }

    /**
     * Reads the attributes a player is not at the default of.
     *
     * <p>Only the ones that were changed, because that is the whole of what has
     * to be written down: an attribute nobody touched goes back to its default,
     * and storing thirty defaults per player would grow every row on the server
     * to say nothing.
     *
     * @return the changed base values, or {@code null} when this server has no
     *         attribute registry to read
     */
    private static @Nullable Map<String, Double> captureAttributes(Player player) {
        if (ATTRIBUTES.isEmpty()) {
            return null;
        }
        Map<String, Double> changed = new LinkedHashMap<>();
        for (Attribute attribute : ATTRIBUTES) {
            AttributeInstance instance = instanceOf(player, attribute);
            if (instance == null) {
                continue;
            }
            try {
                double base = instance.getBaseValue();
                if (base != instance.getDefaultValue()) {
                    changed.put(attribute.getKey().toString(), base);
                }
            } catch (RuntimeException | LinkageError unreadable) {
                // One attribute this server will not talk about costs that
                // attribute. The player still gets everything else back.
            }
        }
        return changed;
    }

    /**
     * Reads the active effects by the name the column stores.
     *
     * <p>{@code getName} is deprecated in favour of the registry key, and it is
     * still the right call: the stored name is what ExyliaCommons wrote and what
     * every existing row holds. Writing a key instead would make this library's
     * rows unreadable by the plugins still on commons, in a table both are using
     * at the same time during a migration.
     */
    @SuppressWarnings("deprecation")
    private static List<Snapshot.Effect> captureEffects(Player player) {
        List<Snapshot.Effect> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(new Snapshot.Effect(effect.getType().getName(), effect.getDuration(),
                    effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(),
                    effect.hasIcon()));
        }
        return effects;
    }

    /**
     * Resolves a stored effect name, or {@code null} when this server has none.
     *
     * <p>Deprecated for the same reason and kept for the same one: the name in
     * the row is a name, and looking it up by anything else would fail to find
     * effects that are perfectly present.
     */
    @SuppressWarnings("deprecation")
    private static @Nullable PotionEffectType effectTypeOf(String name) {
        try {
            return PotionEffectType.getByName(name);
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static double baseMaxHealth(Player player) {
        AttributeInstance attribute = MAX_HEALTH == null ? null : player.getAttribute(MAX_HEALTH);
        // Zero means "unknown" to the restore below, which is what a server
        // that cannot answer the question should say rather than twenty.
        return attribute == null ? 0.0d : attribute.getBaseValue();
    }

    // -------------------------------------------------------------- restoring

    /**
     * Writes the chosen parts of a snapshot back onto a player.
     *
     * @param snapshot the snapshot
     * @param player   the player
     * @param parts    which parts to put back
     */
    public static void apply(@NotNull Snapshot snapshot, @NotNull Player player,
                             @NotNull Set<SnapshotPart> parts) {
        apply(snapshot, player, parts, problem -> { });
    }

    /**
     * The same, saying what it had to skip.
     *
     * <p>Each part is applied on its own. A part that cannot be applied &mdash;
     * a potion effect this server has never heard of, an attribute that moved
     * &mdash; costs that part and is reported; ExyliaCommons let it throw out of
     * {@code applyToPlayer} and take the rest of the restore with it.
     *
     * @param snapshot the snapshot
     * @param player   the player
     * @param parts    which parts to put back
     * @param problems told about each part that had to be skipped
     */
    public static void apply(@NotNull Snapshot snapshot, @NotNull Player player,
                             @NotNull Set<SnapshotPart> parts,
                             @NotNull Consumer<String> problems) {
        PlayerInventory inventory = player.getInventory();
        boolean touchedInventory = false;

        if (wanted(snapshot, parts, SnapshotPart.INVENTORY)) {
            ItemStack[] contents = new ItemStack[Snapshot.INVENTORY_SLOTS];
            ItemStack[] stored = snapshot.inventory();
            System.arraycopy(stored, 0, contents, 0,
                    Math.min(stored.length, Snapshot.INVENTORY_SLOTS));
            inventory.setContents(contents);
            touchedInventory = true;
        }
        if (wanted(snapshot, parts, SnapshotPart.ARMOR)) {
            inventory.setArmorContents(snapshot.armor());
            touchedInventory = true;
        }
        if (parts.contains(SnapshotPart.OFF_HAND)) {
            // Unlike the others, an absent off hand is meaningful: it is an
            // empty hand, and a player who put the snapshot's items back while
            // keeping whatever they happen to be holding is holding something
            // the snapshot says they did not have.
            inventory.setItemInOffHand(snapshot.offHand());
            touchedInventory = true;
        }
        if (wanted(snapshot, parts, SnapshotPart.ENDER_CHEST)) {
            Inventory enderChest = player.getEnderChest();
            ItemStack[] stored = snapshot.enderChest();
            ItemStack[] contents = new ItemStack[enderChest.getSize()];
            System.arraycopy(stored, 0, contents, 0, Math.min(stored.length, contents.length));
            enderChest.setContents(contents);
        }

        if (wanted(snapshot, parts, SnapshotPart.ATTRIBUTES)) {
            // Before health, which owns the maximum health attribute and has to
            // be the one that sets it.
            applyAttributes(snapshot.attributes(), player, problems);
        }
        if (parts.contains(SnapshotPart.HEALTH)) {
            applyHealth(snapshot, player, problems);
        }
        if (parts.contains(SnapshotPart.HUNGER)) {
            player.setFoodLevel(snapshot.foodLevel());
            player.setSaturation(snapshot.saturation());
        }
        if (parts.contains(SnapshotPart.EXPERIENCE)) {
            player.setLevel(snapshot.level());
            player.setExp(snapshot.exp());
        }
        if (parts.contains(SnapshotPart.POTION_EFFECTS)) {
            applyEffects(snapshot, player, problems);
        }
        if (wanted(snapshot, parts, SnapshotPart.GAME_MODE)) {
            player.setGameMode(snapshot.gameMode());
        }
        if (parts.contains(SnapshotPart.FLIGHT)) {
            player.setAllowFlight(snapshot.allowFlight());
            // Flying without permission to fly drops the player out of the sky
            // on the next client tick, so the pair is applied as a pair.
            player.setFlying(snapshot.flying() && snapshot.allowFlight());
            if (snapshot.flySpeed() > 0f) {
                player.setFlySpeed(snapshot.flySpeed());
            }
        }
        if (wanted(snapshot, parts, SnapshotPart.PHYSICAL)) {
            applyPhysical(snapshot.physical(), player);
        }

        if (touchedInventory) {
            player.updateInventory();
        }
    }

    /** Whether a part was asked for and the snapshot actually carries it. */
    private static boolean wanted(Snapshot snapshot, Set<SnapshotPart> parts, SnapshotPart part) {
        return parts.contains(part) && snapshot.has(part);
    }

    private static void applyHealth(Snapshot snapshot, Player player, Consumer<String> problems) {
        AttributeInstance attribute = MAX_HEALTH == null ? null : player.getAttribute(MAX_HEALTH);
        if (attribute == null) {
            problems.accept("maximum health could not be restored: this server has no"
                    + " max health attribute under either of the names it has had");
        } else if (snapshot.maxHealth() > 0.0d) {
            attribute.setBaseValue(snapshot.maxHealth());
        }
        double ceiling = attribute == null ? snapshot.maxHealth() : attribute.getValue();
        double health = snapshot.health();
        if (ceiling > 0.0d) {
            health = Math.min(health, ceiling);
        }
        // Zero would kill them. A snapshot that says zero health was taken of a
        // player who was already dying, and the restore that follows a death is
        // the one place where obeying it literally is never what was meant.
        if (health > 0.0d) {
            player.setHealth(health);
        }
    }

    /**
     * Puts every attribute back where it was.
     *
     * <p>Including the ones the snapshot does not mention: they were at their
     * default when it was taken, so that is where they go. This is the half
     * that matters &mdash; a minigame that shrank the player wrote an attribute
     * the snapshot has nothing to say about, and leaving it alone is what left
     * knee-high players walking around the lobby.
     *
     * <p>Base values only. A modifier another plugin added is untouched.
     */
    private static void applyAttributes(Map<String, Double> stored, Player player,
                                        Consumer<String> problems) {
        for (Attribute attribute : ATTRIBUTES) {
            AttributeInstance instance = instanceOf(player, attribute);
            if (instance == null) {
                continue;
            }
            try {
                Double base = stored.get(attribute.getKey().toString());
                double target = base == null ? instance.getDefaultValue() : base;
                if (instance.getBaseValue() != target) {
                    instance.setBaseValue(target);
                }
            } catch (RuntimeException | LinkageError refused) {
                problems.accept("the attribute \"" + attribute.getKey()
                        + "\" could not be restored, and was left as it is");
            }
        }
    }

    private static void applyEffects(Snapshot snapshot, Player player, Consumer<String> problems) {
        Collection<PotionEffect> active = player.getActivePotionEffects();
        for (PotionEffect effect : List.copyOf(active)) {
            player.removePotionEffect(effect.getType());
        }
        for (Snapshot.Effect stored : snapshot.potionEffects()) {
            PotionEffectType type = effectTypeOf(stored.type());
            if (type == null) {
                problems.accept("the potion effect \"" + stored.type()
                        + "\" is not one this server has, and was skipped");
                continue;
            }
            player.addPotionEffect(new PotionEffect(type, stored.duration(), stored.amplifier(),
                    stored.ambient(), stored.particles(), stored.icon()));
        }
    }

    private static void applyPhysical(Snapshot.Physical physical, Player player) {
        player.setFireTicks(physical.fireTicks());
        player.setRemainingAir(physical.remainingAir());
        player.setVelocity(physical.velocity());
        if (physical.walkSpeed() > 0f) {
            // Zero would leave the player unable to move, and a snapshot that
            // says zero is one taken while another plugin had them frozen.
            player.setWalkSpeed(physical.walkSpeed());
        }
        player.setInvulnerable(physical.invulnerable());
        player.setGlowing(physical.glowing());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Empties what a snapshot would put back.
     *
     * <p>Only the inventory, armour and off hand: {@code saveAndClear} exists so
     * a plugin can hand out its own kit, and a player whose experience, health
     * and game mode were also wiped would be handed a kit while dying in
     * spectator.
     *
     * @param player the player to clear
     */
    public static void clear(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(null);
        inventory.setItemInOffHand(null);
        player.updateInventory();
    }
}
