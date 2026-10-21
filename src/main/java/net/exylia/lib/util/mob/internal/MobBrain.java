package net.exylia.lib.util.mob.internal;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import net.exylia.lib.model.ModelInstance;
import net.exylia.lib.model.internal.Voices;
import net.exylia.lib.util.mob.MobAttack;
import net.exylia.lib.util.mob.MobBody;
import net.exylia.lib.util.mob.MobFlag;
import net.exylia.lib.util.mob.MobModel;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The mind of a model mob with a {@link MobBody}: the one goal it has once its
 * vanilla goals are gone.
 *
 * <p>Each tick it reads the world into a {@link Brain.Sense}, and carries out
 * what {@link Brain} decides: it finds and drops targets, paths, keeps its
 * height, and strikes with its own attacks, landing each on its animation's
 * beat. Paper only: the only class in the mob module that names Paper's goal
 * API, loaded on Paper alone.
 */
@ApiStatus.Internal
final class MobBrain implements Goal<Mob> {

    private static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, new NamespacedKey("exylialib", "body"));
    /** How often it looks for a target, and paths again towards a moving one, in ticks. */
    private static final int LOOK_TICKS = 10;
    /** How far past its follow range a target may go before it gives up on them. */
    private static final double LOSE = 1.5;
    /** The chance a tick that a roaming mob with nowhere to go picks somewhere. */
    private static final double ROAM_CHANCE = 1 / 60.0;

    private final MobEngine engine;
    private final Mob body;
    private final LiveMob mob;
    private final MobBody spec;
    private final List<MobAttack> attacks;
    private final long[] readyAt;
    private final double halfWidth;
    private final double reach;

    private int ticks;
    private int swinging = -1;
    private long strikeAt;
    private @Nullable LivingEntity swingAt;

    private MobBrain(MobEngine engine, Mob body, LiveMob mob, double halfWidth) {
        this.engine = engine;
        this.body = body;
        this.mob = mob;
        MobModel model = mob.template().model();
        this.spec = model.body();
        this.attacks = model.strikes();
        this.readyAt = new long[attacks.size()];
        this.halfWidth = halfWidth;
        this.reach = Strikes.reach(attacks);
    }

    /**
     * Empties a carrier of its vanilla goals and gives it this one.
     *
     * @param halfWidth half the model's width as drawn, blocks: where its body ends
     */
    static void install(MobEngine engine, Mob body, LiveMob mob, double halfWidth) {
        MobGoals goals = Bukkit.getMobGoals();
        goals.removeAllGoals(body);
        body.setTarget(null);
        goals.addGoal(body, 0, new MobBrain(engine, body, mob, halfWidth));
    }

    @Override
    public boolean shouldActivate() {
        return engine.tracks(body, mob);
    }

    @Override
    public boolean shouldStayActive() {
        return engine.tracks(body, mob);
    }

    @Override
    public @NotNull GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public @NotNull EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.JUMP, GoalType.TARGET);
    }

    @Override
    public void stop() {
        swinging = -1;
        swingAt = null;
    }

    @Override
    public void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        if (spec.kind() == MobBody.Kind.SWIMMER) {
            // A dolphin surfaces for air by a goal of its own, which is gone.
            body.setRemainingAir(body.getMaximumAir());
        }
        LivingEntity target = target();
        if (swinging >= 0) {
            swing(target, now);
        }

        Location at = body.getLocation();
        Location home = mob.home();
        boolean sameWorld = home != null && home.getWorld() == at.getWorld();
        double gap = target == null ? Double.MAX_VALUE : gap(target);
        Brain.Intent intent = Brain.decide(new Brain.Sense(target != null, gap, reach, spec.keepDistance(),
                body.getHealth() / MobEngine.maxHealth(body), spec.fleeBelow(),
                sameWorld ? at.distance(home) : 0, mob.template().behaviour().roam(),
                mob.template().has(MobFlag.WANDERS) || mob.template().behaviour().roam() > 0,
                spec.pace() > 0));

        Pathfinder paths = body.getPathfinder();
        switch (intent) {
            case HUNT -> {
                if (ticks % LOOK_TICKS == 0 || !paths.hasPath()) paths.moveTo(aim(target.getLocation(), target), 1);
                body.lookAt(target);
            }
            case HOLD -> {
                if (paths.hasPath()) paths.stopPathfinding();
                body.lookAt(target);
                strike(target, gap, now);
            }
            case BACK_OFF -> {
                Vector away = flat(target.getLocation(), at).multiply(4);
                if (ticks % LOOK_TICKS == 0 || !paths.hasPath()) paths.moveTo(aim(at.clone().add(away), null), 1);
                body.lookAt(target);
                strike(target, gap, now);
            }
            case FLEE -> {
                Vector away = flat(target.getLocation(), at).multiply(10);
                if (ticks % LOOK_TICKS == 0 || !paths.hasPath()) paths.moveTo(aim(at.clone().add(away), null), 1.3);
            }
            case HOME -> {
                body.setTarget(null);
                if (!paths.hasPath()) paths.moveTo(aim(home, null), 1);
            }
            case ROAM -> {
                if (!paths.hasPath() && ThreadLocalRandom.current().nextDouble() < ROAM_CHANCE) roam(paths, at, home);
                else if (!paths.hasPath()) keepHeight(paths, at);
            }
            case IDLE -> {
                if (!paths.hasPath()) keepHeight(paths, at);
            }
        }
    }

    // ------------------------------------------------------------------ targets

    /** Its target if it still has one worth having; finds a new one now and then if it hunts. */
    private @Nullable LivingEntity target() {
        LivingEntity target = body.getTarget();
        if (target != null && !keeps(target)) {
            body.setTarget(null);
            target = null;
        }
        boolean hunts = mob.template().has(MobFlag.AGGRESSIVE) && !mob.template().has(MobFlag.PASSIVE);
        if (target == null && hunts && ticks % LOOK_TICKS == 0) {
            Player prey = nearestSeen(range());
            if (prey != null) {
                body.setTarget(prey);
                target = prey;
            }
        }
        return target;
    }

    private boolean keeps(LivingEntity target) {
        if (!MobEngine.usable(body, target) || mob.template().has(MobFlag.PASSIVE)) return false;
        // On Folia a target in another region is out of this thread's reach, and so out of the fight.
        if (!engine.owns(target.getLocation())) return false;
        double lose = range() * LOSE;
        return target.getLocation().distanceSquared(body.getLocation()) <= lose * lose;
    }

    private double range() {
        return Math.min(48, MobEngine.followRange(body));
    }

    private @Nullable Player nearestSeen(double range) {
        Player best = null;
        double nearest = Double.MAX_VALUE;
        Location at = body.getLocation();
        for (Player player : MobEngine.playersNear(body, range)) {
            double distance = player.getLocation().distanceSquared(at);
            if (distance < nearest && engine.owns(player.getLocation()) && body.hasLineOfSight(player)) {
                nearest = distance;
                best = player;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ striking

    /** Starts an attack when one is ready and reaches. */
    private void strike(LivingEntity target, double gap, long now) {
        if (swinging >= 0 || !body.hasLineOfSight(target)) return;
        int pick = Strikes.pick(attacks, readyAt, gap, now);
        if (pick < 0) return;
        MobAttack attack = attacks.get(pick);
        if (!attack.ranged() && Strikes.angle(body.getBodyYaw(), target.getX() - body.getX(),
                target.getZ() - body.getZ()) > attack.arc() / 2) {
            // Not facing it yet: the look turns it this tick.
            return;
        }
        ModelInstance model = mob.model();
        long animated = model == null ? -1 : model.strikeMillis(attack.state());
        long delay = Strikes.delay(animated, attack.windup().toMillis());
        if (model != null) {
            if (attack.windup().isZero()) model.play(attack.state());
            else model.play(attack.state(), delay);
        }
        swinging = pick;
        swingAt = target;
        strikeAt = now + delay;
        readyAt[pick] = now + Math.max(delay, attack.cooldown().toMillis());
        mob.striking(strikeAt + 150);
    }

    /** Lands the blow under way when its moment comes, if what it swung at is still there to be hit. */
    private void swing(@Nullable LivingEntity target, long now) {
        if (now < strikeAt) return;
        MobAttack attack = attacks.get(swinging);
        LivingEntity aimed = swingAt;
        swinging = -1;
        swingAt = null;
        if (aimed == null || aimed != target || !MobEngine.usable(body, aimed)) return;
        Voices.play(mob.template().model().sounds().attack(), body.getLocation());
        if (attack.ranged()) {
            throwAt(attack, aimed);
            return;
        }
        double angle = Strikes.angle(body.getBodyYaw(), aimed.getX() - body.getX(), aimed.getZ() - body.getZ());
        if (!Strikes.lands(attack, gap(aimed), angle, seen(aimed))) return;
        aimed.damage(Strikes.damage(attack, mob.template().attributes().get("attack_damage")), body);
        if (attack.knockback() > 0) {
            Vector push = flat(body.getLocation(), aimed.getLocation()).multiply(attack.knockback() / 4)
                    .setY(Math.min(0.5, attack.knockback() / 8));
            aimed.setVelocity(aimed.getVelocity().add(push));
        }
    }

    /** Its projectile, from its eyes at the target's chest. */
    private void throwAt(MobAttack attack, LivingEntity target) {
        Class<? extends Projectile> type = MobEngine.projectile(attack.projectile());
        if (type == null) {
            engine.report("projectile:" + mob.template().id() + ":" + attack.projectile(), "Mob "
                    + mob.template().id() + ": attack " + attack.name() + " throws " + attack.projectile()
                    + ", which is none of FIREBALL, SMALL_FIREBALL, WITHER_SKULL, ARROW or SNOWBALL.");
            return;
        }
        Vector direction = target.getLocation().add(0, target.getHeight() * 0.6, 0).toVector()
                .subtract(body.getEyeLocation().toVector());
        if (direction.lengthSquared() < 1.0E-6) return;
        direction.normalize().multiply(1.6);
        Projectile thrown = body.launchProjectile(type, direction);
        if (thrown instanceof Fireball fireball) {
            fireball.setIsIncendiary(false);
            fireball.setDirection(direction);
        }
        mob.thrown(thrown.getUniqueId(), Strikes.damage(attack, mob.template().attributes().get("attack_damage")));
    }

    /** Nothing solid between its eyes and the target's middle. */
    private boolean seen(LivingEntity target) {
        Location eye = body.getEyeLocation();
        Vector to = target.getLocation().add(0, target.getHeight() / 2, 0).toVector().subtract(eye.toVector());
        double length = to.length();
        if (length < 1.0E-3) return true;
        RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, to.multiply(1 / length), length,
                FluidCollisionMode.NEVER, true);
        return hit == null;
    }

    /** Blocks between its body and the target's, flat. */
    private double gap(LivingEntity target) {
        double distance = Math.hypot(target.getX() - body.getX(), target.getZ() - body.getZ());
        double vertical = Math.max(0, Math.abs(target.getY() - body.getY()) - Math.max(body.getHeight(), 1));
        return Math.max(0, Math.hypot(distance - halfWidth - target.getWidth() / 2, vertical));
    }

    // ------------------------------------------------------------------ moving

    /** Where to go for a spot: a flyer at its target's chest or its own height over the ground. */
    private Location aim(Location spot, @Nullable LivingEntity target) {
        if (!spec.kind().flies()) return spot;
        Location aimed = spot.clone();
        if (spec.kind() == MobBody.Kind.FLYER && target != null) {
            aimed.setY(target.getY() + target.getHeight() * 0.6);
        } else {
            double ground = groundUnder(aimed);
            if (!Double.isNaN(ground)) aimed.setY(ground + spec.height());
        }
        return aimed;
    }

    /** A flyer that drifted from its height goes back to it. */
    private void keepHeight(Pathfinder paths, Location at) {
        if (!spec.kind().flies() || ticks % LOOK_TICKS != 0) return;
        double ground = groundUnder(at);
        if (Double.isNaN(ground)) return;
        double wanted = ground + spec.height();
        if (Math.abs(at.getY() - wanted) > 0.5) paths.moveTo(at.clone().add(0, wanted - at.getY(), 0), 0.6);
    }

    private void roam(Pathfinder paths, Location at, @Nullable Location home) {
        double roam = mob.template().behaviour().roam();
        Location centre = roam > 0 && home != null && home.getWorld() == at.getWorld() ? home : at;
        double range = roam > 0 ? roam : 10;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 3; attempt++) {
            double angle = random.nextDouble(Math.PI * 2);
            double distance = random.nextDouble(range * 0.3, range + 1.0E-3);
            Location spot = centre.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            spot.setY(at.getY());
            if (paths.moveTo(aim(spot, null), 0.7)) return;
        }
    }

    /** The top of the ground under a spot, within 24 blocks down; {@code NaN} over the void. */
    private double groundUnder(Location spot) {
        World world = spot.getWorld();
        if (world == null || !engine.owns(spot)) return Double.NaN;
        RayTraceResult hit = world.rayTraceBlocks(spot.clone().add(0, 0.5, 0), new Vector(0, -1, 0), 24,
                FluidCollisionMode.ALWAYS, true);
        return hit == null ? Double.NaN : hit.getHitPosition().getY();
    }

    /** One block's length along the ground from one place to another. */
    private static Vector flat(Location from, Location to) {
        return MobEngine.flat(from, to);
    }
}
