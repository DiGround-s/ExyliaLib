package net.exylia.lib.util.mob.internal;

import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Displays;
import net.exylia.lib.display.Indicators;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.display.Telegraphs;
import net.exylia.lib.display.Vfx;
import net.exylia.lib.ragdoll.RagdollModel;
import net.exylia.lib.ragdoll.RagdollMotion;
import net.exylia.lib.ragdoll.RagdollSkin;
import net.exylia.lib.ragdoll.Ragdolls;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.mob.MobLook;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobTemplate;
import net.exylia.lib.util.mob.MobVisuals;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

/**
 * How a mob reacts to what happens to it: how it enters, takes a hit, heals,
 * dies and looks when it is nearly done.
 *
 * <p>Every reaction is a visual and nothing else. It runs on the mob's own
 * thread, draws with packets through {@link Vfx}, and gives up rather than
 * waiting: nobody within sight, reactions switched off, or the plugin already
 * playing {@link MobVisuals#maxCasts()} large effects, and nothing is built.
 * A mob's health, drops, experience and position never depend on one; the one
 * exception is a {@code drop} entrance, which starts the mob six blocks up.
 *
 * <p>What it costs: a hit draws at most once every {@link #HURT_GAP} ms per mob
 * and healing every {@link #HEAL_GAP}; above {@link Vfx#LOD_VIEWERS} viewers
 * every burst halves. An entrance is about twenty displays, a shattering death
 * up to twenty and a ragdoll nineteen.
 */
final class MobReactions {

    /** The least time between two hurt bursts on one mob: four ticks. */
    static final long HURT_GAP = 200L;

    /** The least time between two heal bursts on one mob: ten ticks. */
    static final long HEAL_GAP = 500L;

    /** Below this share of its health, or hits, a mob with no LOW_HEALTH skill looks nearly done. */
    static final double LOW = 0.25;

    /** A hit taking at least this share of its health also knocks chips off its body. */
    static final double HEAVY_HIT = 0.15;

    /** Healing below this share of its health is regeneration ticking, not worth a burst. */
    static final double HEAL_SHARE = 0.05;

    /** How far a hit, a heal or a nearly-done mob is drawn: the fight, not the valley. */
    static final double NEAR = 32;

    /** How far a heartbeat is heard. */
    static final double HEARTBEAT = 12;

    /** How long an entrance keeps the mob hidden, in ticks. */
    static final long REVEAL_TICKS = 12L;

    /** How high a {@code drop} entrance starts, in blocks. */
    static final int DROP_HEIGHT = 6;

    /** The squash of a pop, on the scale attribute. */
    private static final NamespacedKey POP = new NamespacedKey("exylialib", "mob_pop");

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final DoubleSupplier effectRadius;
    private volatile MobVisuals visuals = MobVisuals.DEFAULT;

    /** When each large effect under way ends; its size is what {@link MobVisuals#maxCasts} caps. */
    private final List<Long> playing = new ArrayList<>();

    private final Map<Material, DisplayModel> blocks = new ConcurrentHashMap<>();
    private volatile @Nullable List<DisplayModel> candies;

    MobReactions(@NotNull Plugin plugin, @NotNull TaskScheduler tasks, @NotNull DoubleSupplier effectRadius) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.effectRadius = effectRadius;
    }

    void visuals(@NotNull MobVisuals visuals) {
        this.visuals = visuals;
        Indicators.of(plugin).range(visuals.indicatorRange());
    }

    @NotNull MobVisuals visuals() {
        return visuals;
    }

    // ------------------------------------------------------------ what AUTO is

    /** How a mob enters: its look's choice, else rise, or drop in hits mode. */
    static @NotNull String spawnOf(@NotNull MobLook look, boolean hits) {
        return pick(look.spawn(), MobLook.SPAWNS, hits ? "drop" : "rise");
    }

    /** How a hit shows: its look's choice, else pop in hits mode and spark otherwise. */
    static @NotNull String hurtOf(@NotNull MobLook look, boolean hits) {
        return pick(look.hurt(), MobLook.HURTS, hits ? "pop" : "spark");
    }

    /** How it dies: its look's choice, else a piñata in hits mode, a ragdoll for a humanoid, shattered otherwise. */
    static @NotNull String deathOf(@NotNull MobLook look, @NotNull EntityType type, boolean hits) {
        String auto = hits ? "pinata" : MobBodies.humanoid(type) ? "ragdoll" : "shatter";
        return pick(look.death(), MobLook.DEATHS, auto);
    }

    /** How it looks nearly done: its look's choice, else frantic in hits mode and wounded otherwise. */
    static @NotNull String lowOf(@NotNull MobLook look, boolean hits) {
        return pick(look.low(), MobLook.LOWS, hits ? "frantic" : "wounded");
    }

    /** A stored id, {@link MobLook#OFF}, or AUTO for blank and anything this version does not draw. */
    private static String pick(String written, List<String> known, String auto) {
        if (written.equals(MobLook.OFF) || known.contains(written)) return written;
        return auto;
    }

    /** The share of health, or hits, below which a mob looks nearly done: its lowest LOW_HEALTH threshold, else a quarter. */
    static double lowAt(@NotNull MobTemplate template) {
        double lowest = Double.NaN;
        for (MobSkill skill : template.skills()) {
            if (skill.trigger() != MobSkill.Trigger.LOW_HEALTH || skill.threshold() <= 0) continue;
            if (Double.isNaN(lowest) || skill.threshold() < lowest) lowest = skill.threshold();
        }
        return Double.isNaN(lowest) ? LOW : lowest;
    }

    // ------------------------------------------------------------------ gates

    private boolean on() {
        return visuals.reactions() && Displays.isSupported();
    }

    /**
     * Takes a slot for a large effect until {@code lengthMillis} from now, or
     * says there is none. Entrances, deaths and skill styles share the slots.
     */
    boolean claim(long lengthMillis) {
        long now = System.currentTimeMillis();
        synchronized (playing) {
            playing.removeIf(end -> end <= now);
            if (playing.size() >= visuals.maxCasts()) return false;
            playing.add(now + Math.max(50L, lengthMillis));
            return true;
        }
    }

    private static List<Player> viewers(Location at, double radius) {
        return SequenceTarget.at(at).within(radius).observers();
    }

    private double near() {
        return Math.min(NEAR, effectRadius.getAsDouble());
    }

    // ------------------------------------------------------------------ spawn

    /** As a mob appears, on its thread, in the tick it was spawned: before any client has drawn it. */
    void spawn(@NotNull LivingEntity entity, @NotNull LiveMob mob) {
        if (!on()) return;
        String style = spawnOf(mob.template().look(), mob.usesHits());
        if (style.equals(MobLook.OFF)) return;
        Location at = entity.getLocation();
        List<Player> viewers = viewers(at, effectRadius.getAsDouble());
        if (viewers.isEmpty()) return;
        Block under = at.clone().subtract(0, 0.2, 0).getBlock();
        boolean grounded = under.getType().isSolid();
        if (style.equals("drop") && !(grounded && drop(entity, mob, at, under, viewers))) style = "rise";
        if (style.equals("rise") && !grounded) style = "portal";
        switch (style) {
            case "rise" -> rise(entity, at, under.getBlockData(), viewers);
            case "portal" -> portal(entity, at, viewers);
            case "bolt" -> bolt(entity, at, viewers);
            default -> { }
        }
    }

    /** Hidden while the ground it stands on breaks open around it, then shown in a spray of it. */
    private void rise(LivingEntity entity, Location at, BlockData ground, List<Player> viewers) {
        Vfx vfx = Vfx.at(at).viewers(viewers);
        if (!claim(1100L)) return;
        tasks.runAtEntityLater(entity, REVEAL_TICKS, MobBodies.vanish(entity));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        DisplayModel chunk = DisplayModel.block(ground);
        double width = Math.max(0.6, entity.getWidth());
        int pieces = vfx.lod() ? 6 : 12;
        for (int piece = 0; piece < pieces; piece++) {
            double angle = Math.PI * 2 * piece / pieces + random.nextDouble(-0.2, 0.2);
            double reach = width * 0.55 + random.nextDouble(0.25);
            double x = Math.cos(angle) * reach;
            double z = Math.sin(angle) * reach;
            double size = random.nextDouble(0.26, 0.4);
            double top = random.nextDouble(0.3, 0.55);
            Rotation tilt = tilt(random, 0.5);
            // Out of the ground on an overshoot, a beat at the top, then thrown aside.
            DisplayMotion erupt = DisplayMotion.builder().life(320).from(x, -0.6, z).to(x, top, z)
                    .rotation(tilt).scale(size * 0.6, size).ease(DisplayMotion.Easing.BACK).build();
            DisplayMotion hold = DisplayMotion.builder().life(180).from(x, top, z).to(x, top, z)
                    .rotation(tilt).scale(size, size).build();
            DisplayMotion fall = thrown(x, top, z, x * 0.9, z * 0.9, size / 2, 420L, 14, size, tilt, random, 250L);
            vfx.display(piece * 22L, chunk, DisplayMotion.chain(erupt, hold, fall));
        }
        Location feet = at.clone().add(0, 0.1, 0);
        for (long beat = 0; beat <= 450; beat += 150) {
            vfx.particle(beat, Particle.BLOCK, feet, 8, width * 0.4, 0.05, width * 0.4, 0.05, ground);
        }
        Sound crack = ground.getSoundGroup().getBreakSound();
        vfx.sound(0, crack, 0.9f, 0.6f)
                .sound(300, crack, 1.0f, 0.8f)
                .sound(600, crack, 1.0f, 1.0f)
                .sound(600, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.8f, 0.7f)
                .particle(600, Particle.BLOCK, at.clone().add(0, 0.6, 0), 26, width * 0.4, 0.4, width * 0.4,
                        0.1, ground)
                .particle(600, Particle.POOF, feet, 6, width * 0.3, 0.05, width * 0.3, 0.02, null);
        vfx.play(plugin);
    }

    /** A ring opening upright in front of it, a pull inwards, and out it steps; a summon style's minions too. */
    void portal(LivingEntity entity, Location at, List<Player> viewers) {
        Vfx vfx = Vfx.at(at).viewers(viewers);
        if (!claim(1300L)) return;
        tasks.runAtEntityLater(entity, REVEAL_TICKS, MobBodies.vanish(entity));
        double height = Math.max(0.6, entity.getHeight());
        double radius = Math.clamp(Math.max(height, entity.getWidth()) * 0.62, 0.7, 2.2);
        Location centre = at.clone().add(0, height / 2 + 0.05, 0);
        // The ring stands across the way the mob faces, so it walks out of it.
        double yaw = Math.toRadians(at.getYaw());
        DisplayModel frame = DisplayModel.block(MobBodies.block(Material.CRYING_OBSIDIAN)).light(15);
        DisplayModel shard = DisplayModel.block(MobBodies.block(Material.PURPLE_STAINED_GLASS)).light(15);
        ring(vfx, 0, centre, frame, radius, vfx.lod() ? 8 : 14, 0.26, yaw, 18L, 820L);
        ring(vfx, 0, centre, shard, radius * 0.5, vfx.lod() ? 4 : 8, 0.18, yaw, 40L, 820L);
        for (long beat = 0; beat <= 500; beat += 100) {
            vfx.particle(beat, Particle.REVERSE_PORTAL, centre, 8, radius * 0.3, radius * 0.3, radius * 0.3, 0.02, null);
        }
        vfx.sound(0, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0f, 0.7f)
                .sound(250, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7f, 1.3f)
                .sound(600, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 0.8f)
                .particle(600, Particle.PORTAL, centre, 30, 0.3, height * 0.3, 0.3, 0.6, null)
                .sound(850, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.5f, 1.4f);
        vfx.play(plugin);
    }

    /**
     * An upright ring of plates: each grows in on an overshoot, holds, and is
     * pulled into the middle.
     *
     * @param atMillis  when the first plate starts growing
     * @param yaw       the way the ring faces, in radians; it stands across it
     * @param collapse  when, from the start of the effect, the plates are pulled in
     */
    static void ring(Vfx vfx, long atMillis, Location centre, DisplayModel model, double radius, int pieces,
                     double size, double yaw, long stagger, long collapse) {
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        Rotation upright = Rotation.around(Rotation.Axis.Y, -yaw);
        for (int piece = 0; piece < pieces; piece++) {
            double angle = Math.PI * 2 * piece / pieces;
            double across = Math.cos(angle) * radius;
            double x = rightX * across;
            double y = Math.sin(angle) * radius;
            double z = rightZ * across;
            Rotation turned = Rotation.around(Rotation.Axis.Z, angle + Math.PI / 4).then(upright);
            long start = atMillis + piece * stagger;
            DisplayMotion grow = DisplayMotion.builder().life(280).from(x * 0.6, y * 0.6, z * 0.6).to(x, y, z)
                    .rotation(turned).scale(0.02, size).ease(DisplayMotion.Easing.BACK).build();
            DisplayMotion held = DisplayMotion.builder().life(Math.max(50L, collapse - start - 280L)).from(x, y, z)
                    .to(x, y, z).rotation(turned).scale(size, size).build();
            DisplayMotion shut = DisplayMotion.builder().life(260).from(x, y, z).to(0, 0, 0)
                    .rotation(turned).scale(size, 0.01).ease(DisplayMotion.Easing.IN).build();
            vfx.display(start, model, DisplayMotion.chain(grow, held, shut), centre);
        }
    }

    /**
     * Starts it {@link #DROP_HEIGHT} blocks up with slow falling over a warning
     * ring, and lands it with a thud and a kick of the ground.
     *
     * @return whether it went up; not when something is in the way
     */
    private boolean drop(LivingEntity entity, LiveMob mob, Location at, Block under, List<Player> viewers) {
        int clearance = DROP_HEIGHT + (int) Math.ceil(entity.getHeight());
        for (int up = 1; up <= clearance; up++) {
            if (!at.clone().add(0, up, 0).getBlock().isPassable()) return false;
        }
        if (!claim(1800L)) return true;
        BlockData ground = under.getBlockData();
        int tint = colour(mob.usesHits() ? "accent" : "warning", 0xFF9500);
        double radius = Math.max(0.9, entity.getWidth() * 1.2);
        Vfx warning = Vfx.at(at).viewers(viewers);
        Telegraphs.circle(warning, 0, at.clone().add(0, 0.02, 0), radius, 1600L, tint);
        warning.play(plugin);
        MobEngine.move(entity, at.clone().add(0, DROP_HEIGHT, 0));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 30, 0, false, false, false));
        AtomicInteger polls = new AtomicInteger();
        tasks.runAtEntityTimer(entity, 4L, 2L, handle -> {
            if (polls.incrementAndGet() > 40 || !entity.isValid()) {
                handle.cancel();
                return;
            }
            Location now = entity.getLocation();
            if (!entity.isOnGround()) {
                if (polls.get() % 2 == 0) {
                    now.getWorld().spawnParticle(Particle.CLOUD, now.clone().add(0, 0.2, 0), 2, 0.2, 0.1, 0.2, 0.01);
                }
                return;
            }
            handle.cancel();
            land(entity, mob, now, ground, tint);
        });
        return true;
    }

    /** The drop's landing: a kick of the ground, a puff, a thud, and confetti for a piñata. */
    private void land(LivingEntity entity, LiveMob mob, Location at, BlockData ground, int tint) {
        Vfx vfx = Vfx.at(at).viewers(viewers(at, effectRadius.getAsDouble()));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        DisplayModel chunk = DisplayModel.block(ground);
        double width = Math.max(0.6, entity.getWidth());
        int pieces = vfx.lod() ? 5 : 10;
        for (int piece = 0; piece < pieces; piece++) {
            double angle = Math.PI * 2 * piece / pieces + random.nextDouble(-0.25, 0.25);
            double x = Math.cos(angle) * width * 0.5;
            double z = Math.sin(angle) * width * 0.5;
            double size = random.nextDouble(0.18, 0.3);
            double out = random.nextDouble(0.8, 1.5);
            vfx.display(0, chunk, thrown(x, 0.1, z, x * out * 2, z * out * 2, size / 2,
                    random.nextLong(380, 520), 16, size, tilt(random, 0.6), random, 220L));
        }
        Location feet = at.clone().add(0, 0.1, 0);
        vfx.particle(0, Particle.BLOCK, feet, 20, width * 0.5, 0.05, width * 0.5, 0.15, ground)
                .particle(0, Particle.POOF, feet, 10, width * 0.5, 0.05, width * 0.5, 0.03, null)
                .sound(0, Sound.ENTITY_PLAYER_BIG_FALL, 1.0f, 0.8f)
                .sound(0, ground.getSoundGroup().getBreakSound(), 1.0f, 0.7f);
        if (mob.usesHits()) confetti(vfx, 0, at.clone().add(0, entity.getHeight() * 0.6, 0), width, 8);
        int beats = visuals.shakes(1);
        if (beats > 0) vfx.shake(0, 6, beats, 100L);
        vfx.play(plugin);
    }

    /** Struck down from the sky: a jagged bolt, a flash and a crack, and it stands where it hit. */
    private void bolt(LivingEntity entity, Location at, List<Player> viewers) {
        Vfx vfx = Vfx.at(at).viewers(viewers);
        if (!claim(800L)) return;
        tasks.runAtEntityLater(entity, 6L, MobBodies.vanish(entity));
        int shock = 0xBFE9FF;
        DisplayModel core = DisplayModel.block(MobBodies.block(Material.WHITE_CONCRETE)).glow(shock).light(15);
        DisplayModel halo = DisplayModel.block(MobBodies.block(Material.LIGHT_BLUE_STAINED_GLASS)).light(15);
        List<Location> path = jagged(at, 16, 4, 0.9);
        for (int segment = 0; segment + 1 < path.size(); segment++) {
            vfx.beam(300, path.get(segment), path.get(segment + 1), core, 0.12, 220L);
            if (!vfx.lod()) vfx.beam(300, path.get(segment), path.get(segment + 1), halo, 0.36, 160L);
        }
        List<Location> flicker = jagged(at, 12, 3, 0.7);
        for (int segment = 0; segment + 1 < flicker.size(); segment++) {
            vfx.beam(420, flicker.get(segment), flicker.get(segment + 1), core, 0.08, 120L);
        }
        Location feet = at.clone().add(0, 0.1, 0);
        vfx.particle(0, Particle.ELECTRIC_SPARK, feet, 12, 0.4, 0.05, 0.4, 0.05, null)
                .sound(0, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.8f)
                .sound(300, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0f, 1.1f)
                .sound(300, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.35f, 1.7f)
                .particle(300, Particle.END_ROD, at.clone().add(0, 1, 0), 18, 0.3, 0.6, 0.3, 0.15, null)
                .particle(300, Particle.ELECTRIC_SPARK, feet, 30, 0.8, 0.1, 0.8, 0.3, null)
                .particle(320, Particle.LARGE_SMOKE, feet, 8, 0.4, 0.05, 0.4, 0.02, null);
        int beats = visuals.shakes(1);
        if (beats > 0) vfx.shake(300, 8, beats, 100L);
        vfx.play(plugin);
    }

    /** Points from high above down to the ground, knocked sideways less and less as they come down. */
    private static List<Location> jagged(Location at, double height, int segments, double swing) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Location> points = new ArrayList<>(segments + 1);
        for (int point = 0; point <= segments; point++) {
            double down = (double) point / segments;
            double sway = point == 0 || point == segments ? 0 : swing * (1 - down * 0.5);
            points.add(at.clone().add(random.nextDouble(-sway, sway + 1.0E-9), height * (1 - down),
                    random.nextDouble(-sway, sway + 1.0E-9)));
        }
        return points;
    }

    // ------------------------------------------------------------------- hurt

    /**
     * A hit that the mob took in health mode, the killing one included.
     *
     * @param dealt   what it lost, already capped at what it had
     * @param crit    a critical hit, drawn louder
     * @param damager what dealt it, for the side the chips fly off
     * @param lethal  the hit that killed it: only its number shows, the death draws the rest
     */
    void hurt(@NotNull LivingEntity entity, @NotNull LiveMob mob, double dealt, boolean crit,
              @Nullable Entity damager, boolean lethal) {
        if (!on() || !(dealt > 0)) return;
        MobLook look = mob.template().look();
        if (look.numbers()) Indicators.of(plugin).damage(entity, dealt, crit);
        String style = hurtOf(look, false);
        if (lethal || style.equals(MobLook.OFF) || !mob.hurtShown(System.currentTimeMillis(), HURT_GAP)) return;
        if (style.equals("pop")) {
            pop(entity, mob, damager);
            return;
        }
        Location at = entity.getLocation();
        List<Player> viewers = viewers(at, near());
        if (viewers.isEmpty()) return;
        Vfx vfx = Vfx.at(at).viewers(viewers);
        double width = entity.getWidth();
        double height = entity.getHeight();
        Location centre = at.clone().add(0, height * 0.55, 0);
        vfx.particle(0, Particle.CRIT, centre, vfx.lod() ? 4 : 8, width * 0.3, height * 0.25, width * 0.3, 0.25, null);
        if (crit) vfx.particle(0, Particle.ENCHANTED_HIT, centre, 6, width * 0.3, height * 0.2, width * 0.3, 0.3, null);
        if (dealt >= MobEngine.maxHealth(entity) * HEAVY_HIT) {
            List<Material> palette = MobBodies.palette(mob.template().type(), mob.variantShown(), mob.bodyShown());
            Vector away = away(at, damager);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int chips = vfx.lod() ? 2 : 3;
            double size = Math.clamp(Math.min(width, height) * 0.2, 0.12, 0.24);
            for (int chip = 0; chip < chips; chip++) {
                Vector out = away.clone().rotateAroundY(random.nextDouble(-0.9, 0.9))
                        .multiply(random.nextDouble(0.6, 1.2));
                double y = height * random.nextDouble(0.4, 0.75);
                vfx.display(0, block(palette.get(random.nextInt(palette.size()))),
                        thrown(0, y, 0, out.getX(), out.getZ(), size / 2, 500L, 14, size, tilt(random, 1), random,
                                200L));
            }
        }
        vfx.play(plugin);
    }

    /**
     * A counted hit in hits mode: the hits left float up, the mob squashes for a
     * moment and candies and confetti fly off it.
     *
     * @param left the hits it has left
     */
    void hit(@NotNull LivingEntity entity, @NotNull LiveMob mob, @NotNull Player player, int left) {
        if (!on()) return;
        MobLook look = mob.template().look();
        if (look.numbers()) {
            Indicators.of(plugin).text(entity, Text.of("{highlight}✦ " + left).build());
        }
        String style = hurtOf(look, true);
        if (style.equals(MobLook.OFF) || !mob.hurtShown(System.currentTimeMillis(), HURT_GAP)) return;
        if (style.equals("pop")) {
            pop(entity, mob, player);
        } else {
            Location at = entity.getLocation();
            List<Player> viewers = viewers(at, near());
            if (viewers.isEmpty()) return;
            Vfx.at(at).viewers(viewers).particle(0, Particle.CRIT, at.clone().add(0, entity.getHeight() * 0.55, 0),
                    8, entity.getWidth() * 0.3, entity.getHeight() * 0.25, entity.getWidth() * 0.3, 0.25, null)
                    .play(plugin);
        }
    }

    /** A squash, candies spilling off the far side and a pinch of confetti, with a soft pop. */
    private void pop(LivingEntity entity, LiveMob mob, @Nullable Entity hitter) {
        Location at = entity.getLocation();
        List<Player> viewers = viewers(at, near());
        if (viewers.isEmpty()) return;
        squash(entity);
        Vfx vfx = Vfx.at(at).viewers(viewers);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double width = Math.max(0.6, entity.getWidth());
        double height = Math.max(0.6, entity.getHeight());
        Vector away = away(at, hitter);
        List<DisplayModel> sweets = candies();
        int count = vfx.lod() ? 2 : 4;
        for (int candy = 0; candy < count; candy++) {
            Vector out = away.clone().rotateAroundY(random.nextDouble(-1.0, 1.0)).multiply(random.nextDouble(0.8, 1.6));
            vfx.display(candy * 30L, sweets.get(random.nextInt(sweets.size())),
                    thrown(0, height * 0.6, 0, out.getX(), out.getZ(), 0.12, random.nextLong(620, 780), 12, 0.35,
                            Rotation.NONE, random, 250L));
        }
        confetti(vfx, 0, at.clone().add(0, height * 0.6, 0), width, vfx.lod() ? 3 : 5);
        vfx.sound(0, Sound.ENTITY_CHICKEN_EGG, 0.7f, (float) random.nextDouble(1.2, 1.6))
                .sound(60, Sound.ENTITY_ITEM_PICKUP, 0.35f, 1.8f);
        vfx.play(plugin);
    }

    /** Scaled up an eighth for three ticks: a modifier, so it rides on top of a SIZE skill. */
    private void squash(LivingEntity entity) {
        AttributeInstance scale = MobEngine.instance(entity, "scale");
        if (scale == null || scale.getModifiers().stream().anyMatch(modifier -> POP.equals(modifier.getKey()))) {
            return;
        }
        scale.addModifier(new AttributeModifier(POP, 0.12, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        tasks.runAtEntityLater(entity, 3L, () -> {
            for (AttributeModifier modifier : List.copyOf(scale.getModifiers())) {
                if (POP.equals(modifier.getKey())) scale.removeModifier(modifier);
            }
        });
    }

    // ------------------------------------------------------------------- heal

    /**
     * Health it got back: its number, and three hearts rising around it.
     *
     * @param amount what it regained, already capped at what it was missing
     */
    void heal(@NotNull LivingEntity entity, @NotNull LiveMob mob, double amount) {
        if (!on() || mob.usesHits() || amount < MobEngine.maxHealth(entity) * HEAL_SHARE) return;
        if (!mob.healShown(System.currentTimeMillis(), HEAL_GAP)) return;
        if (mob.template().look().numbers()) Indicators.of(plugin).heal(entity, amount);
        Location at = entity.getLocation();
        List<Player> viewers = viewers(at, near());
        if (viewers.isEmpty()) return;
        Vfx vfx = Vfx.at(at).viewers(viewers);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double height = entity.getHeight();
        double reach = Math.max(0.45, entity.getWidth() * 0.7);
        DisplayModel heart = DisplayModel.text(Text.of("{success}❤").build()).light(15);
        double turn = random.nextDouble(Math.PI * 2);
        for (int piece = 0; piece < 3; piece++) {
            double angle = turn + piece * Math.PI * 2 / 3;
            double x = Math.cos(angle) * reach;
            double z = Math.sin(angle) * reach;
            DisplayMotion appear = DisplayMotion.builder().life(120).from(x * 0.6, height * 0.4, z * 0.6)
                    .to(x * 0.8, height * 0.5, z * 0.8).scale(0.3, 1.1).ease(DisplayMotion.Easing.BACK).build();
            DisplayMotion rise = DisplayMotion.builder().life(980).from(x * 0.8, height * 0.5, z * 0.8)
                    .to(x, height + 0.6, z).scale(1.1, 0.2).ease(DisplayMotion.Easing.OUT).build();
            vfx.display(piece * 110L, heart, DisplayMotion.chain(appear, rise));
        }
        vfx.particle(0, Particle.HAPPY_VILLAGER, at.clone().add(0, height * 0.5, 0), 6, reach * 0.6, height * 0.3,
                reach * 0.6, 0, null);
        vfx.play(plugin);
    }

    // ------------------------------------------------------------------ death

    /**
     * How it leaves, drawn where it stood.
     *
     * @param killer who finished it, whom a ragdoll flies away from
     * @return whether a drawn body took the real one's place, which the caller
     *         then takes away; never when nothing was drawn
     */
    boolean death(@NotNull LivingEntity entity, @NotNull LiveMob mob, @Nullable Player killer) {
        if (!on()) return false;
        String style = deathOf(mob.template().look(), mob.template().type(), mob.usesHits());
        if (style.equals(MobLook.OFF)) return false;
        Location at = entity.getLocation();
        List<Player> viewers = viewers(at, effectRadius.getAsDouble());
        if (viewers.isEmpty()) return false;
        boolean pinata = style.equals("pinata");
        if (!claim(pinata ? 2100L : 2000L)) return false;
        // A ragdoll the server cannot draw shatters instead.
        if (style.equals("ragdoll") && ragdoll(entity, mob, at, killer, viewers)) return true;
        Vfx vfx = Vfx.at(at).viewers(viewers);
        shatter(vfx, entity, mob, at);
        if (pinata) burst(vfx, entity, at);
        vfx.play(plugin);
        return true;
    }

    /** A humanoid thrown apart in its own colours, away from whoever killed it, carrying what it held. */
    private boolean ragdoll(LivingEntity entity, LiveMob mob, Location at, @Nullable Player killer,
                            List<Player> viewers) {
        RagdollSkin skin = MobBodies.skin(mob.template().type());
        if (skin == null || !Ragdolls.isSupported()) return false;
        EntityEquipment equipment = entity.getEquipment();
        ItemStack helmet = equipment == null ? null : worn(equipment.getHelmet());
        boolean ownHead = helmet != null && MobBodies.isHead(helmet.getType());
        Material head = MobBodies.head(mob.template().type());
        RagdollModel model = RagdollModel.of(skin, ownHead ? helmet : head == null ? null : new ItemStack(head))
                .detail(viewers.size() > Vfx.LOD_VIEWERS ? 1 : 2)
                .light(15)
                .scale(scale(entity))
                .carrying(equipment == null ? null : worn(equipment.getItemInMainHand()),
                        equipment == null ? null : worn(equipment.getItemInOffHand()), ownHead ? null : helmet);
        Location body = at.clone();
        if (killer != null && killer.getWorld().equals(at.getWorld())) {
            Vector towards = killer.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (towards.lengthSquared() > 1.0E-4) body.setDirection(towards);
        }
        RagdollMotion motion = RagdollMotion.builder().life(2.0).intactFor(0.1).speed(3.0).up(5.8).spread(0.4)
                .gravity(26).bounce(0.3).spin(1.6).build();
        Ragdolls.of(plugin).show(model, motion, body, viewers);
        Vfx.at(at).viewers(viewers)
                .particle(0, Particle.POOF, at.clone().add(0, entity.getHeight() * 0.5, 0), 8, 0.25, 0.4, 0.25, 0.03, null)
                .play(plugin);
        return true;
    }

    /** Blocks of its colours burst out of its whole body, arc to the ground, rest a moment and melt away. */
    private void shatter(Vfx vfx, LivingEntity entity, LiveMob mob, Location at) {
        List<Material> palette = MobBodies.palette(mob.template().type(), mob.variantShown(), mob.bodyShown());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double width = Math.max(0.3, entity.getWidth());
        double height = Math.max(0.3, entity.getHeight());
        int pieces = (int) Math.clamp(14 + Math.round(width * height * 2), 14, 20);
        if (vfx.lod()) pieces /= 2;
        double size = Math.clamp(Math.min(width, height) * 0.3, 0.12, 0.5);
        for (int piece = 0; piece < pieces; piece++) {
            double x = random.nextDouble(-width, width) * 0.4;
            double z = random.nextDouble(-width, width) * 0.4;
            double y = height * random.nextDouble(0.1, 0.9);
            double each = size * random.nextDouble(0.75, 1.25);
            Vector out = new Vector(x, 0, z);
            if (out.lengthSquared() < 1.0E-4) out = new Vector(random.nextDouble(-1, 1), 0, random.nextDouble(-1, 1));
            out.normalize().multiply(random.nextDouble(0.6, 1.6) + width * 0.5);
            // Most of it the first colour, so a sheep reads as its wool and not as confetti.
            Material material = random.nextDouble() < 0.45 ? palette.getFirst()
                    : palette.get(random.nextInt(palette.size()));
            vfx.display(0, block(material), thrown(x, y, z, out.getX(), out.getZ(), each / 2,
                    random.nextLong(750, 950), 18, each, tilt(random, 1), random, 550L));
        }
        Material first = palette.getFirst();
        Location centre = at.clone().add(0, height * 0.5, 0);
        vfx.particle(0, Particle.POOF, centre, 10, width * 0.4, height * 0.3, width * 0.4, 0.04, null)
                .particle(0, Particle.BLOCK, centre, 16, width * 0.4, height * 0.3, width * 0.4, 0.1,
                        MobBodies.block(first))
                .sound(0, MobBodies.block(first).getSoundGroup().getBreakSound(), 1.0f, 0.85f);
    }

    /** The piñata's own finish: a fountain of candies, confetti, fireworks and a jolt. */
    private void burst(Vfx vfx, LivingEntity entity, Location at) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double width = Math.max(0.6, entity.getWidth());
        double height = Math.max(0.6, entity.getHeight());
        List<DisplayModel> sweets = candies();
        int count = vfx.lod() ? 10 : 20;
        for (int candy = 0; candy < count; candy++) {
            double angle = random.nextDouble(Math.PI * 2);
            double reach = random.nextDouble(1.2, 3.0);
            vfx.display(random.nextLong(0, 120), sweets.get(random.nextInt(sweets.size())),
                    thrown(0, height * 0.6, 0, Math.cos(angle) * reach, Math.sin(angle) * reach, 0.12,
                            random.nextLong(1000, 1300), 16, 0.4, Rotation.NONE, random, 900L));
        }
        Location centre = at.clone().add(0, height * 0.6, 0);
        confetti(vfx, 0, centre, width * 1.5, 14);
        confetti(vfx, 150, centre.clone().add(0, 1.2, 0), width * 2, 10);
        vfx.particle(0, Particle.FIREWORK, centre, 40, 0.2, 0.2, 0.2, 0.22, null)
                .particle(250, Particle.FIREWORK, centre.clone().add(0, 1.5, 0), 25, 0.3, 0.3, 0.3, 0.15, null)
                .sound(0, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f)
                .sound(250, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.9f, 1.2f)
                .sound(450, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        int beats = visuals.shakes(1);
        if (beats > 0) vfx.shake(0, 6, beats, 100L);
    }

    /**
     * Takes the real body away once a drawn one has replaced it, after a death
     * event: invisible now, removed a tick later.
     *
     * <p>A tick later because the drops and the experience are handed out after
     * the event returns; by then they are in the world. A death another plugin
     * cancelled leaves a living mob, which is shown again as it was. Run on the
     * dying location's thread, where a body that no longer moves stays.
     */
    void hideBody(@NotNull LivingEntity entity) {
        boolean invisible = entity.isInvisible();
        entity.setInvisible(true);
        tasks.runAtLocationLater(entity.getLocation(), 1L, () -> {
            if (entity.isDead()) {
                entity.remove();
            } else {
                entity.setInvisible(invisible);
            }
        });
    }

    // -------------------------------------------------------------------- low

    /**
     * Once a second while it is nearly done: red dripping and a heartbeat every
     * other second, or a piñata's sweat and squeak.
     *
     * @param second how many seconds it has lived, which paces the heartbeat
     */
    void low(@NotNull LivingEntity entity, @NotNull LiveMob mob, int second) {
        if (!on()) return;
        String style = lowOf(mob.template().look(), mob.usesHits());
        if (style.equals(MobLook.OFF)) return;
        double share = mob.usesHits() ? mob.hitsLeft() / (double) mob.maxHits()
                : entity.getHealth() / MobEngine.maxHealth(entity);
        if (!(share > 0) || share > lowAt(mob.template())) return;
        Location at = entity.getLocation();
        List<Player> viewers = viewers(at, near());
        if (viewers.isEmpty()) return;
        Vfx vfx = Vfx.at(at).viewers(viewers);
        double width = entity.getWidth();
        double height = entity.getHeight();
        boolean beat = second % 2 == 0;
        if (style.equals("frantic")) {
            vfx.particle(0, Particle.SPLASH, at.clone().add(0, height + 0.1, 0), vfx.lod() ? 4 : 8, 0.3, 0.1, 0.3, 0.1,
                    null);
            vfx.particle(500, Particle.SPLASH, at.clone().add(0, height, 0), vfx.lod() ? 3 : 6, 0.3, 0.1, 0.3, 0.1,
                    null);
            if (beat) vfx.sound(0, Sound.ENTITY_BAT_HURT, 0.35f, 1.8f);
            vfx.play(plugin);
            return;
        }
        BlockData blood = MobBodies.block(Material.REDSTONE_BLOCK);
        vfx.particle(0, Particle.FALLING_DUST, at.clone().add(0, height * 0.6, 0), vfx.lod() ? 2 : 4,
                width * 0.3, height * 0.25, width * 0.3, 0, blood);
        vfx.particle(500, Particle.FALLING_DUST, at.clone().add(0, height * 0.5, 0), vfx.lod() ? 1 : 3,
                width * 0.3, height * 0.25, width * 0.3, 0, blood);
        vfx.play(plugin);
        if (beat) {
            List<Player> close = new ArrayList<>();
            double reach = HEARTBEAT * HEARTBEAT;
            for (Player viewer : viewers) {
                if (viewer.getLocation().distanceSquared(at) <= reach) close.add(viewer);
            }
            if (!close.isEmpty()) {
                Vfx.at(at).viewers(close)
                        .sound(0, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 0.6f)
                        .sound(160, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.55f, 0.5f)
                        .play(plugin);
            }
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * A piece flung from a point that lands, rests and melts away.
     *
     * <p>Straight out along the ground and a real arc up and down, worked out so
     * it lands exactly at {@code landY}: a chip that sinks through the floor
     * reads as a bug. The spin is whole turns, so it lands the way it left and
     * the rest does not twist.
     *
     * @param landY  the height it lands at, relative to the spawn point: half its size, to sit on the ground
     * @param rest   how long it lies there shrinking, in ms
     */
    static DisplayMotion thrown(double x, double y, double z, double dx, double dz, double landY, long life,
                                double gravity, double size, Rotation base, ThreadLocalRandom random, long rest) {
        double seconds = life / 1000.0;
        double climb = 0.5 * gravity * seconds * seconds + landY - y;
        DisplayMotion fly = DisplayMotion.builder().life(life).from(x, y, z).to(x + dx, y + climb, z + dz)
                .rotation(base).spin(turns(random), turns(random), turns(random)).scale(size, size)
                .gravity(gravity).build();
        DisplayMotion melt = DisplayMotion.builder().life(rest).from(x + dx, landY, z + dz).to(x + dx, landY, z + dz)
                .rotation(base).scale(size, 0.01).ease(DisplayMotion.Easing.IN).build();
        return DisplayMotion.chain(fly, melt);
    }

    private static int turns(ThreadLocalRandom random) {
        return random.nextInt(0, 3) * (random.nextBoolean() ? 1 : -1);
    }

    private static Rotation tilt(ThreadLocalRandom random, double most) {
        return Rotation.around(Rotation.Axis.X, random.nextDouble(-most, most))
                .then(Rotation.around(Rotation.Axis.Z, random.nextDouble(-most, most)))
                .then(Rotation.around(Rotation.Axis.Y, random.nextDouble(Math.PI * 2)));
    }

    /** Level with the ground, away from whoever hit it; any way round when nobody did. */
    private static Vector away(Location at, @Nullable Entity from) {
        Vector away = from == null || !from.getWorld().equals(at.getWorld()) ? null
                : at.toVector().subtract(from.getLocation().toVector()).setY(0);
        if (away == null || away.lengthSquared() < 1.0E-4) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            return new Vector(Math.cos(angle), 0, Math.sin(angle));
        }
        return away.normalize();
    }

    /** Dust in the palette's accent, highlight and info colours. */
    private static void confetti(Vfx vfx, long atMillis, Location centre, double spread, int each) {
        for (String token : List.of("accent", "highlight", "info")) {
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(colour(token, 0xFFFFFF)), 1.1f);
            vfx.particle(atMillis, Particle.DUST, centre, each, spread * 0.4, 0.35, spread * 0.4, 0, dust);
        }
    }

    /** A palette token's colour, read as it plays so a palette reload shows at once. */
    private static int colour(String token, int fallback) {
        TextColor colour = Colors.get(token);
        return colour == null ? fallback : colour.value();
    }

    private static double scale(LivingEntity entity) {
        AttributeInstance scale = MobEngine.instance(entity, "scale");
        double factor = scale == null ? 1 : scale.getValue();
        if (entity instanceof Ageable ageable && !ageable.isAdult()) factor *= 0.5;
        return factor;
    }

    private static @Nullable ItemStack worn(@Nullable ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private DisplayModel block(Material material) {
        return blocks.computeIfAbsent(material, key -> DisplayModel.block(MobBodies.block(key)));
    }

    /** What a piñata is full of. */
    private List<DisplayModel> candies() {
        List<DisplayModel> made = candies;
        if (made == null) {
            made = List.of(Material.COOKIE, Material.SWEET_BERRIES, Material.GLOW_BERRIES, Material.HONEYCOMB,
                            Material.GOLDEN_CARROT, Material.PUMPKIN_PIE).stream()
                    .map(material -> DisplayModel.item(new ItemStack(material)).light(15)).toList();
            candies = made;
        }
        return made;
    }
}
