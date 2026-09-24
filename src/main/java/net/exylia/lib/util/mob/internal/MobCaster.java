package net.exylia.lib.util.mob.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.mob.MobFight;
import net.exylia.lib.util.mob.MobPhase;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobSkills;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Which skills a mob casts and how: the conditions, the rotation groups, the
 * staged cast and the chains, and the fight's phases.
 *
 * <p>Everything here runs on the mob's own thread. A staged cast is a few
 * delayed entity tasks — wind-up, impact, recovery — never a task per tick, and
 * a mob casts one major skill at a time, held in {@link LiveMob#active()}.
 * A skill with no wind-up still lands inline, in the same call, exactly as the
 * engine cast it before stages existed.
 */
final class MobCaster {

    /** How long a mob stays rooted after a staged skill lands, in ticks. */
    static final long RECOVERY = 8L;

    /** How many skills one cast may chain into: A → B → A → B → A stops there. */
    static final int MAX_CHAIN = 4;

    /** The most projectiles one aimed skill throws. */
    private static final int MAX_VOLLEY = 8;

    private static final NamespacedKey ROOT_KEY = new NamespacedKey("exylialib", "mob_root");
    private static final NamespacedKey PHASE_KEY = new NamespacedKey("exylialib", "mob_phase");
    /** Stops it where it stands, over whatever its speed is: a modifier stacks with SPEED skills. */
    private static final AttributeModifier ROOT =
            new AttributeModifier(ROOT_KEY, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1);

    /** How long a phase change holds the mob while its style plays, in ticks. */
    static final long TRANSITION = 24L;

    private final MobEngine engine;
    private final TaskScheduler tasks;
    private final MobShows shows;
    private final MobMoves moves;

    MobCaster(MobEngine engine, TaskScheduler tasks, MobShows shows, MobMoves moves) {
        this.engine = engine;
        this.tasks = tasks;
        this.shows = shows;
        this.moves = moves;
    }

    /**
     * One staged major cast under way: the stage waiting to run, which a death
     * cancels, and what it has drawn, which a death takes off the screen.
     */
    static final class Active {
        volatile @Nullable TaskHandle stage;
        volatile @Nullable Stage show;
    }

    // ------------------------------------------------------------------ fire

    /**
     * Casts what a trigger fires.
     *
     * @param effectsOnly cast only the EFFECT skills: the hit that breaks a mob in hits mode
     */
    void fire(MobSkill.Trigger trigger, LivingEntity entity, LiveMob mob, @Nullable LivingEntity about,
              boolean effectsOnly) {
        List<MobSkill> skills = mob.template().skills();
        if (skills.isEmpty()) return;
        long now = System.currentTimeMillis();
        Situation situation = new Situation(entity, mob, about);
        for (int index = 0; index < skills.size(); index++) {
            MobSkill skill = skills.get(index);
            if (skill.trigger() != trigger || skill.grouped()) continue;
            if (effectsOnly && skill.type() != MobSkill.Type.EFFECT) continue;
            if (!eligible(situation, skill, now, false)) continue;
            if (!mob.attempt(index, skill, now, ThreadLocalRandom.current().nextDouble())) continue;
            cast(entity, mob, index, skill, situation.target(), 0);
        }
        if (trigger == MobSkill.Trigger.INTERVAL && !effectsOnly) rotate(situation, now);
    }

    /** Each rotation group that is due casts one of its members. */
    private void rotate(Situation situation, long now) {
        LiveMob mob = situation.mob;
        List<MobSkill> skills = mob.template().skills();
        for (String group : mob.groups()) {
            if (!mob.groupDue(group, now)) continue;
            List<Integer> eligible = new ArrayList<>();
            for (int index = 0; index < skills.size(); index++) {
                MobSkill skill = skills.get(index);
                if (skill.grouped() && skill.cast().group().equals(group) && eligible(situation, skill, now, false)) {
                    eligible.add(index);
                }
            }
            long period = mob.template().fight().period(group).toMillis();
            int picked = mob.rotate(group, period, eligible, now, ThreadLocalRandom.current().nextDouble());
            if (picked >= 0) cast(situation.entity, mob, picked, skills.get(picked), situation.target(), 0);
        }
    }

    /** A low-health skill, spent the first time health reaches its threshold, then held to its conditions. */
    void lowHealth(LivingEntity entity, LiveMob mob, double fraction) {
        List<MobSkill> skills = mob.template().skills();
        Situation situation = null;
        long now = System.currentTimeMillis();
        for (int index = 0; index < skills.size(); index++) {
            MobSkill skill = skills.get(index);
            if (skill.trigger() != MobSkill.Trigger.LOW_HEALTH) continue;
            if (!mob.lowHealth(index, skill, fraction, ThreadLocalRandom.current().nextDouble())) continue;
            if (situation == null) situation = new Situation(entity, mob, null);
            if (!eligible(situation, skill, now, false)) continue;
            cast(entity, mob, index, skill, situation.target(), 0);
        }
    }

    /**
     * Everything that may keep a skill from being cast, checked before the dice:
     * minions never summon, its conditions, a target where it needs one, the
     * global cooldown and a cast already under way.
     *
     * @param chained cast by another skill's {@code then}: past the global cooldown and the cast under way
     */
    private boolean eligible(Situation situation, MobSkill skill, long now, boolean chained) {
        LiveMob mob = situation.mob;
        if (skill.type() == MobSkill.Type.SUMMON && mob.summoned()) return false;
        MobSkill.Cast cast = skill.cast();
        if (!cast.when().equals(MobSkill.Gate.ANY) && !cast.when().admits(situation.health(),
                situation.targetDistance(), situation.nearestDistance(cast.when().nearby()), mob.fightPhase())) {
            return false;
        }
        if (skill.needsTarget() && situation.target() == null) return false;
        if (MobAim.findsPlayers(cast.aim())
                && MobEngine.playersNear(situation.entity, MobAim.reach(skill)).isEmpty()) {
            return false;
        }
        if (chained || !skill.major()) return true;
        return !mob.globalCooling(now) && mob.active() == null;
    }

    // ------------------------------------------------------------------ cast

    private void cast(LivingEntity entity, LiveMob mob, int index, MobSkill skill, @Nullable LivingEntity target,
                      int depth) {
        MobSkill.Cast cast = skill.cast();
        long now = System.currentTimeMillis();
        if (skill.major()) {
            long gcd = mob.template().fight().globalCooldown().toMillis();
            if (gcd > 0) mob.globalCooldown(now + cast.windup().toMillis() + gcd);
        }
        MobAim.Lock lock = MobAim.lock(entity.getLocation(), target == null ? null : target.getLocation());
        if (cast.windup().isZero()) {
            land(entity, mob, index, skill, lock, target, depth, shows.begin(entity, mob, skill, lock));
            return;
        }
        long windup = ticks(cast.windup().toMillis());
        if (!skill.major()) {
            // A sound or a command with a wind-up is only late: it neither roots the mob nor holds its turn.
            engine.play(cast.windupLines(), entity.getLocation(), entity);
            Stage stage = shows.begin(entity, mob, skill, lock);
            tasks.runAtEntityLater(entity, windup, () -> {
                if (mob.alive() && engine.tracks(entity, mob)) land(entity, mob, index, skill, lock, target, depth, stage);
            });
            return;
        }
        Active active = new Active();
        mob.active(active);
        root(entity);
        if (lock.point() != null) entity.setRotation(lock.yaw(), entity.getLocation().getPitch());
        engine.play(cast.windupLines(), entity.getLocation(), entity);
        Stage stage = shows.begin(entity, mob, skill, lock);
        active.show = stage;
        active.stage = tasks.runAtEntityLater(entity, windup, () -> {
            if (!current(entity, mob, active)) return;
            land(entity, mob, index, skill, lock, target, depth, stage);
            // A chain may have taken over; only the cast still in charge recovers, after a dash has run.
            if (mob.active() == active) {
                active.stage = tasks.runAtEntityLater(entity, RECOVERY + stage.busy(), () -> end(entity, mob, active));
            }
        });
    }

    /** Whether a stage still belongs to the mob: alive, and this is its cast. */
    private boolean current(LivingEntity entity, LiveMob mob, Active active) {
        return mob.active() == active && mob.alive() && engine.tracks(entity, mob);
    }

    /** The end of a staged cast: it moves again. */
    private void end(LivingEntity entity, LiveMob mob, Active active) {
        if (mob.active() != active) return;
        mob.active(null);
        unroot(entity);
    }

    /**
     * Drops the cast under way without landing it, on every way a mob ends:
     * death, a break, expiry, removal.
     */
    void cancel(LiveMob mob) {
        Active active = mob.active();
        mob.active(null);
        if (active != null) {
            if (active.stage != null) active.stage.cancel();
            if (active.show != null) active.show.cancel();
        }
        mob.endLingering();
    }

    /**
     * Lands a skill, guarded against itself, then casts what it chains into.
     *
     * <p>A skill that hurts somebody raises a damage event, which is an attack
     * trigger for this very mob: without the guard, an attack skill that deals
     * area damage on a zero cooldown recurses until the stack gives out.
     */
    private void land(LivingEntity entity, LiveMob mob, int index, MobSkill skill, MobAim.Lock lock,
                      @Nullable LivingEntity target, int depth, Stage stage) {
        if (mob.casting()) return;
        mob.casting(true);
        try {
            Location from = entity.getLocation();
            boolean did;
            if (skill.cast().aim() == MobSkill.Aim.AUTO) {
                LivingEntity aimed = target;
                // A wind-up gives the target time to die or leave.
                if (!skill.cast().windup().isZero() && skill.needsTarget() && !MobEngine.usable(entity, aimed)) {
                    aimed = engine.target(entity, null);
                }
                did = (aimed != null || !skill.needsTarget()) && engine.apply(entity, mob, skill, aimed, stage);
            } else {
                did = aimed(entity, mob, skill, lock, target, stage);
            }
            if (did) {
                engine.play(skill.effect(), from, entity);
                shows.land(stage);
            }
        } catch (RuntimeException | LinkageError failure) {
            engine.failed(mob, index, skill, failure);
        } finally {
            mob.casting(false);
        }
        chain(entity, mob, skill, target, depth);
    }

    /** Casts the skill a landed one names in {@code then}, past the dice and the cooldown, never past its conditions. */
    private void chain(LivingEntity entity, LiveMob mob, MobSkill skill, @Nullable LivingEntity target, int depth) {
        int next = next(mob.template().skills(), skill, depth);
        if (next < 0 || !engine.tracks(entity, mob)) return;
        MobSkill chained = mob.template().skills().get(next);
        Situation situation = new Situation(entity, mob, target);
        if (!eligible(situation, chained, System.currentTimeMillis(), true)) return;
        cast(entity, mob, next, chained, situation.target(), depth + 1);
    }

    /**
     * The skill a landed one chains into.
     *
     * @param skills the mob's skills
     * @param landed the skill that just landed
     * @param depth  how many chained casts led to it; the first cast is {@code 0}
     * @return its position, or {@code -1} for none, for a name nobody has, or past {@link #MAX_CHAIN}
     */
    static int next(List<MobSkill> skills, MobSkill landed, int depth) {
        String then = landed.cast().then();
        if (then.isEmpty() || depth >= MAX_CHAIN) return -1;
        for (int index = 0; index < skills.size(); index++) {
            if (skills.get(index).cast().name().equalsIgnoreCase(then)) return index;
        }
        return -1;
    }

    // ------------------------------------------------------------------ aim

    /**
     * Lands a skill with an aim of its own.
     *
     * @return whether it did something, which is when its effect lines play
     */
    private boolean aimed(LivingEntity entity, LiveMob mob, MobSkill skill, MobAim.Lock lock,
                          @Nullable LivingEntity target, Stage stage) {
        switch (skill.type()) {
            // What happens to the mob itself goes where its type sends it.
            case HEAL, JUMP, SIZE, SPEED, BABY, EFFECT, SHIELD -> {
                return engine.apply(entity, mob, skill, target, stage);
            }
            default -> { }
        }
        MobSkill.Aim aim = skill.cast().aim();
        Location point;
        List<LivingEntity> victims = new ArrayList<>();
        switch (aim) {
            case TARGET -> {
                if (!MobEngine.usable(entity, target)) return false;
                point = target.getLocation();
                victims.add(target);
            }
            case NEAREST, FARTHEST, RANDOM -> {
                List<Player> players = MobEngine.playersNear(entity, MobAim.reach(skill));
                if (players.isEmpty()) return false;
                Location at = entity.getLocation();
                Player one = switch (aim) {
                    case NEAREST -> players.stream().min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(at))).get();
                    case FARTHEST -> players.stream().max(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(at))).get();
                    default -> players.get(ThreadLocalRandom.current().nextInt(players.size()));
                };
                point = one.getLocation();
                victims.add(one);
            }
            case ALL, CONE, LINE -> {
                double reach = MobAim.reach(skill);
                Vector origin = lock.origin().toVector();
                Location end = MobAim.lineEnd(lock, reach);
                for (Player player : MobEngine.playersNear(entity, reach)) {
                    Vector at = player.getLocation().toVector();
                    boolean inside = switch (aim) {
                        case CONE -> MobAim.inCone(origin, lock.yaw(), reach, MobAim.spread(skill), at);
                        case LINE -> MobAim.onLine(origin, end.toVector(), MobAim.spread(skill), at);
                        default -> true;
                    };
                    if (inside) victims.add(player);
                }
                point = aim == MobSkill.Aim.LINE ? end : entity.getLocation();
            }
            case SELF -> point = entity.getLocation();
            case GROUND -> {
                if (lock.point() == null) return false;
                point = lock.point();
            }
            default -> {
                return false;
            }
        }
        // A body aimed at one spot reaches around it: SELF and GROUND always, an area type around its one victim.
        boolean sweeps = aim == MobSkill.Aim.ALL || aim == MobSkill.Aim.CONE || aim == MobSkill.Aim.LINE;
        boolean spot = aim == MobSkill.Aim.SELF || aim == MobSkill.Aim.GROUND;
        boolean around = spot || !sweeps && area(skill) && skill.radius() > 0;
        double area = skill.radius() > 0 ? skill.radius() : aim == MobSkill.Aim.GROUND ? MobAim.SPOT : 0;
        // Pushed away from where it lands, except the aims that sweep out from the mob.
        boolean fromPoint = !sweeps;
        Location landing = point;
        stage.landed(aim == MobSkill.Aim.LINE ? entity.getLocation() : point);
        // What moves the mob, or stays on its own timer, starts here, on the mob's thread.
        switch (skill.type()) {
            case LEAP -> {
                double strength = skill.amount() > 0 ? skill.amount() : 1;
                entity.setVelocity(MobEngine.flat(entity.getLocation(), landing)
                        .multiply(0.9 * strength).setY(Math.min(1.2, 0.45 * strength)));
                moves.pounce(entity, mob, skill, stage);
                return true;
            }
            case DASH -> {
                stage.busy(moves.dash(entity, mob, skill, stage, aim == MobSkill.Aim.SELF ? null : landing));
                return true;
            }
            case ZONE -> {
                return moves.zone(entity, mob, skill, stage, landing, aim == MobSkill.Aim.SELF) != null;
            }
            case BARRAGE -> {
                moves.barrage(entity, mob, skill, stage, landing);
                return true;
            }
            default -> { }
        }
        Runnable work = () -> hitAll(entity, mob, skill, landing,
                around ? playersAround(landing, area) : victims, fromPoint, stage);
        // On Folia the spot may belong to another region than the mob; its players are that region's.
        if (tasks.isOwnedBy(landing)) {
            work.run();
        } else {
            tasks.runAtLocation(landing, work);
        }
        return true;
    }

    /** The types that reach an area around where they land rather than one body. */
    private static boolean area(MobSkill skill) {
        return switch (skill.type()) {
            case AREA_DAMAGE, PUSH -> true;
            case POTION -> skill.radius() > 0;
            default -> false;
        };
    }

    private static List<LivingEntity> playersAround(Location point, double radius) {
        List<LivingEntity> players = new ArrayList<>();
        if (radius <= 0) return players;
        double squared = radius * radius;
        for (org.bukkit.entity.Entity nearby : point.getWorld().getNearbyEntities(point, radius, radius, radius)) {
            if (nearby instanceof Player player && MobEngine.huntable(player)
                    && player.getLocation().distanceSquared(point) <= squared) {
                players.add(player);
            }
        }
        return players;
    }

    /**
     * What each type does to the bodies an aim found.
     *
     * @param fromPoint pushes go away from the landing point rather than from the mob
     */
    private void hitAll(LivingEntity entity, LiveMob mob, MobSkill skill, Location point, List<LivingEntity> hit,
                        boolean fromPoint, Stage stage) {
        Location at = entity.getLocation();
        double strength = skill.amount() > 0 ? skill.amount() : 1;
        // A summon's bodies are its minions, not the players it set them on.
        if (skill.type() != MobSkill.Type.SUMMON) stage.reached(hit);
        switch (skill.type()) {
            // Started on the mob's thread in aimed(): they move the mob or keep their own timer.
            case LEAP, DASH, ZONE, BARRAGE, SHIELD -> { }
            case CHAIN -> {
                if (!hit.isEmpty()) moves.chain(entity, mob, skill, stage, hit.getFirst());
            }
            case PULL -> hit.forEach(body -> body.setVelocity(
                    MobEngine.flat(body.getLocation(), at).multiply(0.9 * strength).setY(0.35)));
            case PUSH -> {
                Location centre = fromPoint ? point : at;
                hit.forEach(body -> body.setVelocity(MobEngine.flat(centre, body.getLocation()).multiply(strength).setY(0.45)));
            }
            case POTION -> {
                PotionEffect effect = engine.potionOf(mob, skill);
                if (effect != null) hit.forEach(body -> body.addPotionEffect(effect));
            }
            case LIGHTNING -> {
                if (hit.isEmpty()) point.getWorld().strikeLightningEffect(point);
                for (LivingEntity body : hit) {
                    body.getWorld().strikeLightningEffect(body.getLocation());
                    if (skill.amount() > 0) body.damage(skill.amount(), entity);
                }
            }
            case PROJECTILE -> hit.stream().limit(MAX_VOLLEY).forEach(body -> engine.shoot(entity, mob, skill, body));
            case AREA_DAMAGE -> hit.forEach(body -> {
                body.damage(skill.amount(), entity);
                MobEngine.burn(body, skill.duration());
            });
            case IGNITE -> hit.forEach(body -> body.setFireTicks(Math.max(body.getFireTicks(),
                    (int) Math.min(Integer.MAX_VALUE, skill.duration().toMillis() / 50))));
            case COMMAND -> {
                if (hit.isEmpty()) engine.command(mob, skill, null);
                hit.forEach(body -> engine.command(mob, skill, body));
            }
            case TELEPORT -> {
                if (skill.radius() > 0) {
                    engine.apply(entity, mob, skill, null, stage);
                } else if (!hit.isEmpty()) {
                    engine.apply(entity, mob, skill, hit.getFirst(), stage);
                } else {
                    Location spot = point.clone();
                    spot.setYaw(at.getYaw());
                    spot.setPitch(at.getPitch());
                    if (spot.getBlock().isPassable() && spot.clone().add(0, 1, 0).getBlock().isPassable()) {
                        MobEngine.move(entity, spot);
                        stage.moved(at, spot);
                        engine.play(skill.effect(), spot, entity);
                    }
                }
            }
            case SUMMON -> stage.reached(engine.summon(entity, mob, skill, hit.isEmpty() ? null : hit.getFirst(),
                    stage.spots));
            case HEAL, JUMP, SIZE, SPEED, BABY, EFFECT -> engine.apply(entity, mob, skill, null, stage);
        }
    }

    // ------------------------------------------------------------------ phase

    /**
     * Moves the fight on when its health crossed a phase threshold: the phase's
     * multipliers replace the previous one's, its name gains the suffix, and its
     * PHASE skills are cast. Only the phase it lands in fires, when one hit
     * crosses two thresholds at once.
     *
     * @param share health over maximum health, or hits left over hits
     */
    void phase(LivingEntity entity, LiveMob mob, double share) {
        MobFight fight = mob.template().fight();
        if (fight.phases().isEmpty() || !mob.enterPhase(fight.phaseAt(share))) return;
        MobPhase phase = fight.phase(mob.fightPhase());
        if (phase != null) {
            multiply(entity, "movement_speed", phase.speed());
            multiply(entity, "attack_damage", phase.damage());
        }
        engine.name(entity, mob);
        // The change plays its style, the mob held still for it, and its PHASE skills come after. A cast
        // under way is not cut short: it lands, and the change is only drawn over it.
        boolean still = phase != null && !phase.style().equalsIgnoreCase(MobSkills.NO_STYLE);
        if (!still || mob.active() != null) {
            if (still) shows.land(shows.transition(entity, mob, phase, false));
            fire(MobSkill.Trigger.PHASE, entity, mob, null, false);
            return;
        }
        Active active = new Active();
        mob.active(active);
        root(entity);
        Stage stage = shows.transition(entity, mob, phase, true);
        active.show = stage;
        active.stage = tasks.runAtEntityLater(entity, TRANSITION, () -> {
            if (!current(entity, mob, active)) return;
            shows.land(stage);
            end(entity, mob, active);
            fire(MobSkill.Trigger.PHASE, entity, mob, null, false);
        });
    }

    /** The damage a hit does after the phase's resistance. */
    static double resisted(LiveMob mob, double damage) {
        MobPhase phase = mob.template().fight().phase(mob.fightPhase());
        return phase == null ? damage : damage / phase.resist();
    }

    private static void multiply(LivingEntity entity, String key, double factor) {
        AttributeInstance instance = MobEngine.instance(entity, key);
        if (instance == null) return;
        for (AttributeModifier modifier : List.copyOf(instance.getModifiers())) {
            if (PHASE_KEY.equals(modifier.getKey())) instance.removeModifier(modifier);
        }
        if (factor != 1) {
            instance.addModifier(new AttributeModifier(PHASE_KEY, factor - 1,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    // ------------------------------------------------------------------ root

    private static void root(LivingEntity entity) {
        AttributeInstance speed = MobEngine.instance(entity, "movement_speed");
        if (speed != null && speed.getModifiers().stream().noneMatch(modifier -> ROOT_KEY.equals(modifier.getKey()))) {
            speed.addModifier(ROOT);
        }
        if (entity instanceof Mob walker) {
            try {
                walker.getPathfinder().stopPathfinding();
            } catch (LinkageError spigot) {
                // No pathfinder on plain Spigot; the speed modifier alone holds it.
            }
        }
    }

    private static void unroot(LivingEntity entity) {
        AttributeInstance speed = MobEngine.instance(entity, "movement_speed");
        if (speed == null) return;
        for (AttributeModifier modifier : List.copyOf(speed.getModifiers())) {
            if (ROOT_KEY.equals(modifier.getKey())) speed.removeModifier(modifier);
        }
    }

    private static long ticks(long millis) {
        return Math.max(1, Math.round(millis / 50.0));
    }

    // ------------------------------------------------------------- situation

    /** What the checks of one trigger read, each looked up once and only when a skill asks. */
    private final class Situation {
        final LivingEntity entity;
        final LiveMob mob;
        private final @Nullable LivingEntity about;
        private boolean looked;
        private @Nullable LivingEntity target;
        private double health = Double.NaN;

        Situation(LivingEntity entity, LiveMob mob, @Nullable LivingEntity about) {
            this.entity = entity;
            this.mob = mob;
            this.about = about;
        }

        @Nullable LivingEntity target() {
            if (!looked) {
                target = engine.target(entity, about);
                looked = true;
            }
            return target;
        }

        double health() {
            if (Double.isNaN(health)) {
                health = mob.usesHits() ? mob.hitsLeft() / (double) mob.maxHits()
                        : entity.getHealth() / MobEngine.maxHealth(entity);
            }
            return health;
        }

        double targetDistance() {
            LivingEntity aimed = target();
            return aimed == null ? Double.NaN : aimed.getLocation().distance(entity.getLocation());
        }

        double nearestDistance(double within) {
            if (within <= 0) return Double.NaN;
            Location at = entity.getLocation();
            double best = Double.NaN;
            for (Player player : MobEngine.playersNear(entity, within)) {
                double distance = player.getLocation().distance(at);
                if (Double.isNaN(best) || distance < best) best = distance;
            }
            return best;
        }
    }
}
