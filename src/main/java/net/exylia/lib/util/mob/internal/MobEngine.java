package net.exylia.lib.util.mob.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.platform.Platform;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.Effects;
import net.exylia.lib.util.Effects.ParsedEffect;
import net.exylia.lib.util.editor.Loadout;
import net.exylia.lib.util.mob.MobDeath;
import net.exylia.lib.util.mob.MobFlag;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobTemplate;
import net.exylia.lib.util.sequence.PluginSequences;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.sequence.Sequences;
import com.destroystokyo.paper.entity.Pathfinder;
import net.exylia.lib.util.mob.MobBehaviour;
import net.exylia.lib.util.mob.MobHit;
import net.exylia.lib.util.mob.MobLook;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Llama;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityEnterLoveModeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.HorseInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Team;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * One plugin's custom mobs: the templates it registered, the mobs alive right
 * now, and the listeners that make them behave.
 *
 * <p>The listeners are registered under the owning plugin, so Bukkit drops
 * them with it; the timers are that plugin's too, so its scheduler cancels
 * them. What is left for {@link #stop} is the mobs themselves.
 */
public final class MobEngine implements Listener {

    /** The key every custom mob carries: {@code <plugin>:<template id>}. */
    public static final NamespacedKey TAG = new NamespacedKey("exylialib", "mob");

    /** How often each mob's timer runs: lifetime, leash, look cycles, hunting and interval skills, in ticks. */
    private static final long PERIOD = 20L;

    /** How often the timer of a mob with an aura, a turning name or a wander runs, in ticks. */
    private static final long FAST = 2L;

    /** Fast passes to a second: the one-second work runs on every tenth. */
    private static final int PER_SECOND = (int) (PERIOD / FAST);

    /** Frames of an aura's turn: 18 steps of 20 degrees, a full turn in under two seconds. */
    static final int FRAMES = 18;

    /** How far a wandering mob with no roam runs off each time. */
    private static final double WANDER_RANGE = 10;

    /** Pathfinder speed of a wandering mob, on top of its movement speed: a run, not a stroll. */
    private static final double RUN = 1.8;

    /** The team prefix outline colours go through, on the main scoreboard. */
    private static final String TEAM = "exylia_mob_";

    /** How often mobs that are gone are dropped from the registry, in ticks. */
    private static final long PRUNE = 200L;

    /** How far a mob looks for somebody to aim at when nobody is in the event. */
    private static final double NEAREST = 16;

    /** The farthest an aggressive mob hunts, whatever its follow range says. */
    private static final double HUNT_LIMIT = 48;

    /** The most minions one summon skill keeps alive. */
    private static final int MAX_MINIONS = 10;

    /** The farthest a minion appears from its summoner. */
    private static final double MAX_SPREAD = 8;

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final String prefix;
    private final Map<String, MobTemplate> templates = new ConcurrentHashMap<>();

    /**
     * The mobs alive in this runtime, by entity id.
     *
     * <p>A registry of live things rather than a cache, which is why it has no
     * cap: evicting an entry would leave a mob in the world whose skills stop
     * and whose death is never reported. It is bounded by the world instead —
     * a mob that dies leaves on its death, and one removed any other way
     * (a chunk unloading, another plugin) is dropped by the prune within
     * ten seconds, because its entity timer stopped.
     */
    private final Map<UUID, LiveMob> live = new ConcurrentHashMap<>();
    private final List<Consumer<MobDeath>> deaths = new CopyOnWriteArrayList<>();
    private final List<Consumer<MobHit>> hits = new CopyOnWriteArrayList<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /** Named auras, in the order they were handed in: the first is what an unknown name wears. */
    private volatile Map<String, List<String>> auras = Map.of();
    private volatile double effectRadius = SequenceTarget.DEFAULT_RADIUS;

    /** Effect and aura-frame sequences, compiled the first time they play; an aura is eighteen. */
    private final Cache<String, Sequence> sequences = Caffeine.newBuilder().maximumSize(1024).build();

    private volatile boolean stopped;

    public MobEngine(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
        this.prefix = plugin.getName() + ":";
    }

    /** Registers the listeners and the prune, and clears what a previous run left. */
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        tasks.runAsyncTimer(PRUNE, PRUNE, () -> live.values().removeIf(mob -> {
            if (mob.alive()) return false;
            // Gone some way the runtime never saw: a chunk unloading. Its team
            // entry would outlive it, and teams live on the main scoreboard.
            String glow = mob.glowShown();
            LivingEntity entity = mob.entity();
            if (glow != null && entity != null) {
                UUID id = entity.getUniqueId();
                tasks.run(() -> team(id, glow, false));
            }
            return true;
        }));
        // Folia has no thread that may walk every world; there, a leftover is
        // found as its chunk loads, which is the only way one reaches a player.
        if (!Platform.isFolia()) {
            tasks.run(() -> {
                for (World world : Bukkit.getWorlds()) {
                    for (LivingEntity entity : world.getLivingEntities()) removeIfOrphan(entity);
                }
            });
        }
    }

    /**
     * Takes every live mob out of the world and forgets everything.
     *
     * <p>Mobs are not saved with their chunk, so on a server stop this only
     * tidies; on a plugin disabled while the server runs it is what keeps its
     * mobs from standing around with nobody left to run them.
     */
    public void stop() {
        stopped = true;
        HandlerList.unregisterAll(this);
        deaths.clear();
        hits.clear();
        List<LiveMob> mobs = new ArrayList<>(live.values());
        live.clear();
        templates.clear();
        for (LiveMob mob : mobs) {
            mob.end();
            LivingEntity entity = mob.entity();
            if (entity != null) remove(entity, mob.glowShown());
        }
    }

    // ---------------------------------------------------------------- registry

    public void register(@NotNull MobTemplate template) {
        templates.put(template.id(), template);
    }

    public boolean unregister(@NotNull String id) {
        return templates.remove(id) != null;
    }

    public @Nullable MobTemplate template(@NotNull String id) {
        return templates.get(id);
    }

    public @NotNull Collection<MobTemplate> templates() {
        return List.copyOf(templates.values());
    }

    public void onDeath(@NotNull Consumer<MobDeath> handler) {
        deaths.add(handler);
    }

    public boolean offDeath(@NotNull Consumer<MobDeath> handler) {
        return deaths.remove(handler);
    }

    public void onHit(@NotNull Consumer<MobHit> handler) {
        hits.add(handler);
    }

    public boolean offHit(@NotNull Consumer<MobHit> handler) {
        return hits.remove(handler);
    }

    public void auras(@NotNull Map<String, List<String>> named) {
        Map<String, List<String>> copy = new java.util.LinkedHashMap<>();
        named.forEach((name, lines) -> copy.put(name, List.copyOf(lines)));
        auras = java.util.Collections.unmodifiableMap(copy);
    }

    public @NotNull Map<String, List<String>> auras() {
        return auras;
    }

    public void effectRadius(double blocks) {
        effectRadius = Double.isFinite(blocks) ? Math.max(0, blocks) : SequenceTarget.DEFAULT_RADIUS;
    }

    public double effectRadius() {
        return effectRadius;
    }

    // ------------------------------------------------------------------ lookup

    public boolean isMob(@NotNull Entity entity) {
        String tag = tag(entity);
        return tag != null && tag.startsWith(prefix);
    }

    public @Nullable MobTemplate templateOf(@NotNull Entity entity) {
        LiveMob mob = live.get(entity.getUniqueId());
        if (mob != null) return mob.template();
        String tag = tag(entity);
        return tag != null && tag.startsWith(prefix) ? templates.get(tag.substring(prefix.length())) : null;
    }

    public @NotNull List<LivingEntity> live() {
        List<LivingEntity> entities = new ArrayList<>();
        for (LiveMob mob : live.values()) {
            if (mob.alive() && mob.entity() != null) entities.add(mob.entity());
        }
        return entities;
    }

    public int count(@NotNull String templateId) {
        int count = 0;
        for (LiveMob mob : live.values()) {
            if (mob.alive() && mob.template().id().equals(templateId)) count++;
        }
        return count;
    }

    // ------------------------------------------------------------------- spawn

    /**
     * Spawns a mob here and now; the caller owns the location's thread.
     *
     * @param template what to spawn
     * @param location where
     * @param summoned whether a summon skill is asking, which keeps the minion from summoning
     * @return the mob, already in the world
     */
    public @NotNull LivingEntity spawnHere(@NotNull MobTemplate template, @NotNull Location location,
                                           boolean summoned) {
        if (stopped) throw new IllegalStateException(plugin.getName() + "'s mobs have been released");
        Class<? extends Entity> type = template.type().getEntityClass();
        if (type == null || !LivingEntity.class.isAssignableFrom(type) || !template.type().isSpawnable()) {
            throw new IllegalArgumentException(template.type() + " is not a living entity that can be spawned");
        }
        World world = location.getWorld();
        if (world == null) throw new IllegalArgumentException("the location has no world");

        LivingEntity entity = spawn(world, location, type.asSubclass(LivingEntity.class), template);
        if (!entity.isValid()) {
            // Another plugin cancelled the spawn event: a region that forbids
            // mobs, a spawn limiter. Reported to the caller, who asked for a mob.
            throw new IllegalStateException("the spawn of " + template.id() + " was cancelled");
        }
        LiveMob mob = new LiveMob(template, entity, summoned, System.currentTimeMillis());
        mob.home(entity.getLocation());
        AttributeInstance movement = instance(entity, "movement_speed");
        // The base, not the value: a potion's modifier would otherwise be baked in by a SPEED skill.
        mob.baseSpeed(movement == null ? Double.NaN : movement.getBaseValue());
        look(entity, mob, true);
        // Only a mob that draws something every other tick pays for the fast
        // timer; the rest keep the one-second one they always had.
        boolean fast = fast(template);
        long period = fast ? FAST : PERIOD;
        // The timer before the registry: the prune reads a mob with no timer as gone.
        mob.timer(tasks.runAtEntityTimer(entity, period, period, () -> tick(entity, mob, fast)));
        live.put(entity.getUniqueId(), mob);
        fire(MobSkill.Trigger.SPAWN, entity, mob, null);
        return entity;
    }

    private <T extends LivingEntity> T spawn(World world, Location location, Class<T> type, MobTemplate template) {
        // Without the vanilla randomising: no rolled armour, no baby zombie on
        // a chicken. What the template says is all the mob is.
        return world.spawn(location, type, false, entity -> prepare(entity, template));
    }

    /** Everything a template sets, applied before the mob is in the world. */
    private void prepare(LivingEntity entity, MobTemplate template) {
        entity.setPersistent(false);
        entity.setRemoveWhenFarAway(false);
        entity.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, prefix + template.id());

        for (Map.Entry<String, Double> entry : template.attributes().entrySet()) {
            Attribute attribute = attribute(entry.getKey());
            AttributeInstance instance = attribute == null ? null : entity.getAttribute(attribute);
            if (instance == null) {
                report("attribute:" + template.id() + ":" + entry.getKey(), "Mob " + template.id()
                        + ": " + template.type() + " has no attribute " + entry.getKey() + "; skipped.");
                continue;
            }
            instance.setBaseValue(entry.getValue());
        }
        entity.setHealth(maxHealth(entity));

        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            equipment.clear();
            equipment.setHelmet(item(template, 0));
            equipment.setChestplate(item(template, 1));
            equipment.setLeggings(item(template, 2));
            equipment.setBoots(item(template, 3));
            equipment.setItemInOffHand(item(template, Loadout.OFFHAND));
            equipment.setItemInMainHand(item(template, MobTemplate.MAIN_HAND));
            // Only a Mob has drop chances; setting one on anything else throws.
            if (entity instanceof Mob) {
                equipment.setHelmetDropChance(0);
                equipment.setChestplateDropChance(0);
                equipment.setLeggingsDropChance(0);
                equipment.setBootsDropChance(0);
                equipment.setItemInOffHandDropChance(0);
                equipment.setItemInMainHandDropChance(0);
            }
        }

        entity.setGlowing(template.has(MobFlag.GLOWING) || !template.look().glow().isEmpty());
        entity.setSilent(template.has(MobFlag.SILENT));
        if (template.has(MobFlag.BABY) && entity instanceof Ageable ageable) ageable.setBaby();
        if (template.has(MobFlag.NO_ITEM_PICKUP)) entity.setCanPickupItems(false);

        for (ParsedEffect effect : template.effects()) {
            PotionEffect potion = potion(effect);
            if (potion == null) {
                report("effect:" + template.id() + ":" + effect.name(),
                        "Mob " + template.id() + ": no potion effect " + effect.name() + "; skipped.");
                continue;
            }
            entity.addPotionEffect(potion);
        }
        render(entity, template, template.behaviour().hits(), 0);
    }

    private static @Nullable ItemStack item(MobTemplate template, int index) {
        ItemStack item = template.equipment(index);
        return item == null ? null : item.clone();
    }

    // -------------------------------------------------------------------- tick

    static boolean fast(MobTemplate template) {
        return !template.look().aura().isEmpty() || template.name().contains("<rainbow>")
                || template.has(MobFlag.WANDERS);
    }

    private void tick(LivingEntity entity, LiveMob mob, boolean fast) {
        if (live.get(entity.getUniqueId()) != mob) {
            mob.end();
            return;
        }
        if (fast) {
            int phase = mob.step();
            String[] aura = mob.aura();
            if (aura != null) play(aura[phase % FRAMES], entity.getLocation(), entity);
            if (phase % 2 == 0 && mob.template().name().contains("<rainbow>")) name(entity, mob);
            if (mob.template().has(MobFlag.WANDERS)) wander(entity, mob);
            if (phase % PER_SECOND != 0) return;
        }
        second(entity, mob);
    }

    /** The once-a-second work: lifetime, leash, look cycles, hunting and interval skills. */
    private void second(LivingEntity entity, LiveMob mob) {
        if (mob.expired(System.currentTimeMillis())) {
            finish(entity, mob, MobDeath.Cause.EXPIRED, null);
            return;
        }
        leash(entity, mob);
        mob.second();
        look(entity, mob, false);
        if (mob.template().has(MobFlag.AGGRESSIVE) && !mob.template().has(MobFlag.PASSIVE)
                && entity instanceof Mob hunter && !usable(hunter, hunter.getTarget())) {
            double range = Math.min(HUNT_LIMIT, value(hunter, "follow_range", NEAREST));
            Player prey = nearest(hunter, range);
            if (prey != null) hunter.setTarget(prey);
        }
        fire(MobSkill.Trigger.INTERVAL, entity, mob, null);
    }

    // ----------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFire(EntityDamageEvent event) {
        LiveMob mob = live.get(event.getEntity().getUniqueId());
        if (mob == null || !mob.template().has(MobFlag.FIRE_IMMUNE)) return;
        switch (event.getCause()) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> event.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        LiveMob mob = live.get(event.getEntity().getUniqueId());
        if (mob == null) return;
        // The plain event, not its by-block or by-entity kinds, is daylight.
        boolean sun = event.getClass() == EntityCombustEvent.class;
        if (mob.template().has(MobFlag.FIRE_IMMUNE) || (sun && mob.template().has(MobFlag.NO_SUN_BURN))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity damager = event instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;
        LivingEntity attacker = damager == null ? null : source(damager);

        if (attacker != null && event.getEntity() instanceof LivingEntity hurt) {
            LiveMob hitter = live.get(attacker.getUniqueId());
            if (hitter != null) fire(MobSkill.Trigger.ATTACK, attacker, hitter, hurt);
        }

        LiveMob mob = live.get(event.getEntity().getUniqueId());
        // A mob in hits mode is counted by onHitsMode; what reaches here is the void or /kill.
        if (mob == null || mob.usesHits() || !(event.getEntity() instanceof LivingEntity entity)) return;
        double health = entity.getHealth();
        double dealt = event.getFinalDamage();
        // Capped at what it had left, so an overkill does not buy a bigger share.
        mob.hurt(damager == null ? null : playerOf(damager), Math.min(dealt, health));
        double after = health - dealt;
        if (after <= 0) return;

        fire(MobSkill.Trigger.DAMAGED, entity, mob, attacker, false);
        lowHealth(entity, mob, after / maxHealth(entity));
        nameLater(entity, mob);
    }

    /**
     * Hits mode: nothing hurts the mob, a player's melee hit counts one.
     *
     * <p>At HIGHEST and reading cancelled events on purpose: a piñata stands in
     * a protected spawn, and a region that forbids hurting mobs must not make
     * it unbreakable. The void and {@code /kill} still go through, so a mob
     * that fell out of the world can die.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHitsMode(EntityDamageEvent event) {
        LiveMob mob = live.get(event.getEntity().getUniqueId());
        if (mob == null || !mob.usesHits() || !(event.getEntity() instanceof LivingEntity entity)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return;
        event.setCancelled(true);
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event instanceof EntityDamageByEntityEvent byEntity)
                || !(byEntity.getDamager() instanceof Player player)) {
            return;
        }
        int left = mob.hit(player.getUniqueId(), System.currentTimeMillis());
        if (left >= 0) hit(entity, mob, player, left);
    }

    /** One counted hit: animation, skills, handlers, and the break when it was the last. */
    private void hit(LivingEntity entity, LiveMob mob, Player player, int left) {
        entity.playHurtAnimation(player.getLocation().getYaw());
        boolean broken = left == 0;
        // The breaking hit still looks and sounds like a hit; nothing else is
        // cast on a mob that is about to go.
        fire(MobSkill.Trigger.DAMAGED, entity, mob, player, broken);
        if (!broken) lowHealth(entity, mob, left / (double) mob.maxHits());
        MobHit hit = new MobHit(mob.template(), entity, player, left, mob.maxHits());
        for (Consumer<MobHit> handler : hits) {
            try {
                handler.accept(hit);
            } catch (RuntimeException | LinkageError failure) {
                debug.error("A mob hit handler failed for " + mob.template().id() + ".", failure);
            }
        }
        if (broken) {
            finish(entity, mob, MobDeath.Cause.BROKEN, player);
        } else {
            name(entity, mob);
        }
    }

    /**
     * Ends a mob that did not die: broken by its last hit or out of time.
     *
     * <p>On the mob's thread. The handlers see it still standing; it is removed
     * after them.
     */
    private void finish(LivingEntity entity, LiveMob mob, MobDeath.Cause cause, @Nullable Player killer) {
        if (!live.remove(entity.getUniqueId(), mob)) return;
        mob.end();
        MobTemplate template = mob.template();
        Location at = entity.getLocation();
        if (cause == MobDeath.Cause.BROKEN) {
            fire(MobSkill.Trigger.DEATH, entity, mob, killer, false);
            int exp = template.exp();
            if (exp > 0) at.getWorld().spawn(at, ExperienceOrb.class, orb -> orb.setExperience(exp));
        }
        tell(new MobDeath(template, entity, at, killer, mob.damage(), mob.topDamager(), mob.playerShare(), cause));
        unglow(entity.getUniqueId(), mob.glowShown());
        entity.remove();
    }

    private void tell(MobDeath death) {
        for (Consumer<MobDeath> handler : deaths) {
            try {
                handler.accept(death);
            } catch (RuntimeException | LinkageError failure) {
                debug.error("A mob death handler failed for " + death.template().id() + ".", failure);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent event) {
        LiveMob mob = live.get(event.getEntity().getUniqueId());
        if (mob != null && event.getEntity() instanceof LivingEntity entity) nameLater(entity, mob);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        LiveMob mob = live.remove(entity.getUniqueId());
        if (mob == null) return;
        mob.end();
        MobTemplate template = mob.template();
        if (template.has(MobFlag.NO_VANILLA_DROPS)) event.getDrops().clear();
        // What the look put on its back is equipment, and equipment never drops.
        Material worn = mob.bodyShown().isEmpty() ? null : Material.matchMaterial(mob.bodyShown());
        if (worn != null) event.getDrops().removeIf(drop -> drop != null && drop.getType() == worn);
        unglow(entity.getUniqueId(), mob.glowShown());
        if (template.has(MobFlag.NO_VANILLA_EXP)) event.setDroppedExp(0);
        event.setDroppedExp(event.getDroppedExp() + template.exp());

        Player killer = entity.getKiller();
        fire(MobSkill.Trigger.DEATH, entity, mob, killer, false);
        tell(new MobDeath(template, entity, entity.getLocation(), killer,
                mob.damage(), mob.topDamager(), mob.playerShare(), MobDeath.Cause.KILLED));
    }

    /** A slime splitting or a zombie drowning would be a vanilla mob wearing our tag. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (live.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    /** A fireball a mob threw hurts people, never the terrain. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter
                && live.containsKey(shooter.getUniqueId())) {
            event.blockList().clear();
        }
    }

    /**
     * No lib mob changes dimension: the other side would re-create it as an
     * entity the runtime is not tracking.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        if (live.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (has(event.getEntity(), MobFlag.PASSIVE)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (has(event.getRightClicked(), MobFlag.NO_INTERACT)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (has(event.getEntity(), MobFlag.NO_INTERACT)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (has(event.getMount(), MobFlag.NO_INTERACT) || has(event.getEntity(), MobFlag.NO_INTERACT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof Entity holder && has(holder, MobFlag.NO_INTERACT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLove(EntityEnterLoveModeEvent event) {
        if (has(event.getEntity(), MobFlag.NO_INTERACT)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (has(event.getMother(), MobFlag.NO_INTERACT) || has(event.getFather(), MobFlag.NO_INTERACT)) {
            event.setCancelled(true);
        }
    }

    private boolean has(@Nullable Entity entity, MobFlag flag) {
        if (entity == null) return false;
        LiveMob mob = live.get(entity.getUniqueId());
        return mob != null && mob.template().has(flag);
    }

    /** Never saved with its chunk, so one that loads is left over from a crash or a reload. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) removeIfOrphan(entity);
    }

    private void removeIfOrphan(Entity entity) {
        if (isMob(entity) && !live.containsKey(entity.getUniqueId())) entity.remove();
    }

    // ----------------------------------------------------------------- skills

    private void fire(MobSkill.Trigger trigger, LivingEntity entity, LiveMob mob, @Nullable LivingEntity about) {
        fire(trigger, entity, mob, about, false);
    }

    /** @param effectsOnly cast only the EFFECT skills: the hit that breaks a mob in hits mode */
    private void fire(MobSkill.Trigger trigger, LivingEntity entity, LiveMob mob, @Nullable LivingEntity about,
                      boolean effectsOnly) {
        List<MobSkill> skills = mob.template().skills();
        if (skills.isEmpty()) return;
        long now = System.currentTimeMillis();
        LivingEntity target = null;
        boolean looked = false;
        for (int index = 0; index < skills.size(); index++) {
            MobSkill skill = skills.get(index);
            if (skill.trigger() != trigger) continue;
            if (effectsOnly && skill.type() != MobSkill.Type.EFFECT) continue;
            if (skill.type() == MobSkill.Type.SUMMON && mob.summoned()) continue;
            // Looked up once per trigger and only when a skill is about to use
            // it: the nearest-player fallback is a query on the world.
            if (!looked) {
                target = target(entity, about);
                looked = true;
            }
            // Checked before the dice so a skill with nobody to aim at keeps
            // its cooldown for when somebody arrives.
            if (target == null && skill.needsTarget()) continue;
            if (!mob.attempt(index, skill, now, ThreadLocalRandom.current().nextDouble())) continue;
            cast(entity, mob, index, skill, target);
        }
    }

    private void lowHealth(LivingEntity entity, LiveMob mob, double fraction) {
        List<MobSkill> skills = mob.template().skills();
        for (int index = 0; index < skills.size(); index++) {
            MobSkill skill = skills.get(index);
            if (skill.trigger() != MobSkill.Trigger.LOW_HEALTH) continue;
            if (skill.type() == MobSkill.Type.SUMMON && mob.summoned()) continue;
            if (!mob.lowHealth(index, skill, fraction, ThreadLocalRandom.current().nextDouble())) continue;
            LivingEntity target = target(entity, null);
            if (target == null && skill.needsTarget()) continue;
            cast(entity, mob, index, skill, target);
        }
    }

    /**
     * Casts one skill, guarded against itself.
     *
     * <p>A skill that hurts somebody raises a damage event, which is an attack
     * trigger for this very mob: without the guard, an attack skill that deals
     * area damage on a zero cooldown recurses until the stack gives out.
     */
    private void cast(LivingEntity entity, LiveMob mob, int index, MobSkill skill, @Nullable LivingEntity target) {
        if (mob.casting()) return;
        mob.casting(true);
        try {
            Location from = entity.getLocation();
            if (apply(entity, mob, skill, target)) play(skill.effect(), from, entity);
        } catch (RuntimeException | LinkageError failure) {
            if (reported.add("cast:" + mob.template().id() + ":" + index)) {
                debug.error("Mob " + mob.template().id() + ": skill " + (index + 1) + " ("
                        + skill.type() + ") failed; it keeps being tried.", failure);
            }
        } finally {
            mob.casting(false);
        }
    }

    /**
     * What each type does; {@link #fire} has already checked the target where it needs one.
     *
     * @return whether it did something, which is when its effect lines play
     */
    private boolean apply(LivingEntity entity, LiveMob mob, MobSkill skill, @Nullable LivingEntity target) {
        Location at = entity.getLocation();
        double strength = skill.amount() > 0 ? skill.amount() : 1;
        switch (skill.type()) {
            case LEAP -> entity.setVelocity(flat(at, target.getLocation())
                    .multiply(0.9 * strength).setY(Math.min(1.2, 0.45 * strength)));
            case PULL -> target.setVelocity(flat(target.getLocation(), at).multiply(0.9 * strength).setY(0.35));
            case PUSH -> {
                for (Player player : playersNear(entity, skill.radius())) {
                    player.setVelocity(flat(at, player.getLocation()).multiply(strength).setY(0.45));
                }
            }
            case POTION -> potion(entity, mob, skill, target);
            case SUMMON -> summon(entity, mob, skill, target);
            case LIGHTNING -> {
                target.getWorld().strikeLightningEffect(target.getLocation());
                if (skill.amount() > 0) target.damage(skill.amount(), entity);
            }
            case PROJECTILE -> shoot(entity, mob, skill, target);
            case HEAL -> {
                double max = maxHealth(entity);
                entity.setHealth(Math.min(max, entity.getHealth() + max * skill.amount() / 100));
                nameLater(entity, mob);
            }
            case TELEPORT -> {
                Location landed = skill.radius() > 0 ? blink(entity, mob, skill.radius()) : behind(entity, target);
                if (landed == null) return false;
                play(skill.effect(), landed, entity);
            }
            case AREA_DAMAGE -> {
                for (Player player : playersNear(entity, skill.radius())) player.damage(skill.amount(), entity);
            }
            case IGNITE -> target.setFireTicks(Math.max(target.getFireTicks(),
                    (int) Math.min(Integer.MAX_VALUE, skill.duration().toMillis() / 50)));
            case EFFECT -> effect(entity, skill);
            case COMMAND -> command(mob, skill, target);
            case JUMP -> entity.setVelocity(entity.getVelocity().setY(skill.amount()));
            case SIZE -> {
                return size(entity, mob, skill);
            }
            case SPEED -> {
                return speed(entity, mob, skill);
            }
            case BABY -> {
                return baby(entity, skill);
            }
        }
        return true;
    }

    /** A random scale in the skill's range. */
    private boolean size(LivingEntity entity, LiveMob mob, MobSkill skill) {
        double[] range = sizeRange(skill.text());
        AttributeInstance scale = instance(entity, "scale");
        if (range == null || scale == null) {
            report("size:" + mob.template().id() + ":" + skill.text(), "Mob " + mob.template().id()
                    + ": a size skill needs a range such as 0.7|1.8 and a type with a scale; skipped.");
            return false;
        }
        scale.setBaseValue(range[0] == range[1] ? range[0]
                : ThreadLocalRandom.current().nextDouble(range[0], range[1]));
        return true;
    }

    /**
     * A size skill's range: {@code min|max} or one number, either order, never
     * below {@code 0.1}.
     *
     * @return {@code {min, max}}, or {@code null} when the text is no range
     */
    static double @Nullable [] sizeRange(String text) {
        String[] parts = text.trim().split("\\|");
        try {
            double first = Double.parseDouble(parts[0].trim());
            double second = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : first;
            if (!Double.isFinite(first) || !Double.isFinite(second)) return null;
            double min = Math.max(0.1, Math.min(first, second));
            return new double[]{min, Math.max(min, Math.max(first, second))};
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Faster for a while, then back to the speed it spawned with; a later boost wins. */
    private boolean speed(LivingEntity entity, LiveMob mob, MobSkill skill) {
        AttributeInstance movement = instance(entity, "movement_speed");
        double base = mob.baseSpeed();
        if (movement == null || Double.isNaN(base)) return false;
        int token = mob.boostSpeed();
        movement.setBaseValue(base * skill.amount());
        tasks.runAtEntityLater(entity, ticks(skill.duration()), () -> {
            if (mob.endBoost(token)) movement.setBaseValue(base);
        });
        return true;
    }

    /** A baby for a while, then an adult again. */
    private boolean baby(LivingEntity entity, MobSkill skill) {
        if (!(entity instanceof Ageable ageable) || !ageable.isAdult()) return false;
        ageable.setBaby();
        // A baby that ages would grow up on its own clock, not the skill's.
        if (entity instanceof Breedable breedable) breedable.setAgeLock(true);
        tasks.runAtEntityLater(entity, ticks(skill.duration()), ageable::setAdult);
        return true;
    }

    private static long ticks(java.time.Duration duration) {
        return Math.max(1, duration.toMillis() / 50);
    }

    /**
     * Blinks to a random spot on the ground within a radius, never further
     * from home than it may roam, and only where this thread may look.
     *
     * @return where it landed, or {@code null} when no spot would do
     */
    private @Nullable Location blink(LivingEntity entity, LiveMob mob, double radius) {
        Location from = entity.getLocation();
        World world = from.getWorld();
        Location home = mob.home();
        double roam = mob.template().behaviour().roam();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble(Math.PI * 2);
            double distance = radius <= 1 ? 1 : random.nextDouble(1, radius);
            int x = (int) Math.floor(from.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(from.getZ() + Math.sin(angle) * distance);
            Location target = new Location(world, x + 0.5, from.getY(), z + 0.5, from.getYaw(), from.getPitch());
            if (roam > 0 && home != null && world.equals(home.getWorld())
                    && target.distanceSquared(home) > roam * roam) continue;
            if (!world.isChunkLoaded(x >> 4, z >> 4) || !tasks.isOwnedBy(target)) continue;
            target.setY(world.getHighestBlockYAt(x, z) + 1.0);
            move(entity, target);
            return target;
        }
        return null;
    }

    /** From one place towards another, level with the ground; zero when they coincide. */
    private static Vector flat(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector()).setY(0);
        return direction.lengthSquared() < 1.0E-6 ? direction : direction.normalize();
    }

    /** A step and a half behind the target, facing it, where the mob fits; where it landed, or {@code null}. */
    private static @Nullable Location behind(LivingEntity entity, LivingEntity target) {
        Location destination = target.getLocation();
        Vector facing = destination.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0E-6) facing = new Vector(0, 0, 1);
        destination.subtract(facing.normalize().multiply(1.5));
        destination.setDirection(target.getLocation().toVector().subtract(destination.toVector()));
        if (!destination.getBlock().isPassable() || !destination.clone().add(0, 1, 0).getBlock().isPassable()) {
            return null;
        }
        move(entity, destination);
        return destination;
    }

    private static void move(LivingEntity entity, Location destination) {
        if (Platform.isFolia()) {
            entity.teleportAsync(destination);
        } else {
            entity.teleport(destination);
        }
    }

    /** Runs a command skill from the console, on the global thread it needs. */
    private void command(LiveMob mob, MobSkill skill, @Nullable LivingEntity target) {
        String command = skill.text().trim();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty()) return;
        if (command.contains("%player%")) {
            if (!(target instanceof Player player)) return;
            command = command.replace("%player%", player.getName());
        }
        String line = command.replace("%mob%", mob.template().id());
        tasks.run(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
    }

    /** The mob's target, else whoever the event is about, else the nearest player. */
    @Nullable LivingEntity target(LivingEntity entity, @Nullable LivingEntity about) {
        if (entity instanceof Mob mob && usable(entity, mob.getTarget())) return mob.getTarget();
        if (usable(entity, about)) return about;
        return nearest(entity, NEAREST);
    }

    private static boolean usable(LivingEntity from, @Nullable LivingEntity target) {
        if (target == null || target == from || !target.isValid() || target.isDead()) return false;
        if (target instanceof Player player && !huntable(player)) return false;
        return target.getWorld().equals(from.getWorld());
    }

    private static boolean huntable(Player player) {
        return !player.isDead() && (player.getGameMode() == GameMode.SURVIVAL
                || player.getGameMode() == GameMode.ADVENTURE);
    }

    /** Players within a sphere, from the mob's own region. */
    static @NotNull List<Player> playersNear(LivingEntity entity, double radius) {
        List<Player> players = new ArrayList<>();
        if (radius <= 0) return players;
        Location at = entity.getLocation();
        double squared = radius * radius;
        for (Entity nearby : entity.getNearbyEntities(radius, radius, radius)) {
            if (nearby instanceof Player player && huntable(player)
                    && player.getLocation().distanceSquared(at) <= squared) {
                players.add(player);
            }
        }
        return players;
    }

    private static @Nullable Player nearest(LivingEntity entity, double radius) {
        Location at = entity.getLocation();
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : playersNear(entity, radius)) {
            double distance = player.getLocation().distanceSquared(at);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    // ------------------------------------------------------ the larger skills

    /** Spawns a summon skill's minions, keeping no more than it allows alive. */
    void summon(LivingEntity entity, LiveMob mob, MobSkill skill, @Nullable LivingEntity target) {
        MobTemplate minion = templates.get(skill.text().trim());
        if (minion == null) {
            report("summon:" + mob.template().id() + ":" + skill.text(), "Mob " + mob.template().id()
                    + ": summons " + skill.text() + ", which is not a registered template.");
            return;
        }
        int cap = (int) Math.min(MAX_MINIONS, Math.max(1, Math.round(skill.amount())));
        int missing = cap - mob.minions(id -> {
            LiveMob alive = live.get(id);
            return alive != null && alive.alive();
        });
        double spread = Math.min(MAX_SPREAD, skill.radius() > 0 ? skill.radius() : 3);
        Location at = entity.getLocation();
        for (int count = 0; count < missing; count++) {
            Location spot = at.clone().add(offset(spread), 0, offset(spread));
            // Somewhere a mob fits and this thread may touch; otherwise at the summoner's feet.
            if (!tasks.isOwnedBy(spot) || !spot.getBlock().isPassable()
                    || !spot.clone().add(0, 1, 0).getBlock().isPassable()) {
                spot = at;
            }
            LivingEntity child = spawnHere(minion, spot, true);
            mob.minion(child.getUniqueId());
            if (target != null && child instanceof Mob hunter) hunter.setTarget(target);
        }
    }

    private static double offset(double spread) {
        return ThreadLocalRandom.current().nextDouble(-spread, spread);
    }

    /** Plays an effect skill's sequence lines at the mob. */
    void effect(LivingEntity entity, MobSkill skill) {
        play(skill.text(), entity.getLocation(), entity);
    }

    /** Plays sequence lines, one per line, seen within the effect radius. */
    private void play(String text, Location at, @Nullable Entity on) {
        String lines = text.trim();
        if (lines.isEmpty()) return;
        PluginSequences plugins = Sequences.of(plugin);
        Sequence sequence = sequences.get(lines, written -> plugins.compile(
                Arrays.asList(written.split("\\R")), "a mob effect"));
        plugins.play(sequence, SequenceTarget.at(at).on(on).within(effectRadius));
    }

    /** The projectile class a skill names, or {@code null} when it names none. */
    static @Nullable Class<? extends Projectile> projectile(String written) {
        return switch (written.trim().toUpperCase(Locale.ROOT)) {
            case "", "FIREBALL" -> LargeFireball.class;
            case "SMALL_FIREBALL" -> SmallFireball.class;
            case "WITHER_SKULL" -> WitherSkull.class;
            case "ARROW" -> Arrow.class;
            case "SNOWBALL" -> Snowball.class;
            default -> null;
        };
    }

    /** Throws a skill's projectile, if it names one. */
    void shoot(LivingEntity entity, LiveMob mob, MobSkill skill, LivingEntity target) {
        Class<? extends Projectile> type = projectile(skill.text());
        if (type == null) {
            report("projectile:" + mob.template().id() + ":" + skill.text(), "Mob " + mob.template().id()
                    + ": no projectile " + skill.text() + "; use FIREBALL, SMALL_FIREBALL, WITHER_SKULL, ARROW or SNOWBALL.");
            return;
        }
        Vector direction = target.getEyeLocation().toVector().subtract(entity.getEyeLocation().toVector());
        if (direction.lengthSquared() < 1.0E-6) return;
        direction.normalize().multiply(skill.amount() > 0 ? skill.amount() : 1.5);
        Projectile projectile = entity.launchProjectile(type, direction);
        if (projectile instanceof Fireball fireball) {
            fireball.setIsIncendiary(false);
            fireball.setDirection(direction);
        }
    }

    /** Puts a potion skill's effect on whoever it reaches. */
    void potion(LivingEntity entity, LiveMob mob, MobSkill skill, @Nullable LivingEntity target) {
        ParsedEffect parsed = Effects.parse(skill.text());
        PotionEffect effect = parsed == null ? null : potion(parsed);
        if (effect == null) {
            report("potion:" + mob.template().id() + ":" + skill.text(), "Mob " + mob.template().id()
                    + ": " + skill.text() + " is not a potion effect line such as SLOWNESS|2|5.");
            return;
        }
        if (skill.radius() > 0) {
            for (Player player : playersNear(entity, skill.radius())) player.addPotionEffect(effect);
        } else if (target != null) {
            target.addPotionEffect(effect);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Keeps the %health% in a name current; the value is only known a tick later. */
    private void nameLater(LivingEntity entity, LiveMob mob) {
        String name = mob.template().name();
        if (name.contains("%health%") || name.contains("%max_health%")) {
            tasks.runAtEntity(entity, () -> name(entity, mob));
        }
    }

    void name(LivingEntity entity, LiveMob mob) {
        render(entity, mob.template(), mob.hitsLeft(), mob.phase());
    }

    /**
     * Draws the name: health, or hits in hits mode, and a turning rainbow.
     *
     * @param hitsLeft the hits it has left, read only in hits mode
     * @param phase    the fast timer's phase: a {@code <rainbow>} moves one step every other pass
     */
    void render(LivingEntity entity, MobTemplate template, int hitsLeft, int phase) {
        String name = template.name();
        if (name.isBlank()) return;
        if (name.contains("<rainbow>")) name = name.replace("<rainbow>", "<rainbow:" + (phase / 2 % 10) + ">");
        MobBehaviour behaviour = template.behaviour();
        entity.customName(Text.of(name)
                .with("%health%", behaviour.usesHits() ? String.valueOf(Math.max(0, hitsLeft)) : whole(entity.getHealth()))
                .with("%max_health%", behaviour.usesHits() ? String.valueOf(behaviour.hits()) : whole(maxHealth(entity)))
                .build());
        entity.setCustomNameVisible(true);
    }

    // -------------------------------------------------------- look and motion

    /**
     * Puts on what its look says: every part as it spawns, then each second
     * only the parts that CYCLE.
     */
    private void look(LivingEntity entity, LiveMob mob, boolean spawning) {
        MobLook look = mob.template().look();
        if (look.equals(MobLook.NONE)) return;
        EntityType type = mob.template().type();
        String variant = pick(look.variant(), MobLook.variants(type), mob, spawning);
        if (variant != null && !variant.equalsIgnoreCase(mob.variantShown())) {
            variant(entity, mob, variant);
            mob.variantShown(variant);
        }
        String body = pick(look.body(), MobLook.bodies(type), mob, spawning);
        if (body != null && !body.equalsIgnoreCase(mob.bodyShown())) {
            body(entity, mob, body);
            mob.bodyShown(body);
        }
        String glow = pick(look.glow(), MobLook.GLOWS, mob, spawning);
        if (glow != null) glow(entity, mob, glow);
        if (spawning || look.aura().equals(MobLook.CYCLE)) mob.aura(aura(look.aura(), mob));
    }

    /** What a part shows now, or {@code null} when it stays as it is. */
    private static @Nullable String pick(String written, List<String> options, LiveMob mob, boolean spawning) {
        if (written.isEmpty()) return null;
        if (written.equals(MobLook.CYCLE)) {
            return options.isEmpty() ? null : options.get(mob.secondsLived() % options.size());
        }
        if (!spawning) return null;
        if (written.equals(MobLook.RANDOM)) {
            return options.isEmpty() ? null : options.get(ThreadLocalRandom.current().nextInt(options.size()));
        }
        return written;
    }

    /** The frames of the aura a look names: RANDOM and unknown names as the piñata always had them. */
    private @Nullable String[] aura(String written, LiveMob mob) {
        Map<String, List<String>> named = auras;
        if (written.isEmpty() || named.isEmpty()) return null;
        List<List<String>> all = List.copyOf(named.values());
        List<String> lines;
        if (written.equals(MobLook.CYCLE)) {
            lines = all.get(mob.secondsLived() % all.size());
        } else if (written.equals(MobLook.RANDOM)) {
            lines = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        } else {
            lines = named.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(written))
                    .map(Map.Entry::getValue).findFirst().orElse(all.get(0));
        }
        return lines.isEmpty() ? null : frames(lines);
    }

    /**
     * An aura's frames: {@code %angle%} turned 20 degrees a frame, and
     * {@code %angle2%} and {@code %angle3%} a third and two thirds of a turn ahead.
     *
     * @param lines one frame's sequence lines
     * @return {@link #FRAMES} texts, one per frame
     */
    static String[] frames(List<String> lines) {
        String[] frames = new String[FRAMES];
        String text = String.join("\n", lines);
        for (int frame = 0; frame < FRAMES; frame++) {
            int angle = frame * (360 / FRAMES);
            frames[frame] = text.replace("%angle2%", String.valueOf((angle + 120) % 360))
                    .replace("%angle3%", String.valueOf((angle + 240) % 360))
                    .replace("%angle%", String.valueOf(angle));
        }
        return frames;
    }

    private void variant(LivingEntity entity, LiveMob mob, String written) {
        String name = written.toUpperCase(Locale.ROOT);
        try {
            switch (entity) {
                case Llama llama -> llama.setColor(Llama.Color.valueOf(name));
                case Horse horse -> horse.setColor(Horse.Color.valueOf(name));
                case Sheep sheep -> sheep.setColor(DyeColor.valueOf(name));
                case Parrot parrot -> parrot.setVariant(Parrot.Variant.valueOf(name));
                case Axolotl axolotl -> axolotl.setVariant(Axolotl.Variant.valueOf(name));
                case Rabbit rabbit -> rabbit.setRabbitType(Rabbit.Type.valueOf(name));
                case Fox fox -> fox.setFoxType(Fox.Type.valueOf(name));
                case MushroomCow cow -> cow.setVariant(MushroomCow.Variant.valueOf(name));
                default -> report("variant:" + mob.template().id(), "Mob " + mob.template().id() + ": "
                        + mob.template().type() + " has no variant the library sets; skipped.");
            }
        } catch (IllegalArgumentException unknown) {
            report("variant:" + mob.template().id() + ":" + name, "Mob " + mob.template().id() + ": "
                    + mob.template().type() + " has no variant " + written + "; skipped.");
        }
    }

    private void body(LivingEntity entity, LiveMob mob, String written) {
        Material material = Material.matchMaterial(written);
        if (material == null || !material.isItem()) {
            report("body:" + mob.template().id() + ":" + written, "Mob " + mob.template().id()
                    + ": no item " + written + " to wear; skipped.");
            return;
        }
        ItemStack item = new ItemStack(material);
        try {
            if (entity instanceof Llama llama) {
                llama.getInventory().setDecor(item);
            } else if (entity instanceof AbstractHorse horse && horse.getInventory() instanceof HorseInventory saddle) {
                saddle.setArmor(item);
            } else {
                EntityEquipment equipment = entity.getEquipment();
                if (equipment == null) throw new IllegalArgumentException("no equipment");
                equipment.setItem(EquipmentSlot.BODY, item);
                if (entity instanceof Mob) equipment.setDropChance(EquipmentSlot.BODY, 0);
            }
        } catch (RuntimeException unsupported) {
            report("body:" + mob.template().id(), "Mob " + mob.template().id() + ": "
                    + mob.template().type() + " cannot wear " + written + "; skipped.");
        }
    }

    /** Moves the outline to another colour: out of the last colour's team, into the new one's. */
    private void glow(LivingEntity entity, LiveMob mob, String colour) {
        String shown = mob.glowShown();
        if (colour.equalsIgnoreCase(shown)) return;
        if (NamedTextColor.NAMES.value(colour.toLowerCase(Locale.ROOT)) == null) {
            report("glow:" + mob.template().id() + ":" + colour, "Mob " + mob.template().id()
                    + ": no colour " + colour + " for its outline; it stays white.");
            return;
        }
        UUID id = entity.getUniqueId();
        if (shown != null) team(id, shown, false);
        mob.glowShown(team(id, colour, true) ? colour : null);
    }

    /**
     * Puts an entity in or out of an outline colour's team on the main scoreboard.
     *
     * <p>A server with no main scoreboard to write to — Folia — keeps the
     * white outline.
     *
     * @return whether the team was written
     */
    private static boolean team(UUID id, String colour, boolean join) {
        NamedTextColor named = NamedTextColor.NAMES.value(colour.toLowerCase(Locale.ROOT));
        if (named == null) return false;
        try {
            var board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = board.getTeam(TEAM + named);
            if (team == null) {
                if (!join) return false;
                team = board.registerNewTeam(TEAM + named);
                team.color(named);
            }
            if (join) {
                team.addEntry(id.toString());
            } else {
                team.removeEntry(id.toString());
            }
            return true;
        } catch (RuntimeException | LinkageError unsupported) {
            return false;
        }
    }

    private static void unglow(UUID id, @Nullable String colour) {
        if (colour != null) team(id, colour, false);
    }

    /** Runs somewhere new whenever it has no target and no path. */
    private void wander(LivingEntity entity, LiveMob mob) {
        if (!(entity instanceof Mob walker) || usable(walker, walker.getTarget())) return;
        try {
            Pathfinder pathfinder = walker.getPathfinder();
            if (pathfinder.hasPath()) return;
            Location from = entity.getLocation();
            Location home = mob.home();
            double roam = mob.template().behaviour().roam();
            Location centre = roam > 0 && home != null && from.getWorld().equals(home.getWorld()) ? home : from;
            double range = roam > 0 ? roam : WANDER_RANGE;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            // A few tries: a spot in a wall or over water has no path.
            for (int attempt = 0; attempt < 4; attempt++) {
                double angle = random.nextDouble(Math.PI * 2);
                double distance = random.nextDouble(range * 0.3, range + 1.0E-3);
                Location spot = centre.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                spot.setY(from.getY());
                if (pathfinder.moveTo(spot, RUN)) return;
            }
        } catch (LinkageError spigot) {
            report("pathfinder", "Mob " + mob.template().id() + ": wandering needs Paper's pathfinder; skipped.");
        }
    }

    /** Past its roam it walks back; eight blocks further, or in another world, it is put back. */
    private void leash(LivingEntity entity, LiveMob mob) {
        double roam = mob.template().behaviour().roam();
        Location home = mob.home();
        if (roam <= 0 || home == null) return;
        Location at = entity.getLocation();
        boolean sameWorld = Objects.equals(at.getWorld(), home.getWorld());
        switch (LiveMob.leash(sameWorld, sameWorld ? at.distanceSquared(home) : 0, roam)) {
            case TELEPORT_BACK -> move(entity, home);
            case WALK_BACK -> {
                if (!(entity instanceof Mob walker)) return;
                try {
                    walker.getPathfinder().moveTo(home, mob.template().has(MobFlag.WANDERS) ? RUN : 1);
                } catch (LinkageError spigot) {
                    move(entity, home);
                }
            }
            case STAY -> { }
        }
    }

    /** Health as a player reads it: never 0 while the mob stands. */
    private static String whole(double health) {
        return String.valueOf((long) Math.ceil(health));
    }

    private static @Nullable AttributeInstance instance(LivingEntity entity, String key) {
        Attribute attribute = attribute(key);
        return attribute == null ? null : entity.getAttribute(attribute);
    }

    static double maxHealth(LivingEntity entity) {
        return value(entity, "max_health", entity.getHealth());
    }

    private static double value(LivingEntity entity, String key, double fallback) {
        Attribute attribute = attribute(key);
        AttributeInstance instance = attribute == null ? null : entity.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    /** By registry key, never by field: the constants were renamed in 1.21.2. */
    private static @Nullable Attribute attribute(String key) {
        NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        return namespaced == null ? null : Registry.ATTRIBUTE.get(namespaced);
    }

    /** The same resolver {@link Effects} applies through, so a legacy name works here too. */
    @SuppressWarnings("deprecation")
    private static @Nullable PotionEffect potion(ParsedEffect effect) {
        PotionEffectType type = PotionEffectType.getByName(effect.name());
        return type == null ? null : new PotionEffect(type, effect.duration(), effect.amplifier(),
                effect.ambient(), effect.particles(), effect.icon());
    }

    /** The living thing behind a hit: the archer behind an arrow. */
    private static @Nullable LivingEntity source(Entity damager) {
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof LivingEntity living ? living : null;
        }
        return damager instanceof LivingEntity living ? living : null;
    }

    /** The player behind a hit: through an arrow, a tamed wolf or lit TNT. */
    static @Nullable UUID playerOf(Entity damager) {
        if (damager instanceof Player player) return player.getUniqueId();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player.getUniqueId();
        }
        if (damager instanceof Tameable tameable && tameable.isTamed()) {
            AnimalTamer owner = tameable.getOwner();
            return owner instanceof org.bukkit.OfflinePlayer ? owner.getUniqueId() : null;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player.getUniqueId();
        }
        return null;
    }

    private static @Nullable String tag(Entity entity) {
        return entity.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
    }

    void report(String key, String message) {
        if (reported.add(key)) debug.warn(message);
    }

    /** Takes a mob out on its own thread, from whatever thread is releasing it, out of its outline team too. */
    private static void remove(LivingEntity entity, @Nullable String glow) {
        Runnable gone = () -> {
            unglow(entity.getUniqueId(), glow);
            entity.remove();
        };
        if (!Platform.isFolia() && Bukkit.isPrimaryThread()) {
            gone.run();
            return;
        }
        // ExyliaLib's scheduler: the owning plugin's is being cancelled.
        Plugin library = Bukkit.getPluginManager().getPlugin("ExyliaLib");
        if (library != null && library.isEnabled()) Tasks.of(library).runAtEntity(entity, gone);
    }
}
