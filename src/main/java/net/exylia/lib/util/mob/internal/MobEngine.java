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
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
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
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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

    /** How often each mob's timer runs: interval skills and hunting, in ticks. */
    private static final long PERIOD = 20L;

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
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /** Effect-skill sequences, compiled the first time they play. */
    private final Cache<String, Sequence> sequences = Caffeine.newBuilder().maximumSize(256).build();

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
        tasks.runAsyncTimer(PRUNE, PRUNE, () -> live.values().removeIf(mob -> !mob.alive()));
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
        List<LiveMob> mobs = new ArrayList<>(live.values());
        live.clear();
        templates.clear();
        for (LiveMob mob : mobs) {
            mob.end();
            LivingEntity entity = mob.entity();
            if (entity != null) remove(entity);
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
        // The timer before the registry: the prune reads a mob with no timer as gone.
        mob.timer(tasks.runAtEntityTimer(entity, PERIOD, PERIOD, () -> tick(entity, mob)));
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

        entity.setGlowing(template.has(MobFlag.GLOWING));
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
        name(entity, template);
    }

    private static @Nullable ItemStack item(MobTemplate template, int index) {
        ItemStack item = template.equipment(index);
        return item == null ? null : item.clone();
    }

    // -------------------------------------------------------------------- tick

    private void tick(LivingEntity entity, LiveMob mob) {
        if (live.get(entity.getUniqueId()) != mob) {
            mob.end();
            return;
        }
        if (mob.template().has(MobFlag.AGGRESSIVE) && entity instanceof Mob hunter && !usable(hunter, hunter.getTarget())) {
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
        if (mob == null || !(event.getEntity() instanceof LivingEntity entity)) return;
        double health = entity.getHealth();
        double dealt = event.getFinalDamage();
        // Capped at what it had left, so an overkill does not buy a bigger share.
        mob.hurt(damager == null ? null : playerOf(damager), Math.min(dealt, health));
        double after = health - dealt;
        if (after <= 0) return;

        fire(MobSkill.Trigger.DAMAGED, entity, mob, attacker);
        lowHealth(entity, mob, after / maxHealth(entity));
        nameLater(entity, mob);
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
        if (template.has(MobFlag.NO_VANILLA_EXP)) event.setDroppedExp(0);
        event.setDroppedExp(event.getDroppedExp() + template.exp());

        Player killer = entity.getKiller();
        fire(MobSkill.Trigger.DEATH, entity, mob, killer);
        MobDeath death = new MobDeath(template, entity, entity.getLocation(), killer,
                mob.damage(), mob.topDamager(), mob.playerShare());
        for (Consumer<MobDeath> handler : deaths) {
            try {
                handler.accept(death);
            } catch (RuntimeException | LinkageError failure) {
                debug.error("A mob death handler failed for " + template.id() + ".", failure);
            }
        }
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
        List<MobSkill> skills = mob.template().skills();
        if (skills.isEmpty()) return;
        long now = System.currentTimeMillis();
        LivingEntity target = null;
        boolean looked = false;
        for (int index = 0; index < skills.size(); index++) {
            MobSkill skill = skills.get(index);
            if (skill.trigger() != trigger) continue;
            if (skill.type() == MobSkill.Type.SUMMON && mob.summoned()) continue;
            // Looked up once per trigger and only when a skill is about to use
            // it: the nearest-player fallback is a query on the world.
            if (!looked) {
                target = target(entity, about);
                looked = true;
            }
            // Checked before the dice so a skill with nobody to aim at keeps
            // its cooldown for when somebody arrives.
            if (target == null && needsTarget(skill)) continue;
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
            if (target == null && needsTarget(skill)) continue;
            cast(entity, mob, index, skill, target);
        }
    }

    private static boolean needsTarget(MobSkill skill) {
        return skill.type().needsTarget() && !(skill.type() == MobSkill.Type.POTION && skill.radius() > 0);
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
            apply(entity, mob, skill, target);
        } catch (RuntimeException | LinkageError failure) {
            if (reported.add("cast:" + mob.template().id() + ":" + index)) {
                debug.error("Mob " + mob.template().id() + ": skill " + (index + 1) + " ("
                        + skill.type() + ") failed; it keeps being tried.", failure);
            }
        } finally {
            mob.casting(false);
        }
    }

    /** What each type does; {@link #fire} has already checked the target where it needs one. */
    private void apply(LivingEntity entity, LiveMob mob, MobSkill skill, @Nullable LivingEntity target) {
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
            case TELEPORT -> behind(entity, target);
            case AREA_DAMAGE -> {
                for (Player player : playersNear(entity, skill.radius())) player.damage(skill.amount(), entity);
            }
            case IGNITE -> target.setFireTicks(Math.max(target.getFireTicks(),
                    (int) Math.min(Integer.MAX_VALUE, skill.duration().toMillis() / 50)));
            case EFFECT -> effect(entity, skill);
            case COMMAND -> command(mob, skill, target);
        }
    }

    /** From one place towards another, level with the ground; zero when they coincide. */
    private static Vector flat(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector()).setY(0);
        return direction.lengthSquared() < 1.0E-6 ? direction : direction.normalize();
    }

    /** A step and a half behind the target, facing it, where the mob fits. */
    private static void behind(LivingEntity entity, LivingEntity target) {
        Location destination = target.getLocation();
        Vector facing = destination.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0E-6) facing = new Vector(0, 0, 1);
        destination.subtract(facing.normalize().multiply(1.5));
        destination.setDirection(target.getLocation().toVector().subtract(destination.toVector()));
        if (!destination.getBlock().isPassable() || !destination.clone().add(0, 1, 0).getBlock().isPassable()) return;
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
        String text = skill.text().trim();
        if (text.isEmpty()) return;
        PluginSequences plugins = Sequences.of(plugin);
        Sequence sequence = sequences.get(text, lines -> plugins.compile(
                Arrays.asList(lines.split("\\R")), "a mob effect skill"));
        plugins.play(sequence, SequenceTarget.at(entity.getLocation()).on(entity));
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
            tasks.runAtEntity(entity, () -> name(entity, mob.template()));
        }
    }

    void name(LivingEntity entity, MobTemplate template) {
        if (template.name().isBlank()) return;
        entity.customName(Text.of(template.name())
                .with("%health%", whole(entity.getHealth()))
                .with("%max_health%", whole(maxHealth(entity)))
                .build());
        entity.setCustomNameVisible(true);
    }

    /** Health as a player reads it: never 0 while the mob stands. */
    private static String whole(double health) {
        return String.valueOf((long) Math.ceil(health));
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

    /** Takes a mob out on its own thread, from whatever thread is releasing it. */
    private static void remove(LivingEntity entity) {
        if (!Platform.isFolia() && Bukkit.isPrimaryThread()) {
            entity.remove();
            return;
        }
        // ExyliaLib's scheduler: the owning plugin's is being cancelled.
        Plugin library = Bukkit.getPluginManager().getPlugin("ExyliaLib");
        if (library != null && library.isEnabled()) Tasks.of(library).runAtEntity(entity, entity::remove);
    }
}
