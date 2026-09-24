package net.exylia.lib.util.mob.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.display.Telegraphs;
import net.exylia.lib.display.Vfx;
import net.exylia.lib.display.VfxRun;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.Effects;
import net.exylia.lib.util.mob.MobPhase;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobSkills;
import net.exylia.lib.util.mob.MobTheme;
import net.exylia.lib.util.mob.MobTheme.Role;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plays skill styles: the wind-up as a cast starts, the impact as it lands,
 * and the parts of a type that happen over time — afterimages, arcs, zones,
 * strikes, shields — as the mechanics reach them.
 *
 * <p>Everything here is a visual. It gives up rather than waits: with nobody
 * within the effect radius, or the plugin already playing
 * {@link net.exylia.lib.util.mob.MobVisuals#maxCasts()} large effects, a cast
 * is simply not drawn and plays exactly the same. The stage a cast carries
 * says which.
 */
final class MobShows {

    /** A crouch or a swell, on the scale attribute; a modifier, so it rides on SIZE. */
    private static final NamespacedKey PULSE = new NamespacedKey("exylialib", "mob_pulse");

    /** The longest a preview plays: long enough to read, short enough to wait for. */
    static final long PREVIEW_MAX = 7_000L;

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final MobEngine engine;
    private final MobReactions reactions;
    private volatile MobTheme theme = MobTheme.DEFAULT;

    MobShows(Plugin plugin, TaskScheduler tasks, MobEngine engine, MobReactions reactions) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.engine = engine;
        this.reactions = reactions;
    }

    void theme(@NotNull MobTheme theme) {
        this.theme = theme;
    }

    @NotNull MobTheme theme() {
        return theme;
    }

    // ------------------------------------------------------------------ cast

    /**
     * A cast starting: works out whether it is drawn, and draws its wind-up.
     *
     * @return the stage, which the mechanics read whether or not it is drawn
     */
    Stage begin(LivingEntity entity, LiveMob mob, MobSkill skill, MobAim.Lock lock) {
        MobStyle style = MobStyle.of(MobSkills.styleOf(skill));
        List<Player> viewers = List.of();
        if (style != null) {
            viewers = SequenceTarget.at(lock.origin()).within(engine.effectRadius()).observers();
            if (!viewers.isEmpty() && !reactions.claim(skill.cast().windup().toMillis() + style.length(skill))) {
                viewers = List.of();
            }
        }
        Stage stage = new Stage(plugin, skill, style, lock, entity, mob, viewers, null,
                Math.max(0.3, entity.getWidth()), Math.max(0.3, entity.getHeight()),
                MobBodies.palette(mob.template().type(), mob.variantShown(), mob.bodyShown()),
                viewers.isEmpty() ? MobBodies.block(Material.COARSE_DIRT) : MobStyle.groundOf(lock.origin()),
                theme, reactions.visuals(), false);
        if (!stage.drawn()) return stage;
        if (style == MobStyle.PORTAL && skill.type() == MobSkill.Type.SUMMON) {
            stage.spots.addAll(engine.summonSpots(entity, mob, skill));
        }
        if (!skill.cast().windup().isZero()) {
            Vfx vfx = stage.vfx(stage.origin());
            style.windup(stage, vfx);
            stage.own(vfx.play(plugin));
        }
        return stage;
    }

    /**
     * A cast landing, after its mechanics: its impact, and what the style does
     * to the caster — a blink's vanish, an enrage's outline, a portal's minions.
     */
    void land(Stage stage) {
        if (!stage.drawn()) return;
        LivingEntity caster = stage.caster();
        Location now = caster != null ? caster.getLocation() : stage.origin();
        Location at = stage.landedOr(now);
        Vfx vfx = stage.vfx(at);
        stage.style.impact(stage, vfx, at);
        vfx.play(plugin);
        if (caster == null) return;
        switch (stage.style) {
            case BLINK -> {
                if (stage.arrived() != null) tasks.runAtEntityLater(caster, 5L, MobBodies.vanish(caster));
            }
            case ENRAGE -> {
                LiveMob mob = stage.mob;
                long ticks = stage.skill.type() == MobSkill.Type.SPEED ? stage.skill.duration().toMillis() / 50 : 0;
                if (mob != null) engine.flare(caster, mob, colourName(stage.main()), ticks);
            }
            case PORTAL -> {
                for (LivingEntity minion : stage.reached()) {
                    if (stage.windup() > 0) {
                        tasks.runAtEntityLater(minion, 2L, MobBodies.vanish(minion));
                        minion.setVelocity(minion.getVelocity().setY(0.3));
                    } else {
                        reactions.portal(minion, minion.getLocation(), stage.viewers);
                    }
                }
            }
            default -> { }
        }
    }

    /**
     * A fight entering a phase: its style, enrage by default.
     *
     * @param held whether the mob is held still for {@link MobCaster#TRANSITION} ticks, which is its wind-up;
     *             a mob busy casting is not, and only the moment itself is drawn
     */
    Stage transition(LivingEntity entity, LiveMob mob, MobPhase phase, boolean held) {
        String style = phase.style().isBlank() ? "enrage" : phase.style();
        MobSkill change = new MobSkill(MobSkill.Trigger.PHASE, MobSkill.Type.SPEED, 1, Duration.ZERO, 0, 0, 1,
                Duration.ZERO, "", "", MobSkill.Cast.NONE.withStyle(style)
                .withWindup(Duration.ofMillis(held ? MobCaster.TRANSITION * 50 : 0)));
        return begin(entity, mob, change, MobAim.lock(entity.getLocation(), null));
    }

    // -------------------------------------------------------------- over time

    /** One stride of a dash: an afterimage in the mob's colours where it just was. */
    void step(Stage stage, Location at, int stride) {
        if (!stage.drawn()) return;
        Vfx vfx = stage.vfx(at);
        afterimage(stage, vfx, 0, at);
        if (stride % 2 == 0) Shapes.sound(vfx, 0, "ENTITY_RAVAGER_STEP", 0.7f, 1.2f);
        vfx.play(plugin);
    }

    /** Somebody a dash ran through. */
    void dashHit(Stage stage, LivingEntity body) {
        if (!stage.drawn()) return;
        Location at = body.getLocation();
        Vfx vfx = stage.vfx(at);
        vfx.particle(0, Particle.CRIT, MobStyle.chest(body), stage.count(12, 6), 0.3, 0.4, 0.3, 0.3, null);
        Shapes.sound(vfx, 0, "ENTITY_GOAT_RAM_IMPACT", 1.0f, 1.0f);
        Shapes.sound(vfx, 0, "ENTITY_PLAYER_ATTACK_KNOCKBACK", 1.0f, 0.9f);
        int beats = stage.shakes(2);
        if (beats > 0) vfx.shake(0, 2.5, beats, 60L);
        vfx.play(plugin);
    }

    /** A dash that ran its course: stars round its head for a moment. */
    void daze(Stage stage) {
        if (!stage.drawn()) return;
        LivingEntity caster = stage.caster();
        Location at = caster != null ? caster.getLocation() : stage.origin();
        Vfx vfx = stage.vfx(at);
        daze(stage, vfx, 0, caster, at);
        vfx.play(plugin);
    }

    /** One jump of a chain, from where it was to whom it reached. */
    void jump(Stage stage, Location from, Location to, int jump) {
        if (!stage.drawn()) return;
        Vfx vfx = stage.vfx(from);
        MobStyle.arc(stage, vfx, 0, from, to, jump);
        vfx.play(plugin);
    }

    /**
     * One strike of a barrage: a circle on the ground for a second, then the
     * arrow that lands in it.
     *
     * @param delay when its circle appears, from now
     * @return the run, for a death to take off the screen; {@code null} when not drawn
     */
    @Nullable VfxRun strike(Stage stage, Location point, long delay) {
        if (!stage.drawn()) return null;
        Vfx vfx = Vfx.at(point).viewers(stage.viewers).lod(true);
        strike(stage, vfx, delay, point);
        return vfx.play(plugin);
    }

    /**
     * A zone's look for as long as it lasts: a ring of blades riding the mob,
     * or a poison pool on the ground.
     *
     * @param rider what it rides, or {@code null} to stay at {@code centre}
     * @return the run, which the zone stops early; {@code null} when not drawn
     */
    @Nullable VfxRun zone(Stage stage, Location centre, @Nullable LivingEntity rider, double radius, long millis) {
        if (!stage.drawn()) return null;
        Vfx vfx = stage.vfx(centre);
        boolean blades = stage.style == MobStyle.BLADES || stage.style != MobStyle.MIASMA && rider != null;
        if (blades) {
            blades(stage, vfx, centre, rider, radius, millis);
        } else {
            pool(stage, vfx, centre, radius, millis);
        }
        return vfx.play(plugin);
    }

    /** A riding ring of blades cutting past: its sweep, heard where the mob is now. */
    void zoneTick(Stage stage, Location at, int tick) {
        if (!stage.drawn() || stage.style == MobStyle.MIASMA) return;
        Vfx vfx = stage.vfx(at);
        Shapes.sound(vfx, 0, "ENTITY_PLAYER_ATTACK_SWEEP", 0.5f, (float) (1.3 + (tick % 3) * 0.08));
        vfx.play(plugin);
    }

    /**
     * A shield's look for as long as it lasts: stacked rings of glass round
     * the body, turning slowly.
     *
     * @param rider what it rides, or {@code null} to stay where the cast began
     * @return the run, which a death stops; {@code null} when not drawn
     */
    @Nullable VfxRun ward(Stage stage, @Nullable LivingEntity rider, long millis) {
        if (!stage.drawn()) return null;
        Location at = rider != null ? rider.getLocation() : stage.origin();
        Vfx vfx = stage.vfx(at);
        ward(stage, vfx, 0, rider, at, millis);
        return vfx.play(plugin);
    }

    /**
     * A hit the shield took: a flash where it struck, and a chime; at most one
     * every {@link #WARD_GAP} ms per mob.
     *
     * @param full whether it stopped the whole hit
     */
    void absorbed(LivingEntity entity, LiveMob mob, @Nullable Entity attacker, boolean full) {
        if (!mob.wardShown(System.currentTimeMillis(), WARD_GAP)) return;
        Location at = entity.getLocation();
        List<Player> viewers = SequenceTarget.at(at).within(Math.min(MobReactions.NEAR, engine.effectRadius()))
                .observers();
        if (viewers.isEmpty()) return;
        Vector towards = attacker == null || !attacker.getWorld().equals(at.getWorld()) ? MobAim.facing(at.getYaw())
                : attacker.getLocation().toVector().subtract(at.toVector()).setY(0);
        if (towards.lengthSquared() < 1.0E-4) towards = MobAim.facing(at.getYaw());
        Vfx vfx = Vfx.at(at).viewers(viewers);
        int shield = colour(Role.SHIELD);
        double reach = Math.max(entity.getWidth() / 2 + 0.7, entity.getHeight() * 0.45);
        flash(vfx, 0, at.clone().add(0, entity.getHeight() * 0.55, 0), towards.normalize(), reach, shield, full);
        vfx.play(plugin);
    }

    /** A leap coming down hard: the ground breaks in a ring where it lands. */
    void landed(Stage stage, Location at, double radius) {
        if (!stage.drawn()) return;
        Vfx vfx = stage.vfx(at);
        landing(stage, vfx, 0, at, radius);
        vfx.play(plugin);
    }

    // --------------------------------------------------------------- preview

    /**
     * Plays a skill's style to one player, from a stand-in caster four blocks
     * in front of them, aimed at them. Nothing lands: no damage, no mob, no
     * minion.
     *
     * @return how long it plays, in milliseconds; {@code 0} when the skill has no style
     */
    long preview(Player viewer, MobSkill skill) {
        MobStyle style = MobStyle.of(MobSkills.styleOf(skill));
        if (style == null) return 0L;
        Location eye = viewer.getLocation();
        Vector ahead = MobAim.facing(eye.getYaw());
        Location spot = eye.clone().add(ahead.clone().multiply(4));
        spot.setY(ground(spot, eye.getY()));
        float yaw = MobAim.yaw(spot.toVector(), eye.toVector(), eye.getYaw() + 180);
        spot.setYaw(yaw);
        spot.setPitch(0);
        Location target = eye.clone();
        target.setY(ground(target, eye.getY()));
        Stage stage = new Stage(plugin, skill, style, new MobAim.Lock(spot, yaw, target), null, null,
                List.of(viewer), null, Stage.WIDTH, Stage.HEIGHT, MobBodies.FALLBACK, MobStyle.groundOf(spot),
                theme, reactions.visuals(), true);
        return preview(stage, viewer);
    }

    /** Draws a preview's whole timeline; split out so it is built without a player's thread. */
    long preview(Stage stage, @Nullable Player viewer) {
        MobSkill skill = stage.skill;
        MobStyle style = stage.style;
        long windup = stage.windup();
        Location spot = stage.origin();
        Location aim = stage.point();
        Vector side = new Vector(-MobAim.facing(stage.yaw()).getZ(), 0, MobAim.facing(stage.yaw()).getX());
        // The viewer stands in for whoever the skill reaches, where a style draws on the bodies it hit.
        boolean onBodies = switch (skill.type()) {
            case POTION, PULL, LIGHTNING, IGNITE, PROJECTILE, AREA_DAMAGE -> true;
            default -> false;
        };
        if (viewer != null && onBodies) stage.reached(List.of(viewer));
        switch (skill.type()) {
            case SUMMON -> {
                stage.spots.add(spot.clone().add(side.clone().multiply(2)));
                stage.spots.add(spot.clone().subtract(side.clone().multiply(2)));
            }
            case TELEPORT -> stage.moved(spot, aim.clone().subtract(MobAim.facing(stage.yaw()).multiply(-1.5)));
            default -> { }
        }
        long length = windup + Math.min(PREVIEW_MAX, style.length(skill));
        Vfx stand = stage.vfx(spot);
        stand.display(0, Shapes.glowless(Material.WHITE_STAINED_GLASS), DisplayMotion.builder()
                .life(Math.min(PREVIEW_MAX, length + 400)).from(0, Stage.HEIGHT / 2, 0).to(0, Stage.HEIGHT / 2, 0)
                .scale(new double[]{Stage.WIDTH, Stage.HEIGHT, Stage.WIDTH},
                        new double[]{Stage.WIDTH, Stage.HEIGHT, Stage.WIDTH}).build());
        stand.play(plugin);
        if (windup > 0) {
            Vfx vfx = stage.vfx(spot);
            style.windup(stage, vfx);
            vfx.play(plugin);
        }
        Runnable impact = () -> previewImpact(stage, spot, aim, side);
        if (windup <= 0) {
            impact.run();
        } else if (viewer != null) {
            tasks.runAtEntityLater(viewer, Math.max(1L, Math.round(windup / 50.0)), impact);
        } else {
            impact.run();
        }
        return Math.min(PREVIEW_MAX, length + 300);
    }

    private void previewImpact(Stage stage, Location spot, Location aim, Vector side) {
        MobSkill skill = stage.skill;
        Location at = switch (skill.type()) {
            case TELEPORT -> stage.arrived() == null ? spot : stage.arrived();
            default -> MobStyle.spot(stage);
        };
        Vfx vfx = stage.vfx(at);
        stage.style.impact(stage, vfx, at);
        long bounded = Math.min(5_000L, Math.max(1_000L, skill.duration().toMillis()));
        switch (skill.type()) {
            case DASH -> {
                Vector ahead = MobAim.facing(stage.yaw());
                double reach = MobMoves.dashReach(skill);
                int strides = (int) Math.clamp(Math.ceil(reach / MobMoves.DASH_STRIDE), 1, 12);
                boolean struck = false;
                for (int stride = 0; stride < strides; stride++) {
                    Location step = spot.clone().add(ahead.clone().multiply(stride * MobMoves.DASH_STRIDE));
                    afterimage(stage, vfx, stride * 100L, step);
                    if (!struck && step.distanceSquared(aim) < 2.5 * 2.5) {
                        struck = true;
                        vfx.particle(stride * 100L, Particle.CRIT, aim.clone().add(0, 1.2, 0), 12, 0.3, 0.4, 0.3, 0.3, null);
                        Shapes.sound(vfx, stride * 100L, "ENTITY_GOAT_RAM_IMPACT", 1.0f, 1.0f);
                    }
                }
                Location end = spot.clone().add(ahead.clone().multiply(reach));
                daze(stage, vfx, strides * 100L, null, end);
            }
            case CHAIN -> {
                Location from = MobStyle.hands(stage);
                Location chest = aim.clone().add(0, 1.2, 0);
                MobStyle.arc(stage, vfx, 0, from, chest, 0);
                int jumps = Math.min(3, MobMoves.jumps(skill));
                Location last = chest;
                for (int jump = 1; jump < jumps; jump++) {
                    Location next = chest.clone().add(side.clone().multiply(jump % 2 == 0 ? -2.5 : 2.5))
                            .add(MobAim.facing(stage.yaw()).multiply(jump * 0.8));
                    MobStyle.arc(stage, vfx, jump * 100L, last, next, jump);
                    last = next;
                }
            }
            case SHIELD -> {
                ward(stage, vfx, 0, null, spot, Math.min(4_000L, bounded));
                Vector towards = aim.toVector().subtract(spot.toVector()).setY(0);
                if (towards.lengthSquared() > 1.0E-4) {
                    towards.normalize();
                    double reach = MobStyle.bubble(stage);
                    Location chest = spot.clone().add(0, Stage.HEIGHT * 0.55, 0);
                    flash(vfx, 1_200, chest, towards, reach, stage.main(), false);
                    flash(vfx, 2_400, chest, towards, reach, stage.main(), skill.amount() >= 100);
                }
            }
            case ZONE -> {
                boolean riding = skill.cast().aim() == MobSkill.Aim.SELF;
                double radius = MobMoves.zoneRadius(skill);
                if (stage.style == MobStyle.BLADES || stage.style != MobStyle.MIASMA && riding) {
                    blades(stage, vfx, riding ? spot : aim, null, radius, bounded);
                } else {
                    pool(stage, vfx, riding ? spot : aim, radius, bounded);
                }
            }
            case BARRAGE -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int strikes = Math.min(6, MobMoves.strikes(skill));
                double scatter = MobMoves.scatter(skill);
                for (int strike = 0; strike < strikes; strike++) {
                    Location point = strike == 0 ? aim.clone() : MobMoves.scattered(aim, scatter, random);
                    Vfx one = Vfx.at(point).viewers(stage.viewers).lod(true);
                    strike(stage, one, strike * MobMoves.STRIKE_GAP_TICKS * 50L, point);
                    one.play(plugin);
                }
            }
            case LEAP -> {
                if (skill.radius() > 0) landing(stage, vfx, MobStyle.AIRTIME, aim, skill.radius());
            }
            default -> { }
        }
        vfx.play(plugin);
    }

    // ---------------------------------------------------------------- pieces

    /** A see-through copy of the body in its own colours, fading where it stood. */
    static void afterimage(Stage stage, Vfx vfx, long atMillis, Location at) {
        List<Material> palette = stage.palette;
        DisplayModel lower = Shapes.glowless(Shapes.glass(palette.getFirst()));
        DisplayModel upper = Shapes.glowless(Shapes.glass(palette.get(Math.min(1, palette.size() - 1))));
        double w = stage.width;
        double h = stage.height;
        vfx.display(atMillis, lower, DisplayMotion.builder().life(400).from(0, h * 0.28, 0).to(0, h * 0.28, 0)
                .scale(new double[]{w * 0.95, h * 0.5, w * 0.95}, new double[]{w * 0.1, h * 0.05, w * 0.1})
                .ease(DisplayMotion.Easing.IN).build(), at);
        vfx.display(atMillis, upper, DisplayMotion.builder().life(400).from(0, h * 0.75, 0).to(0, h * 0.75, 0)
                .scale(new double[]{w * 0.8, h * 0.42, w * 0.8}, new double[]{w * 0.08, h * 0.04, w * 0.08})
                .ease(DisplayMotion.Easing.IN).build(), at);
        vfx.particle(atMillis, Particle.CLOUD, at.clone().add(0, 0.2, 0), stage.count(2, 1), w * 0.3, 0.05, w * 0.3,
                0.01, null);
        vfx.particle(atMillis, Particle.BLOCK, at.clone().add(0, 0.1, 0), stage.count(3, 2), w * 0.3, 0.05, w * 0.3,
                0.05, stage.ground);
    }

    /** Stars going round a dazed head, and a chime. */
    static void daze(Stage stage, Vfx vfx, long atMillis, @Nullable LivingEntity caster, Location at) {
        DisplayModel star = DisplayModel.text(Text.of(MobStyle.glyph(stage, Role.CRIT, "✦")).build()).light(15);
        int stars = stage.count(3, 2);
        double reach = stage.width * 0.5 + 0.2;
        for (int piece = 0; piece < stars; piece++) {
            double start = Math.PI * 2 * piece / stars;
            DisplayMotion motion = MobStyle.orbit(reach, stage.height + 0.25, stage.height + 0.25, start, 1.5, 700,
                    0.5, 0.8);
            if (caster != null) {
                vfx.ride(atMillis, star, motion, caster);
            } else {
                vfx.display(atMillis, star, motion, at);
            }
        }
        Shapes.sound(vfx, atMillis, "BLOCK_AMETHYST_BLOCK_CHIME", 0.6f, 1.6f);
        Shapes.sound(vfx, atMillis + 250, "BLOCK_AMETHYST_BLOCK_CHIME", 0.5f, 1.4f);
    }

    /** One barrage strike on its own effect: circle, falling arrow, impact, the arrow left standing. */
    static void strike(Stage stage, Vfx vfx, long delay, Location point) {
        Telegraphs.circle(vfx, delay, point, MobMoves.STRIKE, MobMoves.STRIKE_LEAD_TICKS * 50L, stage.main());
        long fall = 380;
        long lands = delay + MobMoves.STRIKE_LEAD_TICKS * 50L;
        DisplayModel arrow = Shapes.item(Material.ARROW).billboard("VERTICAL");
        Rotation down = Rotation.around(Rotation.Axis.Z, -Math.PI * 3 / 4);
        vfx.display(lands - fall, arrow, DisplayMotion.chain(
                DisplayMotion.builder().life(fall).from(0, 14, 0).to(0, 0.35, 0).rotation(down).scale(1.1, 1.1)
                        .ease(DisplayMotion.Easing.IN).build(),
                DisplayMotion.still(350),
                DisplayMotion.builder().life(200).from(0, 0.35, 0).to(0, 0.2, 0).rotation(down).scale(1.1, 0.02)
                        .ease(DisplayMotion.Easing.IN).build()), point);
        Location impact = point.clone().add(0, 0.3, 0);
        vfx.particle(lands, Particle.CRIT, impact, 10, 0.25, 0.2, 0.25, 0.3, null)
                .particle(lands, Particle.BLOCK, impact, 6, 0.3, 0.05, 0.3, 0.05, MobStyle.groundOf(point));
        Shapes.sound(vfx, lands - fall, point, "ENTITY_ARROW_SHOOT", 0.35f, 1.5f);
        Shapes.sound(vfx, lands, point, "ENTITY_ARROW_HIT", 1.0f, 1.1f);
    }

    /**
     * Blades round the mob: they turn together, hold the same beat to the end,
     * and fly off outwards as the zone closes.
     */
    static void blades(Stage stage, Vfx vfx, Location centre, @Nullable LivingEntity rider, double radius,
                               long millis) {
        long cycle = 600;
        int turns = (int) Math.max(1, Math.round(millis / (double) cycle));
        long life = turns * cycle;
        int blades = stage.count(6, 4);
        double orbit = radius * 0.85;
        double y = stage.height * 0.45;
        DisplayModel sword = Shapes.item(Material.NETHERITE_SWORD);
        int poses = 12;
        for (int blade = 0; blade < blades; blade++) {
            double start = Math.PI * 2 * blade / blades;
            List<DisplayKeyframe> frames = new ArrayList<>(poses + 1);
            for (int pose = 0; pose <= poses; pose++) {
                double angle = start + Math.PI * 2 * pose / poses;
                Rotation turn = MobStyle.blade(angle);
                frames.add(new DisplayKeyframe(cycle * pose / poses, (float) (Math.cos(angle) * orbit), (float) y,
                        (float) (Math.sin(angle) * orbit), turn, 1.1f, 1.1f, 1.1f));
            }
            DisplayMotion spin = DisplayMotion.of(frames, cycle);
            if (life > cycle) spin = DisplayMotion.chain(spin, DisplayMotion.still(life - cycle)).looping(cycle, 1, 1);
            double x = Math.cos(start);
            double z = Math.sin(start);
            DisplayMotion away = DisplayMotion.builder().life(400).from(x * orbit, y, z * orbit)
                    .to(x * (orbit + 1.6), y + 0.3, z * (orbit + 1.6)).rotation(MobStyle.blade(start))
                    .scale(1.1, 0.2).ease(DisplayMotion.Easing.IN).build();
            if (rider != null) {
                vfx.ride(0, sword, spin, rider);
                vfx.ride(life, sword, away, rider);
            } else {
                vfx.display(0, sword, spin, centre);
                vfx.display(life, sword, away, centre);
            }
        }
        Shapes.sound(vfx, life, "ITEM_TRIDENT_RETURN", 0.8f, 1.2f);
        if (rider == null) {
            for (long beat = 0; beat < life; beat += 500) {
                Shapes.sound(vfx, beat, "ENTITY_PLAYER_ATTACK_SWEEP", 0.5f, 1.35f);
            }
        }
    }

    /**
     * A poison pool: its edge laid on the ground for as long as it lasts, a
     * cloud over it every half second and bubbles rising through it.
     */
    static void pool(Stage stage, Vfx vfx, Location centre, double radius, long millis) {
        int colour = stage.main();
        int plates = stage.count(Math.max(12, Math.min(28, (int) Math.round(Math.PI * 2 * radius / 0.9))), 8);
        DisplayModel edge = Shapes.glowing(Shapes.nearestGlass(colour), colour);
        double arc = Math.PI * 2 * radius / plates * 1.08;
        for (int plate = 0; plate < plates; plate++) {
            double angle = Math.PI * 2 * plate / plates;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Rotation turn = Shapes.facing(angle);
            double[] thin = {0.02, 0.02, 0.12};
            double[] full = {arc, 0.02, 0.12};
            vfx.display(0, edge, DisplayMotion.chain(
                    DisplayMotion.builder().life(200).from(x, 0.04, z).to(x, 0.04, z).rotation(turn).scale(thin, full)
                            .ease(DisplayMotion.Easing.OUT).build(),
                    DisplayMotion.still(Math.max(50L, millis - 500L)),
                    DisplayMotion.builder().life(300).from(x, 0.04, z).to(x, 0.04, z).rotation(turn).scale(full, thin)
                            .ease(DisplayMotion.Easing.IN).build()), centre);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        DisplayModel bubble = Shapes.glowless(Shapes.nearestGlass(colour));
        int each = millis <= 8_000L ? stage.count(3, 1) : 1;
        int budget = stage.lod ? 30 : 60;
        Location mist = centre.clone().add(0, 0.25, 0);
        for (long beat = 0; beat < millis; beat += 500) {
            vfx.particle(beat, Particle.DUST, mist, stage.count(18, 9), radius * 0.45, 0.12, radius * 0.45, 0,
                    Shapes.dust(colour, 1.5f));
            for (int piece = 0; piece < each && budget > 0; piece++, budget--) {
                double angle = random.nextDouble(Math.PI * 2);
                double out = Math.sqrt(random.nextDouble()) * radius * 0.8;
                double x = Math.cos(angle) * out;
                double z = Math.sin(angle) * out;
                vfx.display(beat + piece * 90L, bubble, DisplayMotion.builder().life(900).from(x, 0.15, z)
                        .to(x, random.nextDouble(1.2, 1.8), z).scale(0.18, 0.05).ease(DisplayMotion.Easing.OUT).build(),
                        centre);
            }
            if (beat % 1_500 == 0) Shapes.sound(vfx, beat, centre, "BLOCK_BREWING_STAND_BREW", 0.3f, 0.6f);
            if (beat % 1_500 == 750) {
                Shapes.sound(vfx, beat, centre, "BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT", 0.35f, 0.8f);
            }
        }
    }

    /**
     * A shield: three rings of glass plates, stacked rather than a dome, each
     * turning its own way; they rise out of the ground, and at the end are
     * thrown off and break.
     */
    static void ward(Stage stage, Vfx vfx, long atMillis, @Nullable LivingEntity rider, Location at,
                             long millis) {
        int colour = stage.main();
        DisplayModel glass = Shapes.glowless(Shapes.nearestGlass(colour));
        double base = MobStyle.bubble(stage);
        double h = stage.height;
        double[] radii = {base * 1.0, base * 0.85, base * 0.55};
        double[] heights = {h * 0.2, h * 0.62, h * 0.97};
        double[] tilts = {0.25, 0.0, -0.45};
        int[] counts = {stage.count(12, 6), stage.count(10, 5), stage.count(7, 4)};
        long rise = 300;
        int loops = (int) Math.max(1, Math.round((millis - rise) / 4_000.0));
        long cycle = Math.max(1_000L, (millis - rise) / loops);
        long life = rise + loops * cycle;
        for (int ring = 0; ring < radii.length; ring++) {
            double direction = ring == 1 ? -1 : 1;
            for (int plate = 0; plate < counts[ring]; plate++) {
                double start = Math.PI * 2 * plate / counts[ring];
                double arc = Math.PI * 2 * radii[ring] / counts[ring] * 0.9;
                double[] shape = {arc, 0.35, 0.03};
                int poses = 24;
                List<DisplayKeyframe> frames = new ArrayList<>(poses + 2);
                frames.add(new DisplayKeyframe(0, (float) (Math.cos(start) * radii[ring] * 0.6), -0.3f,
                        (float) (Math.sin(start) * radii[ring] * 0.6), Shapes.outward(start, tilts[ring]),
                        (float) (shape[0] * 0.2), 0.05f, 0.03f));
                for (int pose = 0; pose <= poses; pose++) {
                    double angle = start + direction * Math.PI * 2 * pose / poses;
                    frames.add(new DisplayKeyframe(rise + cycle * pose / poses,
                            (float) (Math.cos(angle) * radii[ring]), (float) heights[ring],
                            (float) (Math.sin(angle) * radii[ring]), Shapes.outward(angle, tilts[ring]),
                            (float) shape[0], (float) shape[1], (float) shape[2]));
                }
                DisplayMotion turning = DisplayMotion.of(frames, rise + cycle);
                if (loops > 1) {
                    turning = DisplayMotion.chain(turning, DisplayMotion.still(life - rise - cycle))
                            .looping(rise, rise + cycle, 1, 1);
                }
                double x = Math.cos(start);
                double z = Math.sin(start);
                DisplayMotion broken = DisplayMotion.builder().life(350)
                        .from(x * radii[ring], heights[ring], z * radii[ring])
                        .to(x * radii[ring] * 1.6, heights[ring] + 0.2, z * radii[ring] * 1.6)
                        .rotation(Shapes.outward(start, tilts[ring])).spin(0.5, 0, 0.5)
                        .scale(shape, new double[]{0.02, 0.02, 0.02}).ease(DisplayMotion.Easing.OUT).build();
                if (rider != null) {
                    vfx.ride(atMillis, glass, turning, rider);
                    vfx.ride(atMillis + life, glass, broken, rider);
                } else {
                    vfx.display(atMillis, glass, turning, at);
                    vfx.display(atMillis + life, glass, broken, at);
                }
            }
        }
        for (long beat = atMillis + 1_500; beat < atMillis + life; beat += 1_500) {
            Shapes.sound(vfx, beat, "BLOCK_AMETHYST_BLOCK_CHIME", 0.3f, 1.6f);
        }
        Shapes.sound(vfx, atMillis + life, "BLOCK_GLASS_BREAK", 0.8f, 1.4f);
        Shapes.sound(vfx, atMillis + life, "BLOCK_AMETHYST_CLUSTER_BREAK", 0.8f, 1.2f);
    }

    /** Where a hit met a shield: a pane of it lights up facing the attacker, and it rings. */
    static void flash(Vfx vfx, long atMillis, Location chest, Vector towards, double reach, int colour,
                              boolean full) {
        Location hit = chest.clone().add(towards.clone().multiply(reach));
        double angle = Math.atan2(towards.getZ(), towards.getX());
        DisplayModel pane = Shapes.glowing(Shapes.nearestGlass(colour), colour);
        vfx.display(atMillis, pane, DisplayMotion.builder().life(200).rotation(Shapes.facing(angle))
                .scale(new double[]{0.7, 0.7, 0.03}, new double[]{0.95, 0.95, 0.03})
                .ease(DisplayMotion.Easing.OUT).build(), hit);
        vfx.particle(atMillis, Particle.END_ROD, hit, 6, 0.15, 0.15, 0.15, 0.05, null)
                .particle(atMillis, Particle.ENCHANTED_HIT, hit, 4, 0.2, 0.2, 0.2, 0.2, null);
        Shapes.sound(vfx, atMillis, hit, "BLOCK_AMETHYST_BLOCK_HIT", 1.0f, 1.2f);
        if (full) Shapes.sound(vfx, atMillis, hit, "ITEM_SHIELD_BLOCK", 0.8f, 1.2f);
    }

    /** A leap's landing: a small ring of broken ground, a thud and a jolt. */
    static void landing(Stage stage, Vfx vfx, long atMillis, Location at, double radius) {
        double r = Math.clamp(radius, 0.8, 8);
        MobStyle.quake(stage, vfx, atMillis, r, new double[]{0.55, 1.0}, 10);
        vfx.particle(atMillis, Particle.POOF, at.clone().add(0, 0.2, 0), stage.count(10, 5), r * 0.3, 0.05, r * 0.3,
                0.04, null);
        Shapes.sound(vfx, atMillis, at, "ENTITY_PLAYER_BIG_FALL", 1.0f, 0.8f);
        Shapes.sound(vfx, atMillis, at, "ENTITY_GENERIC_EXPLODE", 0.4f, 1.4f);
        int beats = stage.shakes(1);
        if (beats > 0) vfx.shake(atMillis, r + 5, beats, 90L);
    }

    // --------------------------------------------------------------- helpers

    /** The least time between two shield flashes on one mob. */
    static final long WARD_GAP = 150L;

    private int colour(Role role) {
        int colour = Shapes.colour(theme.of(role));
        return colour >= 0 ? colour : Shapes.colour(MobTheme.DEFAULT.of(role));
    }

    /** The outline colour nearest a colour, as the team name the engine keeps. */
    static String colourName(int rgb) {
        NamedTextColor named = NamedTextColor.nearestTo(TextColor.color(rgb & 0xFFFFFF));
        String name = NamedTextColor.NAMES.key(named);
        return name == null ? "red" : name;
    }

    /**
     * A potion line's own colour, so a puff of speed looks like speed.
     *
     * @return the colour, or {@code fallback} when the line names nothing the server knows
     */
    @SuppressWarnings("deprecation")
    static int potionColour(String line, int fallback) {
        try {
            Effects.ParsedEffect parsed = Effects.parse(line);
            PotionEffectType type = parsed == null ? null : PotionEffectType.getByName(parsed.name());
            return type == null ? fallback : type.getColor().asRGB();
        } catch (RuntimeException | LinkageError noServer) {
            return fallback;
        }
    }

    /**
     * Scales a body by {@code delta} for {@code ticks}: a modifier, so it rides
     * on top of a SIZE skill, and taken off by key, so two never stack.
     */
    static void pulse(Plugin plugin, LivingEntity entity, double delta, long ticks) {
        AttributeInstance scale = MobEngine.instance(entity, "scale");
        if (scale == null || !entity.isValid()) return;
        unpulse(scale);
        scale.addModifier(new AttributeModifier(PULSE, delta, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        Tasks.of(plugin).runAtEntityLater(entity, Math.max(1L, ticks), () -> unpulse(scale));
    }

    private static void unpulse(AttributeInstance scale) {
        for (AttributeModifier modifier : List.copyOf(scale.getModifiers())) {
            if (PULSE.equals(modifier.getKey())) scale.removeModifier(modifier);
        }
    }

    /** The top of the ground under a spot, when this thread may look; else the height it was given. */
    private double ground(Location spot, double fallback) {
        if (!tasks.isOwnedBy(spot)) return fallback;
        try {
            for (int dy = 1; dy >= -4; dy--) {
                Location probe = spot.clone();
                probe.setY(Math.floor(fallback) + dy);
                if (probe.getBlock().getType().isSolid()) return probe.getBlockY() + 1.0;
            }
        } catch (RuntimeException | LinkageError unreadable) {
            return fallback;
        }
        return fallback;
    }
}
