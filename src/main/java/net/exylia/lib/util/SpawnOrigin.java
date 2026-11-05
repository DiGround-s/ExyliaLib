package net.exylia.lib.util;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Where a mob came from: a spawner, an egg, breeding, or the world itself.
 *
 * <pre>{@code
 * @EventHandler
 * public void onDeath(EntityDeathEvent event) {
 *     if (SpawnOrigin.of(event.getEntity()).playerMade()) return; // no reward for farms
 *     reward(event.getEntity().getKiller());
 * }
 * }</pre>
 *
 * <p>Read from the spawn reason Paper keeps on every entity and saves with it,
 * so it survives restarts and chunk unloads. What Paper does not carry over is
 * added here: a mob made from another one (a slime split, a villager struck
 * into a witch, a zombie drowned, a mooshroom sheared) inherits the origin of
 * the one it came from, so the children of a spawner slime are still
 * {@link #SPAWNER}. That inheritance needs the library running when the
 * conversion happens; one made before falls back to its own reason.
 *
 * <h2>Threads</h2>
 * Ask from the thread that owns the entity, where its events run.
 *
 * @since 1.478.0
 */
public enum SpawnOrigin {

    /** Spawned by the world: natural spawns, chunk generation, raids, patrols, conversions of those. */
    NATURAL,
    /** A monster spawner, trial spawner or ominous item spawner. */
    SPAWNER,
    /** A spawn egg (used or dispensed), a thrown egg, or a mob released from a bucket. */
    EGG,
    /** Bred by players, or an allay duplicated. */
    BREEDING,
    /** A snow golem, iron golem or wither built from blocks. */
    BUILT,
    /** A plugin or a command. */
    PLUGIN,
    /** Anything else: an endermite from a pearl, a parrot off a shoulder, an effect or enchantment. */
    OTHER;

    /** Where an inherited origin is kept on the entity. */
    public static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString("exylialib:spawn_origin"));

    /**
     * Whether players made this mob on purpose: a spawner, an egg or breeding.
     * What a farm produces, and what anti-farming rules usually pay less for.
     */
    public boolean playerMade() {
        return this == SPAWNER || this == EGG || this == BREEDING;
    }

    /**
     * Where an entity came from.
     *
     * @param entity the entity
     * @return its origin; {@link #OTHER} on a server that keeps no spawn reason
     */
    public static @NotNull SpawnOrigin of(@NotNull Entity entity) {
        String inherited = entity.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        SpawnOrigin stored = parse(inherited);
        if (stored != null) return stored;
        try {
            return ofReason(entity.getEntitySpawnReason().name());
        } catch (NoSuchMethodError notPaper) {
            return OTHER;
        }
    }

    /**
     * The origin a spawn reason stands for, by its name so a reason newer than
     * the API the library compiles against is still read.
     *
     * @param reason a {@code CreatureSpawnEvent.SpawnReason} name, such as {@code SPAWNER_EGG}
     * @return its origin; {@link #OTHER} for a name not listed
     */
    public static @NotNull SpawnOrigin ofReason(@NotNull String reason) {
        return switch (reason) {
            case "NATURAL", "CHUNK_GEN", "JOCKEY", "MOUNT", "VILLAGE_DEFENSE", "VILLAGE_INVASION", "PATROL",
                 "RAID", "REINFORCEMENTS", "BEEHIVE", "SILVERFISH_BLOCK", "TRAP", "NETHER_PORTAL", "LIGHTNING",
                 "SLIME_SPLIT", "DROWNED", "INFECTION", "CURED", "PIGLIN_ZOMBIFIED", "FROZEN", "METAMORPHOSIS",
                 "SHEARED", "OCELOT_BABY", "SPELL", "EXPLOSION" -> NATURAL;
            case "SPAWNER", "TRIAL_SPAWNER", "OMINOUS_ITEM_SPAWNER" -> SPAWNER;
            case "SPAWNER_EGG", "DISPENSE_EGG", "EGG", "BUCKET" -> EGG;
            case "BREEDING", "DUPLICATION" -> BREEDING;
            case "BUILD_SNOWMAN", "BUILD_IRONGOLEM", "BUILD_WITHER" -> BUILT;
            case "CUSTOM", "COMMAND", "DEFAULT" -> PLUGIN;
            default -> OTHER;
        };
    }

    private static @Nullable SpawnOrigin parse(@Nullable String name) {
        if (name == null) return null;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
