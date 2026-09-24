package net.exylia.lib.util.mob.internal;

import net.exylia.lib.display.VfxRun;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.mob.MobSkill;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the skill types that play out over time do: a dash, a chain, a shield,
 * a zone, a barrage, and a leap that lands hard.
 *
 * <p>Gameplay only. Each one runs on its own timer — the mob's for what moves
 * with it, the spot's for what stays where it landed — whether or not anybody
 * is watching, and asks {@link MobShows} to draw each moment, which it may
 * decline. Everything that outlasts the cast is registered on the mob with
 * {@link LiveMob#linger}, so its death, its break, its expiry and its unload
 * all stop it; each timer also checks the mob is still there on every run.
 */
final class MobMoves {

    /** A dash's reach when its skill says none, and the most it may say. */
    static final double DASH_REACH = 12;
    static final double MAX_DASH = 24;

    /** How far a dash gets between two pushes, give or take: about 22 blocks a second. */
    static final double DASH_STRIDE = 2.2;

    /** The speed a push sets, in blocks a tick. */
    static final double DASH_SPEED = 1.4;

    /** How close to a dashing mob a player is run through. */
    static final double DASH_HIT = 1.3;

    /** A chain's jumps when its text says none, and the most it may say. */
    static final int JUMPS = 4;
    static final int MAX_JUMPS = 8;

    /** A chain's jump range when its radius says none. */
    static final double CHAIN_RANGE = 6;

    /** Ticks between two jumps of a chain. */
    static final long JUMP_TICKS = 2L;

    /** How many zones one mob keeps up at once. */
    static final int MAX_ZONES = 2;

    /** The longest a zone or a shield lasts. */
    static final long MAX_LINGER = 30_000L;

    /** A zone's radius when its skill says none. */
    static final double ZONE_RADIUS = 3;

    /** Ticks between two zone pulses: damage and potion every half second. */
    static final long ZONE_TICKS = 10L;

    /** A barrage's strikes when its amount says none, and the most it may say. */
    static final int STRIKES = 5;
    static final int MAX_STRIKES = 16;

    /** How far a barrage scatters when its radius says none. */
    static final double SCATTER = 6;

    /** Who stands on a strike: within this many blocks of its spot. */
    static final double STRIKE = 1.5;

    /** A strike's damage when its text says none. */
    static final double STRIKE_DAMAGE = 4;

    /** Ticks from a strike's circle appearing to its landing, and between two strikes. */
    static final long STRIKE_LEAD_TICKS = 20L;
    static final long STRIKE_GAP_TICKS = 4L;

    /** How long a leap waits to land before it gives up, in two-tick polls. */
    private static final int LANDING_POLLS = 30;

    private final MobEngine engine;
    private final TaskScheduler tasks;
    private final MobShows shows;

    MobMoves(MobEngine engine, TaskScheduler tasks, MobShows shows) {
        this.engine = engine;
        this.tasks = tasks;
        this.shows = shows;
    }

    // ------------------------------------------------------------------ dash

    static double dashReach(MobSkill skill) {
        return Math.min(MAX_DASH, skill.radius() > 0 ? skill.radius() : DASH_REACH);
    }

    /**
     * Charges from where the mob is, a push every two ticks, until it has gone
     * its reach, hit a wall or run out of pushes. On the mob's thread.
     *
     * @param towards where to run; {@code null} runs the way it faced as it wound up
     * @return how many ticks it runs, which its recovery waits out
     */
    long dash(LivingEntity entity, LiveMob mob, MobSkill skill, Stage stage, @Nullable Location towards) {
        Location from = entity.getLocation();
        Vector direction = towards == null ? MobAim.facing(stage.yaw()) : MobEngine.flat(from, towards);
        if (direction.lengthSquared() < 1.0E-6) direction = MobAim.facing(from.getYaw());
        direction.normalize();
        double reach = dashReach(skill);
        int strides = (int) Math.clamp(Math.ceil(reach / DASH_STRIDE), 1, 12);
        entity.setRotation(MobAim.yaw(new Vector(), direction, from.getYaw()), from.getPitch());
        Set<UUID> hit = ConcurrentHashMap.newKeySet();
        AtomicInteger stride = new AtomicInteger();
        AtomicBoolean done = new AtomicBoolean();
        Location[] last = {from.clone()};
        Vector heading = direction;
        java.util.function.Consumer<TaskHandle> run = handle -> {
            if (done.get() || !mob.alive() || !engine.tracks(entity, mob)) {
                done.set(true);
                if (handle != null) handle.cancel();
                return;
            }
            int index = stride.getAndIncrement();
            Location now = entity.getLocation();
            runThrough(entity, mob, skill, stage, heading, hit);
            double travelled = Math.hypot(now.getX() - from.getX(), now.getZ() - from.getZ());
            boolean stalled = index >= 2 && Math.hypot(now.getX() - last[0].getX(), now.getZ() - last[0].getZ()) < 0.3;
            last[0] = now;
            if (index >= strides || travelled >= reach - 0.4 || stalled) {
                done.set(true);
                if (handle != null) handle.cancel();
                entity.setVelocity(entity.getVelocity().setX(0).setZ(0));
                shows.daze(stage);
                return;
            }
            double speed = Math.min(DASH_SPEED, DASH_SPEED * (reach - travelled) / DASH_STRIDE);
            entity.setVelocity(heading.clone().multiply(speed).setY(Math.min(0, entity.getVelocity().getY())));
            shows.step(stage, now, index);
        };
        // The first push in this very tick, the rest on a timer: the wind-up already made everyone wait.
        run.accept(null);
        if (!done.get()) tasks.runAtEntityTimer(entity, 2L, 2L, run);
        return strides * 2L + 2L;
    }

    /** Whoever the dash is running through takes its damage and is thrown aside, once each. */
    private void runThrough(LivingEntity entity, LiveMob mob, MobSkill skill, Stage stage, Vector heading,
                            Set<UUID> hit) {
        double reach = DASH_HIT + entity.getWidth() / 2;
        Location at = entity.getLocation();
        for (Player player : MobEngine.playersNear(entity, reach + 1)) {
            Location there = player.getLocation();
            if (Math.hypot(there.getX() - at.getX(), there.getZ() - at.getZ()) > reach) continue;
            if (!hit.add(player.getUniqueId())) continue;
            hurt(entity, mob, player, skill.amount());
            Vector offset = there.toVector().subtract(at.toVector());
            double side = heading.getX() * offset.getZ() - heading.getZ() * offset.getX() >= 0 ? 1 : -1;
            Vector aside = new Vector(-heading.getZ() * side, 0, heading.getX() * side);
            player.setVelocity(aside.multiply(0.8).add(heading.clone().multiply(0.4)).setY(0.4));
            shows.dashHit(stage, player);
        }
    }

    // ----------------------------------------------------------------- chain

    static int jumps(MobSkill skill) {
        try {
            String text = skill.text().trim();
            int jumps = text.isEmpty() ? JUMPS : (int) Math.round(Double.parseDouble(text));
            return Math.clamp(jumps, 1, MAX_JUMPS);
        } catch (NumberFormatException notANumber) {
            return JUMPS;
        }
    }

    static double chainRange(MobSkill skill) {
        return skill.radius() > 0 ? Math.min(16, skill.radius()) : CHAIN_RANGE;
    }

    /**
     * Hits the first body, then jumps on from each one it hits to the nearest
     * player it has not, on the thread of the spot it jumps from.
     */
    boolean chain(LivingEntity entity, LiveMob mob, MobSkill skill, Stage stage, LivingEntity first) {
        Set<UUID> hit = ConcurrentHashMap.newKeySet();
        hit.add(first.getUniqueId());
        shows.jump(stage, MobStyle.hands(stage), MobStyle.chest(first), 0);
        hurt(entity, mob, first, skill.amount());
        int jumps = jumps(skill);
        if (jumps > 1) jumpOn(entity, mob, skill, stage, first.getLocation(), MobStyle.chest(first), hit, 1, jumps);
        return true;
    }

    private void jumpOn(LivingEntity entity, LiveMob mob, MobSkill skill, Stage stage, Location at, Location from,
                        Set<UUID> hit, int jump, int jumps) {
        tasks.runAtLocationLater(at, JUMP_TICKS, () -> {
            if (!mob.alive()) return;
            Player next = nearest(at, chainRange(skill), hit);
            if (next == null) return;
            hit.add(next.getUniqueId());
            Location chest = MobStyle.chest(next);
            shows.jump(stage, from, chest, jump);
            hurt(entity, mob, next, skill.amount());
            if (jump + 1 < jumps) jumpOn(entity, mob, skill, stage, next.getLocation(), chest, hit, jump + 1, jumps);
        });
    }

    private static @Nullable Player nearest(Location at, double range, Set<UUID> skip) {
        World world = at.getWorld();
        if (world == null) return null;
        Player best = null;
        double closest = range * range;
        for (Entity nearby : world.getNearbyEntities(at, range, range, range)) {
            if (!(nearby instanceof Player player) || !MobEngine.huntable(player)
                    || skip.contains(player.getUniqueId())) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(at);
            if (distance <= closest) {
                closest = distance;
                best = player;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- shield

    static long lingering(MobSkill skill, long fallback) {
        long millis = skill.duration().isZero() ? fallback : skill.duration().toMillis();
        return Math.clamp(millis, 250L, MAX_LINGER);
    }

    /** Raises the mob's shield and draws it for as long as it lasts. */
    void shield(LivingEntity entity, LiveMob mob, MobSkill skill, Stage stage) {
        long millis = lingering(skill, 5_000L);
        double share = skill.amount() > 0 ? skill.amount() / 100 : 0.6;
        mob.shield(share, System.currentTimeMillis() + millis);
        VfxRun look = shows.ward(stage, entity, millis);
        if (look == null) return;
        Runnable stop = look::cancel;
        mob.linger(stop);
        tasks.runAtEntityLater(entity, millis / 50 + 20, () -> mob.unlinger(stop));
    }

    /**
     * The damage a hit does through a shield, and whether it goes through at
     * all.
     *
     * @return the damage left; {@code 0} when the shield stops all of it
     */
    static double throughShield(LiveMob mob, double damage, long now) {
        double share = mob.shielded(now);
        return share <= 0 ? damage : damage * (1 - share);
    }

    /** Whether a hit in hits mode counts: not while a shield is up. */
    static boolean countsHit(LiveMob mob, long now) {
        return mob.shielded(now) <= 0;
    }

    // ------------------------------------------------------------------ zone

    static double zoneRadius(MobSkill skill) {
        return skill.radius() > 0 ? Math.min(16, skill.radius()) : ZONE_RADIUS;
    }

    /** One zone that is up: its timer and its look, stopped together, once. */
    static final class Zone {
        private final LiveMob mob;
        private final AtomicBoolean stopped = new AtomicBoolean();
        volatile @Nullable TaskHandle timer;
        volatile @Nullable VfxRun look;
        final Runnable stopper = this::stop;

        Zone(LiveMob mob) {
            this.mob = mob;
        }

        void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            TaskHandle handle = timer;
            if (handle != null) handle.cancel();
            VfxRun run = look;
            if (run != null) run.cancel();
            mob.closeZone();
            mob.unlinger(stopper);
        }

        boolean stopped() {
            return stopped.get();
        }

        /**
         * Whether a zone keeps going: its time is not up, its mob is still there
         * and the ground under it is still loaded.
         */
        static boolean keeps(long now, long endsAt, boolean mobThere, boolean loaded) {
            return now < endsAt && mobThere && loaded;
        }
    }

    /**
     * Opens a zone: on the ground at {@code centre}, or riding the mob.
     *
     * @return the zone, or {@code null} when the mob already has {@link #MAX_ZONES} up
     */
    @Nullable Zone zone(LivingEntity entity, LiveMob mob, MobSkill skill, Stage stage, Location centre,
                        boolean riding) {
        if (!mob.openZone(MAX_ZONES)) return null;
        long millis = lingering(skill, 5_000L);
        long endsAt = System.currentTimeMillis() + millis;
        double radius = zoneRadius(skill);
        double perPulse = skill.amount() * ZONE_TICKS / 20.0;
        PotionEffect potion = skill.text().isBlank() ? null : engine.potionOf(mob, skill);
        Zone zone = new Zone(mob);
        mob.linger(zone.stopper);
        zone.look = shows.zone(stage, centre, riding ? entity : null, radius, millis);
        AtomicInteger pulses = new AtomicInteger();
        java.util.function.Consumer<TaskHandle> pulse = handle -> {
            // A mob that unloaded stops its timer, which is what alive() reads; a riding zone also needs it tracked.
            boolean there = mob.alive() && (!riding || engine.tracks(entity, mob));
            Location at = riding ? (there ? entity.getLocation() : centre) : centre;
            boolean loaded = at.getWorld() != null && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4);
            if (!Zone.keeps(System.currentTimeMillis(), endsAt, there, loaded)) {
                zone.stop();
                return;
            }
            int count = pulses.incrementAndGet();
            for (Player player : playersAround(at, radius)) {
                if (perPulse > 0) hurt(entity, mob, player, perPulse);
                if (potion != null) player.addPotionEffect(potion);
            }
            if (riding) shows.zoneTick(stage, at, count);
        };
        zone.timer = riding ? tasks.runAtEntityTimer(entity, ZONE_TICKS, ZONE_TICKS, pulse)
                : tasks.runAtLocationTimer(centre, ZONE_TICKS, ZONE_TICKS, pulse);
        return zone;
    }

    // --------------------------------------------------------------- barrage

    static int strikes(MobSkill skill) {
        return skill.amount() > 0 ? (int) Math.clamp(Math.round(skill.amount()), 1, MAX_STRIKES) : STRIKES;
    }

    static double scatter(MobSkill skill) {
        return skill.radius() > 0 ? Math.min(16, skill.radius()) : SCATTER;
    }

    static double strikeDamage(MobSkill skill) {
        try {
            String text = skill.text().trim();
            return text.isEmpty() ? STRIKE_DAMAGE : Math.max(0, Double.parseDouble(text));
        } catch (NumberFormatException notANumber) {
            return STRIKE_DAMAGE;
        }
    }

    /** A spot within {@code scatter} of the centre, spread evenly over the disc rather than bunched in the middle. */
    static Location scattered(Location centre, double scatter, ThreadLocalRandom random) {
        double angle = random.nextDouble(Math.PI * 2);
        double out = Math.sqrt(random.nextDouble()) * scatter;
        return centre.clone().add(Math.cos(angle) * out, 0, Math.sin(angle) * out);
    }

    /**
     * Marks the strikes and lands them: the first on the centre itself, the
     * rest scattered, each a second after its circle appears and a fifth of a
     * second after the one before.
     */
    void barrage(LivingEntity entity, LiveMob mob, MobSkill skill, Stage stage, Location centre) {
        int strikes = strikes(skill);
        double scatter = scatter(skill);
        double damage = strikeDamage(skill);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int strike = 0; strike < strikes; strike++) {
            Location spot = grounded(strike == 0 ? centre.clone() : scattered(centre, scatter, random));
            long delay = strike * STRIKE_GAP_TICKS;
            VfxRun look = shows.strike(stage, spot, delay * 50L);
            TaskHandle[] landing = new TaskHandle[1];
            Runnable stop = () -> {
                if (landing[0] != null) landing[0].cancel();
                if (look != null) look.cancel();
            };
            mob.linger(stop);
            landing[0] = tasks.runAtLocationLater(spot, delay + STRIKE_LEAD_TICKS, () -> {
                mob.unlinger(stop);
                if (!mob.alive()) return;
                for (Player player : playersAround(spot, STRIKE)) hurt(entity, mob, player, damage);
            });
        }
    }

    /** A spot moved onto the ground under it, when this thread may look; else as it was. */
    private Location grounded(Location spot) {
        if (!tasks.isOwnedBy(spot)) return spot;
        try {
            Location probe = spot.clone();
            for (int dy = 3; dy >= -5; dy--) {
                probe.setY(Math.floor(spot.getY()) + dy);
                if (probe.getBlock().getType().isSolid()) {
                    Location top = spot.clone();
                    top.setY(probe.getBlockY() + 1.0);
                    return top;
                }
            }
        } catch (RuntimeException | LinkageError unreadable) {
            return spot;
        }
        return spot;
    }

    // ------------------------------------------------------------------ leap

    /**
     * Waits for a leaping mob to land, then hits everyone within its radius
     * with its own attack damage and knocks them back.
     */
    void pounce(LivingEntity entity, LiveMob mob, MobSkill skill, Stage stage) {
        double radius = Math.min(8, skill.radius());
        if (radius <= 0) return;
        AtomicInteger polls = new AtomicInteger();
        tasks.runAtEntityTimer(entity, 4L, 2L, handle -> {
            if (polls.incrementAndGet() > LANDING_POLLS || !mob.alive() || !engine.tracks(entity, mob)) {
                handle.cancel();
                return;
            }
            if (!entity.isOnGround()) return;
            handle.cancel();
            Location at = entity.getLocation();
            double damage = MobEngine.attackDamage(entity);
            for (Player player : MobEngine.playersNear(entity, radius)) {
                hurt(entity, mob, player, damage);
                player.setVelocity(MobEngine.flat(at, player.getLocation()).multiply(0.6).setY(0.35));
            }
            shows.landed(stage, at, radius);
        });
    }

    // --------------------------------------------------------------- helpers

    /**
     * Hurts a body on the mob's behalf. On the mob's own thread the hit is
     * marked as the mob casting, so an attack skill it sets off cannot cast
     * back into this one.
     */
    private void hurt(LivingEntity entity, LiveMob mob, LivingEntity body, double amount) {
        if (!(amount > 0) || !body.isValid()) return;
        boolean own = tasks.isOwnedBy(entity);
        boolean was = own && mob.casting();
        if (own) mob.casting(true);
        try {
            body.damage(amount, entity);
        } finally {
            if (own) mob.casting(was);
        }
    }

    /** Survival and adventure players within a radius of a spot, from that spot's thread. */
    static @NotNull List<Player> playersAround(Location point, double radius) {
        List<Player> players = new ArrayList<>();
        World world = point.getWorld();
        if (world == null || radius <= 0) return players;
        double squared = radius * radius;
        for (Entity nearby : world.getNearbyEntities(point, radius, 2.5, radius)) {
            if (nearby instanceof Player player && MobEngine.huntable(player)) {
                Location at = player.getLocation();
                double dx = at.getX() - point.getX();
                double dz = at.getZ() - point.getZ();
                if (dx * dx + dz * dz <= squared) players.add(player);
            }
        }
        return players;
    }
}
