package net.exylia.lib.util.mob.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.display.Telegraphs;
import net.exylia.lib.display.Vfx;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobTheme;
import net.exylia.lib.util.mob.MobTheme.Role;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How each skill style is drawn: a wind-up, which is the fair warning, and an
 * impact, which is the moment it lands.
 *
 * <p>A recipe only draws. It never moves, hurts or heals anything, and a cast
 * whose style is skipped — nobody watching, the large-effect cap reached —
 * plays exactly the same. The one exception is a crouch or a hop the caster
 * does during its own wind-up, which is part of the telegraph.
 *
 * <p>Every recipe works from a {@link Stage}: where the caster stood, where it
 * aimed, how big it is, who is watching and in which colours. A preview is a
 * stage with no caster, so nothing here may assume there is one.
 *
 * <p>The parts that happen over time — a dash's afterimages, a chain's jumps,
 * a zone, a barrage's strikes, a shield — belong to the skill's type rather
 * than its style and are drawn by {@link MobShows} as the mechanics reach them.
 */
enum MobStyle {

    SLAM("slam", Role.TELEGRAPH) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location centre = spot(s);
            double r = radius(s, 4);
            Telegraphs.circle(v, 0, centre, r, w, s.main());
            v.particle(0, Particle.BLOCK, s.origin().add(0, 0.1, 0), s.count(10, 5), s.width * 0.4, 0.05,
                    s.width * 0.4, 0.05, s.ground);
            Shapes.sound(v, 0, "ENTITY_IRON_GOLEM_ATTACK", 0.8f, 0.6f);
            if (w >= HOP_AIR + 150) {
                // A crouch, then a hop that comes back down just as the circle fills: 0.45 up is 12 ticks aloft.
                pulse(s, v, 0, -0.08, (w - HOP_AIR) / 50);
                hop(s, v, w - HOP_AIR, 0.45);
                Shapes.sound(v, w - HOP_AIR, "ENTITY_GOAT_LONG_JUMP", 0.9f, 0.7f);
            }
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 4);
            quake(s, v, 0, r, new double[]{0.3, 0.62, 0.92}, 14);
            Location centre = at.clone().add(0, 0.2, 0);
            v.particle(0, Particle.EXPLOSION, centre, 1)
                    .particle(0, Particle.POOF, centre, s.count(14, 7), r * 0.25, 0.1, r * 0.25, 0.05, null);
            Shapes.circle(v, 450, Particle.DUST_PLUME, at.clone().add(0, 0.15, 0), r * 0.9, s.count(12, 6), 0.02, null);
            Shapes.sound(v, 0, "ENTITY_GENERIC_EXPLODE", 0.8f, 0.6f);
            Shapes.sound(v, 0, "BLOCK_ANVIL_LAND", 0.5f, 0.5f);
            breakSound(s, v, 0, 1.0f, 0.6f);
            breakSound(s, v, 140, 0.8f, 0.8f);
            shake(s, v, 0, r + 8, 2);
        }
    },

    METEOR("meteor", Role.DANGER) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location centre = spot(s);
            double r = radius(s, 3);
            Telegraphs.circle(v, 0, centre, r, w, s.main());
            Telegraphs.cross(v, 0, centre, r * 1.1, w, s.colour(Role.FIRE));
            for (long beat = 0; beat < w; beat += 300) {
                Shapes.sound(v, beat, centre, "BLOCK_FIRE_AMBIENT", 0.9f, 1.0f);
            }
            Location hands = hands(s);
            Shapes.sound(v, 0, "ENTITY_EVOKER_PREPARE_ATTACK", 0.8f, 0.7f);
            v.particle(0, Particle.FLAME, hands, 6, 0.2, 0.2, 0.2, 0.02, null)
                    .particle(200, Particle.FLAME, hands, 6, 0.2, 0.2, 0.2, 0.03, null);

            long fall = Math.min(1000L, Math.max(300L, w * 4 / 5));
            long start = Math.max(0L, w - fall);
            Vector back = s.origin().toVector().subtract(centre.toVector()).setY(0);
            if (back.lengthSquared() < 0.25) back = MobAim.facing(s.yaw()).multiply(-1);
            back.normalize().multiply(6);
            double sx = back.getX();
            double sy = 18;
            double sz = back.getZ();
            DisplayModel rock = Shapes.glowing(Material.MAGMA_BLOCK, s.colour(Role.FIRE));
            v.display(start, rock, DisplayMotion.builder().life(fall).from(sx, sy, sz).to(0, 0.7, 0)
                    .scale(0.9, 1.4).spin(1, 2, 0.5).ease(DisplayMotion.Easing.IN).build(), centre);
            // The trail follows the same curve a beat behind and burns out before it lands.
            DisplayModel ember = Shapes.block(Material.NETHERRACK);
            int trail = s.count(3, 1);
            for (int piece = 1; piece <= trail; piece++) {
                v.display(start + piece * 50L, ember, DisplayMotion.builder().life(fall * 3 / 4)
                        .from(sx, sy, sz).to(sx + (0 - sx) * 0.5625, sy + (0.7 - sy) * 0.5625, sz + (0 - sz) * 0.5625)
                        .scale(0.8 - piece * 0.12, 0.15).spin(1, 1, 0).ease(DisplayMotion.Easing.IN).build(), centre);
            }
            for (int step = 0; step <= 9; step++) {
                double t = step / 9.0;
                double eased = t * t;
                Location at = centre.clone().add(sx + (0 - sx) * eased, sy + (0.7 - sy) * eased, sz + (0 - sz) * eased);
                long when = start + Math.round(fall * t);
                v.particle(when, Particle.FLAME, at, s.count(6, 3), 0.25, 0.25, 0.25, 0.02, null)
                        .particle(when, Particle.LARGE_SMOKE, at, s.count(2, 1), 0.2, 0.2, 0.2, 0.01, null);
            }
            Shapes.sound(v, start, centre, "ITEM_FIRECHARGE_USE", 1.0f, 0.5f);
            Shapes.sound(v, start + fall / 2, centre, "ENTITY_GHAST_SHOOT", 0.6f, 0.6f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 3);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int debris = s.count(10, 5);
            for (int piece = 0; piece < debris; piece++) {
                double angle = Math.PI * 2 * piece / debris + random.nextDouble(-0.3, 0.3);
                double out = random.nextDouble(1.2, 2.6);
                double size = random.nextDouble(0.25, 0.45);
                v.display(0, Shapes.block(piece % 2 == 0 ? Material.MAGMA_BLOCK : Material.BLACKSTONE),
                        Shapes.thrown(0, 0.5, 0, Math.cos(angle) * out, Math.sin(angle) * out, size,
                                random.nextLong(700, 950), random, 450L));
            }
            // What is left of the rock: flattened into the ground, glowing, then gone.
            DisplayModel splat = Shapes.glowing(Material.MAGMA_BLOCK, s.colour(Role.FIRE));
            v.display(0, splat, DisplayMotion.chain(
                    DisplayMotion.builder().life(120).from(0, 0.7, 0).to(0, 0.12, 0)
                            .scale(new double[]{1.4, 1.4, 1.4}, new double[]{2.4, 0.22, 2.4})
                            .ease(DisplayMotion.Easing.OUT).build(),
                    DisplayMotion.still(480),
                    DisplayMotion.builder().life(400).from(0, 0.12, 0).to(0, 0.02, 0)
                            .scale(new double[]{2.4, 0.22, 2.4}, new double[]{0.2, 0.04, 0.2})
                            .ease(DisplayMotion.Easing.IN).build()));
            Location centre = at.clone().add(0, 0.5, 0);
            v.particle(0, Particle.EXPLOSION, centre, 2, 0.3, 0.1, 0.3, 0, null)
                    .particle(0, Particle.LAVA, centre, s.count(10, 5), r * 0.3, 0.1, r * 0.3, 0, null)
                    .particle(100, Particle.LARGE_SMOKE, centre, s.count(16, 8), r * 0.4, 0.2, r * 0.4, 0.03, null);
            Shapes.circle(v, 0, Particle.FLAME, at.clone().add(0, 0.15, 0), r, s.count(18, 9), 0.12, null);
            Shapes.sound(v, 0, "ENTITY_GENERIC_EXPLODE", 1.0f, 0.8f);
            Shapes.sound(v, 0, "ENTITY_BLAZE_SHOOT", 1.0f, 0.5f);
            Shapes.sound(v, 200, "BLOCK_LAVA_EXTINGUISH", 0.6f, 0.8f);
            shake(s, v, 0, r + 9, 2);
        }
    },

    BLADES("blades", Role.DANGER) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            double r = radius(s, 3);
            Telegraphs.circle(v, 0, s.origin(), r, w, s.main());
            int blades = s.count(6, 4);
            double orbit = r * 0.85;
            DisplayModel sword = Shapes.item(Material.NETHERITE_SWORD);
            for (int blade = 0; blade < blades; blade++) {
                double angle = Math.PI * 2 * blade / blades;
                double x = Math.cos(angle) * orbit;
                double z = Math.sin(angle) * orbit;
                v.display(0, sword, DisplayMotion.builder().life(Math.max(50L, w))
                        .from(x * 0.4, -0.5, z * 0.4).to(x, s.height * 0.45, z)
                        .rotation(blade(angle)).scale(0.3, 1.1).ease(DisplayMotion.Easing.OUT).build(), s.origin());
            }
            Shapes.sound(v, 0, "ITEM_ARMOR_EQUIP_NETHERITE", 1.0f, 0.8f);
            Shapes.sound(v, w / 2, "BLOCK_GRINDSTONE_USE", 0.5f, 1.4f);
            Shapes.sound(v, Math.max(0, w - 100), "ITEM_TRIDENT_RIPTIDE_1", 0.8f, 1.4f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 3);
            Shapes.sound(v, 0, "ENTITY_PLAYER_ATTACK_SWEEP", 0.9f, 0.9f);
            Shapes.sound(v, 60, "ITEM_TRIDENT_RIPTIDE_2", 0.6f, 1.3f);
            v.particle(0, Particle.SWEEP_ATTACK, at.clone().add(0, s.height * 0.45, 0), s.count(6, 3),
                    r * 0.5, 0.1, r * 0.5, 0, null);
            Shapes.circle(v, 0, Particle.CRIT, at.clone().add(0, s.height * 0.45, 0), r * 0.6, s.count(16, 8), 0.5,
                    null);
            for (LivingEntity body : s.reached()) {
                v.particle(0, Particle.DAMAGE_INDICATOR, chest(body), s.count(4, 2), 0.2, 0.3, 0.2, 0.1, null);
            }
        }
    },

    CHARGE("charge", Role.DANGER) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location from = s.origin();
            double width = s.skill.cast().spread() > 0 ? s.skill.cast().spread() : Math.max(1.6, s.width * 1.6);
            Telegraphs.line(v, 0, from, dashEnd(s), width, w, s.main());
            Location heels = from.clone().subtract(MobAim.facing(s.yaw()).multiply(s.width * 0.5)).add(0, 0.1, 0);
            for (int scrape = 0; scrape < 3; scrape++) {
                v.particle(w * scrape / 3, Particle.BLOCK, heels, s.count(8, 4), s.width * 0.3, 0.05, s.width * 0.3,
                        0.1, s.ground);
            }
            Location snout = hands(s);
            Vector ahead = MobAim.facing(s.yaw());
            v.particle(w / 2, Particle.CLOUD, snout, 0, ahead.getX(), -0.1, ahead.getZ(), 0.12, null);
            Shapes.sound(v, 0, "ENTITY_GOAT_PREPARE_RAM", 1.0f, 0.8f);
            Shapes.sound(v, w / 3, "ENTITY_RAVAGER_STEP", 0.8f, 0.7f);
            Shapes.sound(v, w * 2 / 3, "ENTITY_RAVAGER_STEP", 0.9f, 0.8f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            Vector back = MobAim.facing(s.yaw()).multiply(-1);
            v.particle(0, Particle.CLOUD, at.clone().add(0, 0.2, 0), s.count(10, 5), s.width * 0.4, 0.1,
                    s.width * 0.4, 0.05, null);
            for (int puff = 0; puff < s.count(4, 2); puff++) {
                v.particle(0, Particle.CLOUD, at.clone().add(0, 0.3, 0), 0, back.getX(), 0.05, back.getZ(), 0.25, null);
            }
            // Where it digs in to stop: the ground kicked up ahead of it.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Vector ahead = back.clone().multiply(-1);
            DisplayModel dirt = DisplayModel.block(s.ground);
            int chips = s.count(6, 3);
            for (int chip = 0; chip < chips; chip++) {
                double spread = random.nextDouble(-0.7, 0.7);
                double out = random.nextDouble(0.8, 1.6);
                double dx = ahead.getX() * out - ahead.getZ() * spread;
                double dz = ahead.getZ() * out + ahead.getX() * spread;
                v.display(0, dirt, Shapes.thrown(ahead.getX() * s.width * 0.5, 0.1, ahead.getZ() * s.width * 0.5,
                        dx, dz, random.nextDouble(0.15, 0.25), random.nextLong(380, 520), random, 250L));
            }
            Shapes.sound(v, 0, "ENTITY_GOAT_LONG_JUMP", 1.0f, 0.8f);
            Shapes.sound(v, 0, "ENTITY_PLAYER_ATTACK_SWEEP", 0.8f, 0.6f);
            shake(s, v, 0, 8, 1);
        }
    },

    CHAIN("chain", Role.SHOCK) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location hands = hands(s);
            DisplayModel orb = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            v.display(0, orb, DisplayMotion.builder().life(Math.max(50L, w)).scale(0.05, 0.32).spin(2, 3, 1)
                    .ease(DisplayMotion.Easing.IN).build(), hands);
            for (long beat = 0; beat < w; beat += 100) {
                v.particle(beat, Particle.ELECTRIC_SPARK, hands, s.count(4, 2), 0.35, 0.35, 0.35, 0.02, null);
            }
            Shapes.sound(v, 0, "BLOCK_BEACON_POWER_SELECT", 0.6f, 2.0f);
            Shapes.sound(v, w / 2, "BLOCK_RESPAWN_ANCHOR_CHARGE", 0.5f, 1.8f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            Location hands = hands(s);
            v.particle(0, Particle.END_ROD, hands, s.count(6, 3), 0.1, 0.1, 0.1, 0.08, null);
            if (s.skill.type() == MobSkill.Type.CHAIN) return;
            // A lightning skill's own strike: one arc from the hands to each body it hit.
            int jump = 0;
            for (LivingEntity body : s.reached()) {
                if (jump >= 4) break;
                arc(s, v, jump * 60L, hands, chest(body), jump);
                jump++;
            }
            if (jump == 0 && s.preview) arc(s, v, 0, hands, s.point().add(0, 1.2, 0), 0);
        }
    },

    BUBBLE("bubble", Role.SHIELD) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location body = s.origin().add(0, s.height * 0.5, 0);
            for (long beat = 0; beat < w; beat += 100) {
                v.particle(beat, Particle.ENCHANT, body, s.count(8, 4), s.width, s.height * 0.4, s.width, 0.4, null);
            }
            Shapes.circle(v, 0, Particle.DUST, s.origin().add(0, 0.1, 0), bubble(s), s.count(16, 8), 0,
                    Shapes.dust(s.main(), 1.0f));
            Shapes.sound(v, 0, "BLOCK_AMETHYST_BLOCK_CHIME", 1.0f, 1.2f);
            Shapes.sound(v, w / 2, "BLOCK_AMETHYST_BLOCK_RESONATE", 0.6f, 1.5f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            shell(s, v, at, false);
            Shapes.sound(v, 0, "BLOCK_BEACON_ACTIVATE", 0.8f, 1.6f);
            Shapes.sound(v, 0, "BLOCK_AMETHYST_BLOCK_RESONATE", 1.0f, 1.2f);
            v.particle(0, Particle.END_ROD, at.clone().add(0, s.height * 0.5, 0), s.count(10, 5),
                    bubble(s) * 0.6, s.height * 0.3, bubble(s) * 0.6, 0.02, null);
        }
    },

    PORTAL("portal", Role.ARCANE) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location hands = hands(s);
            for (long beat = 0; beat < w; beat += 200) {
                v.particle(beat, Particle.WITCH, hands, s.count(4, 2), 0.25, 0.25, 0.25, 0.02, null);
            }
            Shapes.sound(v, 0, "BLOCK_END_PORTAL_FRAME_FILL", 1.0f, 0.7f);
            DisplayModel frame = Shapes.glowless(Material.CRYING_OBSIDIAN);
            DisplayModel shard = Shapes.glowless(Material.PURPLE_STAINED_GLASS);
            int index = 0;
            for (Location spot : s.spots) {
                Location centre = spot.clone().add(0, 1.0, 0);
                Vector towards = s.origin().toVector().subtract(spot.toVector()).setY(0);
                double yaw = towards.lengthSquared() < 1.0E-4 ? Math.toRadians(s.yaw())
                        : Math.atan2(-towards.getX(), towards.getZ());
                long open = Math.min(index * 120L, Math.max(0L, w - 400));
                MobReactions.ring(v, open, centre, frame, 0.9, s.count(12, 8), 0.24, yaw, 18L, w);
                MobReactions.ring(v, open, centre, shard, 0.45, s.count(6, 4), 0.16, yaw, 40L, w);
                for (long beat = open; beat < w; beat += 150) {
                    v.particle(beat, Particle.REVERSE_PORTAL, centre, s.count(6, 3), 0.3, 0.4, 0.3, 0.02, null);
                }
                Shapes.sound(v, open + 200, centre, "BLOCK_RESPAWN_ANCHOR_CHARGE", 0.6f, 1.3f);
                Shapes.sound(v, w, centre, "ENTITY_ENDERMAN_TELEPORT", 1.0f, 0.6f);
                Shapes.sound(v, w + 100, centre, "BLOCK_RESPAWN_ANCHOR_DEPLETE", 0.5f, 1.4f);
                index++;
            }
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            List<Location> through = new ArrayList<>();
            for (LivingEntity minion : s.reached()) through.add(minion.getLocation());
            if (through.isEmpty() && s.preview) through.addAll(s.spots);
            for (Location spot : through) {
                Location centre = spot.clone().add(0, 1.0, 0);
                v.particle(0, Particle.PORTAL, centre, s.count(30, 15), 0.3, 0.5, 0.3, 0.6, null)
                        .particle(0, Particle.POOF, spot.clone().add(0, 0.2, 0), s.count(6, 3), 0.3, 0.05, 0.3,
                                0.02, null);
            }
        }
    },

    MIASMA("miasma", Role.POISON) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location centre = spot(s);
            double r = radius(s, 3);
            Telegraphs.circle(v, 0, centre, r, w, s.main());
            Shapes.sound(v, 0, "ENTITY_WITCH_THROW", 0.8f, 0.8f);
            Shapes.sound(v, 0, centre, "BLOCK_BREWING_STAND_BREW", 0.6f, 0.6f);
            // A flask thrown from its hands lands on the spot as the circle fills.
            Location hands = hands(s);
            long flight = Math.min(Math.max(50L, w), 700L);
            double seconds = flight / 1000.0;
            double gravity = 14;
            double dx = hands.getX() - centre.getX();
            double dy = hands.getY() - centre.getY();
            double dz = hands.getZ() - centre.getZ();
            if (Math.hypot(dx, dz) > 0.5) {
                double climb = 0.5 * gravity * seconds * seconds + 0.3;
                v.display(Math.max(0L, w - flight), Shapes.item(Material.SPLASH_POTION), DisplayMotion.builder()
                        .life(flight).from(dx, dy, dz).to(0, climb, 0).gravity(gravity).spin(0, 0, 2)
                        .scale(0.55, 0.55).build(), centre);
            }
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 3);
            Location centre = at.clone().add(0, 0.3, 0);
            v.particle(0, Particle.DUST, centre, s.count(30, 15), r * 0.4, 0.3, r * 0.4, 0,
                    Shapes.dust(s.main(), 1.6f));
            v.particle(0, Particle.ITEM_SLIME, centre, s.count(10, 5), r * 0.3, 0.2, r * 0.3, 0.05, null);
            Shapes.sound(v, 0, "ENTITY_SPLASH_POTION_BREAK", 1.0f, 0.7f);
        }
    },

    NOVA("nova", Role.FROST) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location centre = spot(s);
            double r = radius(s, 5);
            Telegraphs.ring(v, 0, centre, r, w, s.main());
            int points = s.count(10, 5);
            for (long beat = 0; beat < w; beat += 100) {
                for (int point = 0; point < points; point++) {
                    double angle = Math.PI * 2 * point / points + beat * 0.002;
                    double x = Math.cos(angle);
                    double z = Math.sin(angle);
                    v.particle(beat, Particle.SNOWFLAKE, centre.clone().add(x * r, 0.3, z * r), 0, -x, 0.02, -z,
                            r / 8, null);
                }
            }
            Shapes.sound(v, 0, "ITEM_BUCKET_FILL_POWDER_SNOW", 0.8f, 0.8f);
            Shapes.sound(v, w * 3 / 5, "BLOCK_AMETHYST_BLOCK_CHIME", 0.8f, 0.5f);
            Shapes.sound(v, Math.max(0, w - 120), "ENTITY_PLAYER_HURT_FREEZE", 0.5f, 1.4f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 5);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            spikes(v, r * 0.5, s.count(6, 3), 0, random);
            spikes(v, r * 0.88, s.count(10, 5), 70, random);
            Location centre = at.clone().add(0, 0.3, 0);
            v.particle(0, Particle.SNOWFLAKE, centre, s.count(40, 20), r * 0.4, 0.3, r * 0.4, 0.1, null)
                    .particle(0, Particle.BLOCK, centre, s.count(16, 8), r * 0.5, 0.1, r * 0.5, 0.1,
                            MobBodies.block(Material.PACKED_ICE));
            Shapes.sound(v, 0, "BLOCK_GLASS_BREAK", 1.0f, 0.7f);
            Shapes.sound(v, 0, "ENTITY_PLAYER_HURT_FREEZE", 0.8f, 1.0f);
            Shapes.sound(v, 60, "BLOCK_AMETHYST_CLUSTER_BREAK", 0.8f, 0.6f);
            Shapes.sound(v, 2000, "BLOCK_GLASS_BREAK", 0.5f, 1.2f);
            shake(s, v, 0, r + 4, 1);
            long frozen = Math.clamp(potionMillis(s.skill.text()), 1000L, 6000L);
            int count = 0;
            for (LivingEntity body : s.reached()) {
                if (count++ >= 8) break;
                encase(s, v, body, frozen);
            }
        }

        private void spikes(Vfx v, double radius, int count, long delay, ThreadLocalRandom random) {
            for (int spike = 0; spike < count; spike++) {
                double angle = Math.PI * 2 * spike / count + random.nextDouble(-0.12, 0.12);
                double x = Math.cos(angle) * radius;
                double z = Math.sin(angle) * radius;
                double size = random.nextDouble(0.8, 1.2);
                double[] shape = {0.3 * size, 1.2 * size, 0.3 * size};
                double top = shape[1] * 0.4;
                Rotation lean = Shapes.outward(angle, 0.44);
                DisplayModel ice = Shapes.block(spike % 2 == 0 ? Material.PACKED_ICE : Material.BLUE_ICE);
                v.display(delay, ice, DisplayMotion.chain(
                        DisplayMotion.builder().life(220).from(x, -shape[1] * 0.6, z).to(x, top, z).rotation(lean)
                                .scale(shape, shape).ease(DisplayMotion.Easing.BACK).build(),
                        DisplayMotion.still(Math.max(50L, 2000L - 220L - delay)),
                        DisplayMotion.builder().life(300).from(x, top, z).to(x, -shape[1] * 0.7, z).rotation(lean)
                                .scale(shape, shape).ease(DisplayMotion.Easing.IN).build()));
            }
        }
    },

    BLINK("blink", Role.ARCANE) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location body = s.origin().add(0, s.height * 0.5, 0);
            for (long beat = 0; beat < w; beat += 100) {
                v.particle(beat, Particle.REVERSE_PORTAL, body, s.count(6, 3), s.width * 0.6, s.height * 0.4,
                        s.width * 0.6, 0.02, null);
            }
            Shapes.sound(v, 0, "BLOCK_RESPAWN_ANCHOR_CHARGE", 0.5f, 1.6f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            Location from = s.left();
            Location to = s.arrived();
            if (from == null) from = s.origin();
            if (to == null) to = at;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int shards = s.count(8, 5);
            DisplayModel shard = Shapes.block(Material.OBSIDIAN);
            DisplayModel sliver = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            double h = s.height;
            double reach = s.width * 0.5 + 0.35;
            // Out: the body folds into a line and its shards are pulled into it.
            v.display(0, sliver, DisplayMotion.builder().life(180).from(0, h / 2, 0).to(0, h / 2, 0)
                    .scale(new double[]{s.width * 1.1, h, s.width * 1.1}, new double[]{0.02, h * 1.1, 0.02})
                    .ease(DisplayMotion.Easing.IN).build(), from);
            for (int piece = 0; piece < shards; piece++) {
                double angle = Math.PI * 2 * piece / shards;
                double y = h * random.nextDouble(0.15, 0.9);
                double size = random.nextDouble(0.12, 0.2);
                v.display(0, shard, DisplayMotion.builder().life(250)
                        .from(Math.cos(angle) * reach, y, Math.sin(angle) * reach).to(0, h / 2, 0)
                        .rotation(Shapes.tilt(random, 1)).spin(0, 1, 0).scale(size, 0.02)
                        .ease(DisplayMotion.Easing.IN).build(), from);
            }
            v.particle(0, Particle.PORTAL, from.clone().add(0, h / 2, 0), s.count(20, 10), s.width * 0.4, h * 0.3,
                    s.width * 0.4, 0.4, null);
            Shapes.sound(v, 0, from, "ENTITY_ENDERMAN_TELEPORT", 1.0f, 1.2f);
            // In, five ticks later: the line opens into the body and the shards are thrown off it.
            long in = 250;
            v.display(in, sliver, DisplayMotion.chain(
                    DisplayMotion.builder().life(150).from(0, h / 2, 0).to(0, h / 2, 0)
                            .scale(new double[]{0.02, h * 1.1, 0.02}, new double[]{s.width * 1.1, h, s.width * 1.1})
                            .ease(DisplayMotion.Easing.OUT).build(),
                    DisplayMotion.builder().life(120).from(0, h / 2, 0).to(0, h / 2, 0)
                            .scale(new double[]{s.width * 1.1, h, s.width * 1.1}, new double[]{0.02, 0.02, 0.02})
                            .ease(DisplayMotion.Easing.IN).build()), to);
            for (int piece = 0; piece < shards; piece++) {
                double angle = Math.PI * 2 * piece / shards + 0.3;
                double size = random.nextDouble(0.12, 0.2);
                v.display(in, shard, DisplayMotion.builder().life(350).from(0, h / 2, 0)
                        .to(Math.cos(angle) * 1.4, h * random.nextDouble(0.2, 0.9), Math.sin(angle) * 1.4)
                        .rotation(Shapes.tilt(random, 1)).spin(1, 1, 0).scale(size, 0.02)
                        .ease(DisplayMotion.Easing.OUT).build(), to);
            }
            v.particle(in, Particle.REVERSE_PORTAL, to.clone().add(0, h / 2, 0), s.count(25, 12), s.width * 0.3,
                    h * 0.3, s.width * 0.3, 0.05, null);
            Shapes.sound(v, in, to, "ENTITY_ENDERMAN_TELEPORT", 0.8f, 0.8f);
        }
    },

    RENEW("renew", Role.HEAL) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location feet = s.origin();
            Telegraphs.circle(v, 0, feet, Math.max(1.2, s.width + 0.6), w, s.main());
            int hearts = s.count(5, 3);
            long life = w + 400;
            DisplayModel heart = DisplayModel.text(Text.of(glyph(s, Role.HEAL, "❤")).build()).light(15);
            double reach = 0.7 + s.width * 0.5;
            for (int piece = 0; piece < hearts; piece++) {
                double start = Math.PI * 2 * piece / hearts;
                v.display(piece * 60L, heart, orbit(reach, 0.3, s.height + 0.4, start, 1, life, 0.6, 1.1));
            }
            for (long beat = 0; beat < w; beat += 200) {
                v.particle(beat, Particle.HAPPY_VILLAGER, feet.clone().add(0, s.height * 0.5, 0), s.count(3, 2),
                        reach * 0.6, s.height * 0.3, reach * 0.6, 0, null);
            }
            Shapes.sound(v, 0, "BLOCK_BEACON_POWER_SELECT", 0.5f, 1.6f);
            Shapes.sound(v, w / 2, "BLOCK_AMETHYST_BLOCK_CHIME", 0.6f, 1.8f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            int points = s.count(18, 10);
            double[] radii = {0.8, 1.5, 2.2};
            for (int ring = 0; ring < radii.length; ring++) {
                Shapes.circle(v, ring * 110L, Particle.DUST, at.clone().add(0, 0.2, 0), radii[ring], points, 0,
                        Shapes.dust(s.main(), 1.2f));
            }
            v.particle(0, Particle.HEART, at.clone().add(0, s.height + 0.2, 0), s.count(5, 3), 0.4, 0.2, 0.4, 0, null);
            Shapes.sound(v, 0, "ENTITY_PLAYER_LEVELUP", 0.35f, 1.8f);
            Shapes.sound(v, 0, "BLOCK_AMETHYST_BLOCK_RESONATE", 0.8f, 1.6f);
        }
    },

    ENRAGE("enrage", Role.DANGER) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            pulse(s, v, 0, 0.15, w / 50 + 3);
            Location head = s.origin().add(0, s.height + 0.1, 0);
            Location body = s.origin().add(0, s.height * 0.5, 0);
            for (long beat = 0; beat < w; beat += 300) {
                v.particle(beat, Particle.ANGRY_VILLAGER, head, 2, s.width * 0.4, 0.15, s.width * 0.4, 0, null);
            }
            for (long beat = 0; beat < w; beat += 150) {
                v.particle(beat, Particle.DUST, body, s.count(6, 3), s.width, s.height * 0.3, s.width, 0,
                        Shapes.dust(s.main(), 1.3f));
            }
            Shapes.sound(v, 0, "ENTITY_POLAR_BEAR_WARNING", 0.8f, 0.6f);
            // A heartbeat that quickens until the roar.
            double[] beats = {0, 0.35, 0.6, 0.8, 0.92};
            for (int beat = 0; beat < beats.length; beat++) {
                Shapes.sound(v, Math.round(w * beats[beat]), "BLOCK_NOTE_BLOCK_BASEDRUM", 0.7f, (float) (0.6 + beat * 0.05));
            }
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            Shapes.sound(v, 0, "ENTITY_RAVAGER_ROAR", 1.2f, 0.7f);
            int plates = s.count(20, 10);
            double reach = 6;
            DisplayModel nylium = DisplayModel.block(MobBodies.block(Material.CRIMSON_NYLIUM)).light(15);
            for (int plate = 0; plate < plates; plate++) {
                double angle = Math.PI * 2 * plate / plates;
                double x = Math.cos(angle);
                double z = Math.sin(angle);
                double arc = Math.PI * 2 * reach / plates * 1.05;
                v.display(0, nylium, DisplayMotion.builder().life(450).from(x * 0.5, 0.1, z * 0.5)
                        .to(x * reach, 0.05, z * reach).rotation(Shapes.facing(angle))
                        .scale(new double[]{0.5, 0.18, 0.35}, new double[]{arc, 0.02, 0.12})
                        .ease(DisplayMotion.Easing.OUT).build());
            }
            Location body = at.clone().add(0, s.height * 0.6, 0);
            v.particle(0, Particle.ANGRY_VILLAGER, body, s.count(8, 4), s.width, s.height * 0.3, s.width, 0, null)
                    .particle(0, Particle.LAVA, body, s.count(6, 3), s.width * 0.5, 0.2, s.width * 0.5, 0, null)
                    .particle(0, Particle.DUST, body, s.count(20, 10), s.width * 1.2, s.height * 0.4, s.width * 1.2, 0,
                            Shapes.dust(s.main(), 1.6f));
            shake(s, v, 0, 14, 2);
        }
    },

    RAIN("rain", Role.DANGER) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Shapes.sound(v, 0, "ITEM_CROSSBOW_LOADING_START", 1.0f, 0.8f);
            Shapes.sound(v, w / 2, "ITEM_CROSSBOW_LOADING_MIDDLE", 1.0f, 0.8f);
            long loose = Math.max(0L, w - 100);
            Shapes.sound(v, loose, "ENTITY_ARROW_SHOOT", 1.0f, 0.6f);
            Shapes.sound(v, loose + 60, "ENTITY_ARROW_SHOOT", 0.8f, 0.8f);
            Location hands = hands(s);
            DisplayModel arrow = Shapes.item(Material.ARROW).billboard("VERTICAL");
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int arrows = s.count(5, 3);
            for (int piece = 0; piece < arrows; piece++) {
                double x = random.nextDouble(-0.6, 0.6);
                double z = random.nextDouble(-0.6, 0.6);
                v.display(loose + piece * 30L, arrow, DisplayMotion.builder().life(500).from(0, 0, 0).to(x, 12, z)
                        .rotation(Rotation.around(Rotation.Axis.Z, Math.PI / 4)).scale(0.9, 0.9)
                        .ease(DisplayMotion.Easing.IN).build(), hands);
            }
        }
    },

    POUNCE("pounce", Role.TELEGRAPH) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            double r = radius(s, 2);
            if (s.skill.radius() > 0) Telegraphs.circle(v, 0, s.point(), r, w + AIRTIME, s.main());
            pulse(s, v, 0, -0.1, w / 50);
            for (int scrape = 0; scrape < 2; scrape++) {
                v.particle(w * scrape / 2, Particle.BLOCK, s.origin().add(0, 0.1, 0), s.count(6, 3), s.width * 0.4,
                        0.05, s.width * 0.4, 0.05, s.ground);
            }
            Shapes.sound(v, 0, "ENTITY_RAVAGER_STEP", 0.6f, 1.3f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            v.particle(0, Particle.CLOUD, at.clone().add(0, 0.2, 0), s.count(8, 4), s.width * 0.4, 0.05,
                    s.width * 0.4, 0.03, null)
                    .particle(0, Particle.BLOCK, at.clone().add(0, 0.1, 0), s.count(8, 4), s.width * 0.4, 0.05,
                            s.width * 0.4, 0.1, s.ground);
            Shapes.sound(v, 0, "ENTITY_GOAT_LONG_JUMP", 1.0f, 1.0f);
        }
    },

    HOOK("hook", Role.DANGER) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            v.display(0, Shapes.item(Material.CHAIN), DisplayMotion.builder().life(Math.max(50L, w))
                    .spin(0, 0, 2).scale(0.3, 0.6).build(), hands(s));
            Shapes.sound(v, 0, "ITEM_ARMOR_EQUIP_CHAIN", 1.0f, 0.7f);
            Shapes.sound(v, Math.max(0, w - 50), "ENTITY_FISHING_BOBBER_THROW", 1.0f, 0.6f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            Location hands = hands(s);
            List<Location> ends = new ArrayList<>();
            for (LivingEntity body : s.reached()) {
                if (ends.size() >= 3) break;
                ends.add(chest(body));
            }
            if (ends.isEmpty() && s.preview) ends.add(s.point().add(0, 1.2, 0));
            DisplayModel link = Shapes.item(Material.CHAIN);
            for (Location end : ends) {
                double spacing = Math.max(0.32, hands.distance(end) / 24);
                Shapes.links(v, 0, hands, end, link, spacing, 380);
                v.particle(0, Particle.CRIT, end, s.count(6, 3), 0.2, 0.3, 0.2, 0.2, null);
                Shapes.sound(v, 0, end, "BLOCK_CHAIN_HIT", 1.0f, 0.8f);
            }
            Shapes.sound(v, 120, hands, "ENTITY_FISHING_BOBBER_RETRIEVE", 1.0f, 0.8f);
        }
    },

    VOLLEY("volley", Role.DANGER) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location hands = hands(s);
            boolean fire = fiery(s.skill.text());
            for (long beat = 0; beat < w; beat += 100) {
                v.particle(beat, Particle.SMOKE, hands, s.count(3, 2), 0.1, 0.1, 0.1, 0.01, null);
                if (fire) v.particle(beat, Particle.SMALL_FLAME, hands, 1, 0.08, 0.08, 0.08, 0.01, null);
            }
            String kind = s.skill.text().trim().toUpperCase(Locale.ROOT);
            switch (kind) {
                case "SMALL_FIREBALL" -> Shapes.sound(v, 0, "ENTITY_BLAZE_AMBIENT", 0.8f, 1.2f);
                case "WITHER_SKULL" -> Shapes.sound(v, 0, "ENTITY_WITHER_AMBIENT", 0.5f, 1.4f);
                case "ARROW" -> Shapes.sound(v, 0, "ITEM_CROSSBOW_LOADING_START", 1.0f, 1.0f);
                case "SNOWBALL" -> Shapes.sound(v, 0, "ENTITY_SNOW_GOLEM_AMBIENT", 0.8f, 1.2f);
                default -> Shapes.sound(v, 0, "ENTITY_GHAST_WARN", 0.6f, 1.2f);
            }
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            Location hands = hands(s);
            Location aim = s.reached().isEmpty() ? s.point().add(0, 1.2, 0) : chest(s.reached().getFirst());
            Vector towards = aim.toVector().subtract(hands.toVector());
            if (towards.lengthSquared() < 1.0E-4) towards = MobAim.facing(s.yaw());
            towards.normalize();
            boolean fire = fiery(s.skill.text());
            for (int puff = 0; puff < s.count(5, 3); puff++) {
                v.particle(0, fire ? Particle.FLAME : Particle.CLOUD, hands, 0, towards.getX(), towards.getY(),
                        towards.getZ(), 0.12 + puff * 0.03, null);
            }
            v.particle(0, Particle.SMOKE, hands, s.count(8, 4), 0.15, 0.15, 0.15, 0.02, null)
                    .particle(0, Particle.POOF, hands, s.count(4, 2), 0.1, 0.1, 0.1, 0.02, null);
            String kind = s.skill.text().trim().toUpperCase(Locale.ROOT);
            switch (kind) {
                case "SMALL_FIREBALL" -> Shapes.sound(v, 0, "ENTITY_BLAZE_SHOOT", 1.0f, 1.0f);
                case "WITHER_SKULL" -> Shapes.sound(v, 0, "ENTITY_WITHER_SHOOT", 0.8f, 1.0f);
                case "ARROW" -> Shapes.sound(v, 0, "ENTITY_SKELETON_SHOOT", 1.0f, 1.0f);
                case "SNOWBALL" -> Shapes.sound(v, 0, "ENTITY_SNOWBALL_THROW", 1.0f, 0.8f);
                default -> Shapes.sound(v, 0, "ENTITY_GHAST_SHOOT", 1.0f, 0.9f);
            }
        }
    },

    CLEAVE("cleave", Role.DANGER) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            double reach = Math.min(MobAim.reach(s.skill), 16);
            double half = Math.toRadians(MobAim.spread(s.skill)) / 2;
            Telegraphs.cone(v, 0, s.origin(), s.yaw(), reach, Math.toDegrees(half * 2), w, s.main());
            // The axe is drawn back past the edge the swing starts from, and held there.
            double facing = heading(s);
            double from = facing - half * 0.3;
            double to = facing - half - 0.6;
            double hold = s.width * 0.5 + 0.7;
            double y = s.height * 0.7;
            v.display(0, Shapes.item(Material.NETHERITE_AXE), DisplayMotion.chain(
                    DisplayMotion.builder().life(Math.max(50L, w * 3 / 4))
                            .from(Math.cos(from) * hold, y * 0.8, Math.sin(from) * hold)
                            .to(Math.cos(to) * hold, y + 0.4, Math.sin(to) * hold)
                            .rotation(blade(to)).scale(0.6, 1.3).ease(DisplayMotion.Easing.OUT).build(),
                    DisplayMotion.builder().life(Math.max(50L, w / 4))
                            .from(Math.cos(to) * hold, y + 0.4, Math.sin(to) * hold)
                            .to(Math.cos(to) * hold, y + 0.4, Math.sin(to) * hold)
                            .rotation(blade(to)).scale(1.3, 1.3).build()), s.origin());
            for (long beat = w / 2; beat < w; beat += 100) {
                v.particle(beat, Particle.DUST, s.origin().add(Math.cos(to) * hold, y + 0.5, Math.sin(to) * hold),
                        s.count(3, 2), 0.15, 0.15, 0.15, 0, Shapes.dust(s.main(), 1.2f));
            }
            Shapes.sound(v, 0, "ITEM_AXE_SCRAPE", 1.0f, 0.6f);
            Shapes.sound(v, w / 2, "ENTITY_RAVAGER_STEP", 0.8f, 0.7f);
            Shapes.sound(v, Math.max(0, w - 150), "ITEM_TRIDENT_RIPTIDE_1", 0.7f, 0.7f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double reach = Math.min(MobAim.reach(s.skill), 16);
            double half = Math.toRadians(MobAim.spread(s.skill)) / 2;
            double facing = heading(s);
            double y = s.height * 0.45;
            // A fan of blades swept across the wedge, first edge first, each flung out as it passes.
            int blades = s.count(10, 5);
            DisplayModel axe = Shapes.item(Material.NETHERITE_AXE);
            DisplayModel trail = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            for (int blade = 0; blade < blades; blade++) {
                double angle = facing - half + half * 2 * blade / Math.max(1, blades - 1);
                double x = Math.cos(angle);
                double z = Math.sin(angle);
                long delay = blade * 22L;
                v.display(delay, blade % 2 == 0 ? axe : trail, DisplayMotion.builder().life(260)
                        .from(x * reach * 0.25, y, z * reach * 0.25).to(x * reach * 0.8, y - 0.2, z * reach * 0.8)
                        .rotation(blade % 2 == 0 ? blade(angle) : Shapes.facing(angle + Math.PI / 2))
                        .scale(blade % 2 == 0 ? new double[]{1.2, 1.2, 1.2} : new double[]{0.9, 0.05, 0.3},
                                new double[]{0.2, 0.2, 0.2})
                        .ease(DisplayMotion.Easing.OUT).build(), at);
                v.particle(delay, Particle.SWEEP_ATTACK, at.clone().add(x * reach * 0.55, y, z * reach * 0.55), 1,
                        0, 0, 0, 0, null);
            }
            for (LivingEntity body : s.reached()) {
                v.particle(120, Particle.CRIT, chest(body), s.count(12, 6), 0.3, 0.4, 0.3, 0.3, null);
            }
            v.particle(0, Particle.BLOCK, at.clone().add(0, 0.1, 0), s.count(10, 5), reach * 0.3, 0.05, reach * 0.3,
                    0.1, s.ground);
            Shapes.sound(v, 0, "ENTITY_PLAYER_ATTACK_SWEEP", 1.0f, 0.6f);
            Shapes.sound(v, 40, "ENTITY_RAVAGER_ATTACK", 0.9f, 1.0f);
            Shapes.sound(v, 80, "ENTITY_PLAYER_ATTACK_STRONG", 0.8f, 0.8f);
            shake(s, v, 0, reach + 4, 1);
        }
    },

    FISSURE("fissure", Role.TELEGRAPH) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location from = s.origin();
            Location end = MobAim.lineEnd(s.lock, Math.min(MobAim.reach(s.skill), MobAim.MAX_DISTANCE));
            Telegraphs.line(v, 0, from, end, MobAim.spread(s.skill), w, s.main());
            pulse(s, v, 0, -0.08, w / 50);
            // The crack runs out ahead of the fill, a dust trail along the ground.
            Vector step = end.toVector().subtract(from.toVector());
            for (long beat = 0; beat < w; beat += 100) {
                double t = Math.min(1, (double) beat / Math.max(1, w) * 1.2);
                Location crack = from.clone().add(step.clone().multiply(t)).add(0, 0.1, 0);
                v.particle(beat, Particle.BLOCK, crack, s.count(6, 3), 0.25, 0.05, 0.25, 0.1, s.ground);
            }
            for (long beat = 0; beat < w; beat += 350) {
                Shapes.sound(v, beat, "BLOCK_DEEPSLATE_BREAK", 0.7f, 0.5f);
            }
            Shapes.sound(v, 0, "ENTITY_IRON_GOLEM_ATTACK", 0.9f, 0.5f);
            if (w >= HOP_AIR + 150) {
                hop(s, v, w - HOP_AIR, 0.45);
                Shapes.sound(v, w - HOP_AIR, "ENTITY_GOAT_LONG_JUMP", 0.9f, 0.6f);
            }
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            Location from = s.origin();
            Location end = MobAim.lineEnd(s.lock, Math.min(MobAim.reach(s.skill), MobAim.MAX_DISTANCE));
            double dx = end.getX() - from.getX();
            double dz = end.getZ() - from.getZ();
            double length = Math.max(1, Math.hypot(dx, dz));
            double angle = Math.atan2(dz, dx);
            double width = MobAim.spread(s.skill);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            DisplayModel slab = DisplayModel.block(s.ground);
            int pieces = (int) Math.clamp(Math.round(length / 0.8), 4, s.count(16, 8));
            // A ripple: the ground splits from the mob outwards, one slab each side in turn.
            for (int piece = 0; piece < pieces; piece++) {
                double t = (piece + 0.5) / pieces;
                double sideSign = piece % 2 == 0 ? 1 : -1;
                double side = sideSign * width * random.nextDouble(0.15, 0.35);
                double x = dx * t - Math.sin(angle) * side;
                double z = dz * t + Math.cos(angle) * side;
                double w = random.nextDouble(0.6, 0.9);
                double h = random.nextDouble(0.8, 1.3);
                double[] shape = {w, h, w * 0.7};
                double top = h * 0.4;
                Rotation lean = Shapes.outward(angle + Math.PI / 2 * sideSign, random.nextDouble(0.3, 0.55));
                long delay = piece * 30L;
                v.display(delay, slab, DisplayMotion.chain(
                        DisplayMotion.builder().life(160).from(x, -h * 0.5 - 0.1, z).to(x, top, z).rotation(lean)
                                .scale(shape, shape).ease(DisplayMotion.Easing.BACK).build(),
                        DisplayMotion.still(300),
                        DisplayMotion.builder().life(300).from(x, top, z).to(x, -h * 0.6, z).rotation(lean)
                                .scale(shape, new double[]{w * 0.9, h * 0.6, w * 0.6})
                                .ease(DisplayMotion.Easing.IN).build()), from);
                Location spot = from.clone().add(x, 0.2, z);
                v.particle(delay, Particle.BLOCK, spot, s.count(6, 3), 0.3, 0.1, 0.3, 0.1, s.ground)
                        .particle(delay, Particle.DUST_PLUME, spot, 2, 0.2, 0.05, 0.2, 0.02, null);
                if (piece % 3 == 0) Shapes.sound(v, delay, spot, "BLOCK_BASALT_BREAK", 0.9f, 0.6f);
            }
            breakSound(s, v, 0, 1.0f, 0.5f);
            Shapes.sound(v, 0, "ENTITY_GENERIC_EXPLODE", 0.5f, 0.5f);
            shake(s, v, 0, length + 6, 2);
        }
    },

    VORTEX("vortex", Role.ARCANE) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            double r = radius(s, 8);
            Location feet = s.origin();
            Location core = feet.clone().add(0, s.height * 0.6, 0);
            Telegraphs.ring(v, 0, feet, r, w, s.main());
            // Three arms of a spiral turning in on the mob, tighter every beat.
            Particle.DustOptions dust = Shapes.dust(s.main(), 1.2f);
            int arm = s.count(7, 4);
            for (long beat = 0; beat < w; beat += 100) {
                double share = 1 - 0.6 * beat / Math.max(1, w);
                for (int spiral = 0; spiral < 3; spiral++) {
                    for (int point = 0; point < arm; point++) {
                        double out = r * share * (point + 1) / arm;
                        double angle = Math.PI * 2 * spiral / 3 + point * 0.45 + beat * 0.006;
                        v.particle(beat, Particle.DUST, feet.clone().add(Math.cos(angle) * out, 0.3,
                                Math.sin(angle) * out), 1, 0, 0, 0, 0, dust);
                    }
                }
                v.particle(beat, Particle.PORTAL, core, s.count(10, 5), r * 0.2, 0.4, r * 0.2, 1.2, null);
            }
            DisplayModel heart = Shapes.glowing(Material.BLACK_STAINED_GLASS, s.main());
            v.display(0, heart, DisplayMotion.builder().life(Math.max(50L, w)).scale(0.05, 0.8).spin(1, 3, 2)
                    .ease(DisplayMotion.Easing.IN).build(), core);
            DisplayModel shard = Shapes.block(Material.CRYING_OBSIDIAN);
            int shards = s.count(6, 3);
            for (int piece = 0; piece < shards; piece++) {
                v.display(0, shard, orbit(1.4 + s.width * 0.5, 0, 0.2, Math.PI * 2 * piece / shards, 2,
                        Math.max(50L, w), 0.1, 0.22), core.clone().subtract(0, 0.2, 0));
            }
            Shapes.sound(v, 0, "BLOCK_PORTAL_TRIGGER", 0.4f, 1.8f);
            Shapes.sound(v, w / 3, "BLOCK_RESPAWN_ANCHOR_CHARGE", 0.7f, 0.6f);
            Shapes.sound(v, Math.max(0, w - 400), "ENTITY_WARDEN_SONIC_CHARGE", 0.6f, 1.4f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            Location core = at.clone().add(0, s.height * 0.6, 0);
            DisplayModel heart = Shapes.glowing(Material.BLACK_STAINED_GLASS, s.main());
            v.display(0, heart, DisplayMotion.chain(
                    DisplayMotion.builder().life(150).scale(0.8, 1.2).spin(0, 2, 0).ease(DisplayMotion.Easing.OUT).build(),
                    DisplayMotion.builder().life(200).scale(1.2, 0.02).spin(0, 3, 0).ease(DisplayMotion.Easing.IN).build()),
                    core);
            // A streak from each body it drags to the heart.
            DisplayModel streak = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            int shown = 0;
            for (LivingEntity body : s.reached()) {
                if (shown++ >= 6) break;
                v.beam(0, chest(body), core, streak, 0.08, 250);
                v.particle(0, Particle.REVERSE_PORTAL, chest(body), s.count(10, 5), 0.3, 0.4, 0.3, 0.05, null);
            }
            if (shown == 0 && s.preview) v.beam(0, s.point().add(0, 1.2, 0), core, streak, 0.08, 250);
            double r = radius(s, 8);
            Shapes.circle(v, 0, Particle.REVERSE_PORTAL, at.clone().add(0, 0.3, 0), r * 0.7, s.count(20, 10), 0, null);
            v.particle(0, Particle.PORTAL, core, s.count(40, 20), r * 0.35, 0.6, r * 0.35, 1.5, null)
                    .particle(350, Particle.SONIC_BOOM, core, 1, 0, 0, 0, 0, null);
            Shapes.sound(v, 0, "ENTITY_ENDERMAN_TELEPORT", 1.0f, 0.5f);
            Shapes.sound(v, 0, "BLOCK_RESPAWN_ANCHOR_DEPLETE", 1.0f, 0.6f);
            Shapes.sound(v, 350, "ENTITY_WARDEN_SONIC_BOOM", 0.5f, 1.6f);
            shake(s, v, 350, r + 4, 1);
        }
    },

    DREAD("dread", Role.ARCANE) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            double r = radius(s, 10);
            Location feet = s.origin();
            Telegraphs.ring(v, 0, feet, r, w, s.main());
            pulse(s, v, 0, 0.12, w / 50 + 2);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (long beat = 0; beat < w; beat += 100) {
                for (int soul = 0; soul < s.count(4, 2); soul++) {
                    double angle = random.nextDouble(Math.PI * 2);
                    double out = random.nextDouble(1, r);
                    v.particle(beat, Particle.SCULK_SOUL, feet.clone().add(Math.cos(angle) * out, 0.2,
                            Math.sin(angle) * out), 0, -Math.cos(angle), 0.3, -Math.sin(angle), 0.08, null);
                }
                v.particle(beat, Particle.LARGE_SMOKE, feet.clone().add(0, s.height + 0.2, 0), s.count(2, 1),
                        s.width * 0.3, 0.1, s.width * 0.3, 0.01, null);
            }
            // A heartbeat that quickens until the shriek.
            double[] beats = {0, 0.4, 0.68, 0.86};
            for (double beat : beats) Shapes.sound(v, Math.round(w * beat), "ENTITY_WARDEN_HEARTBEAT", 1.0f, 0.8f);
            Shapes.sound(v, Math.max(0, w - 300), "BLOCK_SCULK_SHRIEKER_SHRIEK", 1.0f, 0.8f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 10);
            // A wall of shadow runs out to the edge, and the air shudders twice behind it.
            int plates = s.count(20, 10);
            DisplayModel shade = Shapes.glowless(Material.BLACK_STAINED_GLASS);
            double arc = Math.PI * 2 * r / plates * 1.05;
            for (int plate = 0; plate < plates; plate++) {
                double angle = Math.PI * 2 * plate / plates;
                double x = Math.cos(angle);
                double z = Math.sin(angle);
                v.display(0, shade, DisplayMotion.builder().life(550).from(x * 0.6, 0.1, z * 0.6)
                        .to(x * r, 0.1, z * r).rotation(Shapes.facing(angle))
                        .scale(new double[]{0.4, 1.4, 0.1}, new double[]{arc, 0.02, 0.05})
                        .ease(DisplayMotion.Easing.OUT).build());
            }
            double[] rings = {0.35, 0.7};
            for (int ring = 0; ring < rings.length; ring++) {
                Shapes.circle(v, 120L + ring * 150L, Particle.SONIC_BOOM, at.clone().add(0, 1.0, 0), r * rings[ring],
                        s.count(8, 4), 0, null);
            }
            Location head = at.clone().add(0, s.height * 0.8, 0);
            v.particle(0, Particle.SQUID_INK, head, s.count(20, 10), s.width * 0.5, 0.3, s.width * 0.5, 0.15, null)
                    .particle(0, Particle.SCULK_SOUL, head, s.count(12, 6), 0.4, 0.4, 0.4, 0.1, null);
            for (LivingEntity body : s.reached()) {
                v.particle(150, Particle.SCULK_SOUL, body.getLocation().add(0, body.getHeight() + 0.2, 0),
                        s.count(6, 3), 0.2, 0.1, 0.2, 0.02, null);
            }
            Shapes.sound(v, 0, "ENTITY_WARDEN_ROAR", 1.0f, 0.9f);
            Shapes.sound(v, 120, "ENTITY_WARDEN_SONIC_BOOM", 0.6f, 0.7f);
            shake(s, v, 0, r + 4, 2);
        }
    },

    ERUPTION("eruption", Role.FIRE) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location centre = spot(s);
            double r = radius(s, 2.5);
            Telegraphs.circle(v, 0, centre, r, w, s.main());
            // Glowing cracks spread from the middle to the edge as it heats up.
            int cracks = s.count(6, 4);
            DisplayModel magma = Shapes.glowing(Material.MAGMA_BLOCK, s.main());
            for (int crack = 0; crack < cracks; crack++) {
                double angle = Math.PI * 2 * crack / cracks + 0.4;
                double x = Math.cos(angle);
                double z = Math.sin(angle);
                v.display(0, magma, DisplayMotion.builder().life(Math.max(50L, w))
                        .from(0, 0.04, 0).to(x * r * 0.45, 0.04, z * r * 0.45).rotation(Shapes.facing(angle))
                        .scale(new double[]{0.1, 0.04, 0.02}, new double[]{0.16, 0.04, r * 0.9})
                        .ease(DisplayMotion.Easing.OUT).build(), centre);
            }
            for (long beat = 0; beat < w; beat += 150) {
                double heat = (double) beat / Math.max(1, w);
                v.particle(beat, Particle.LAVA, centre.clone().add(0, 0.2, 0), 1 + (int) Math.round(heat * 2),
                        r * 0.3, 0, r * 0.3, 0, null)
                        .particle(beat, Particle.SMOKE, centre.clone().add(0, 0.2, 0), s.count(4, 2), r * 0.4, 0.05,
                                r * 0.4, 0.02, null);
            }
            for (long beat = 0; beat < w; beat += 280) Shapes.sound(v, beat, centre, "BLOCK_LAVA_POP", 0.9f, 0.7f);
            Shapes.sound(v, 0, centre, "BLOCK_LAVA_AMBIENT", 1.0f, 0.8f);
            Shapes.sound(v, 0, "ENTITY_BLAZE_AMBIENT", 0.7f, 0.6f);
            Shapes.sound(v, Math.max(0, w - 120), centre, "ITEM_FIRECHARGE_USE", 1.0f, 0.6f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 2.5);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            // A geyser: magma thrown up in a column that rises, hangs and falls back into the ground.
            DisplayModel magma = Shapes.glowing(Material.MAGMA_BLOCK, s.main());
            int column = s.count(7, 4);
            for (int piece = 0; piece < column; piece++) {
                double x = random.nextDouble(-0.3, 0.3);
                double z = random.nextDouble(-0.3, 0.3);
                double peak = 1.5 + piece * 0.8;
                double size = random.nextDouble(0.5, 0.8) * (1 - piece * 0.06);
                v.display(piece * 25L, magma, DisplayMotion.chain(
                        DisplayMotion.builder().life(320).from(x, 0, z).to(x, peak, z).spin(1, 1, 0)
                                .scale(size, size).ease(DisplayMotion.Easing.OUT).build(),
                        DisplayMotion.builder().life(380).from(x, peak, z).to(x * 3, -0.3, z * 3).spin(1, 0, 1)
                                .scale(size, 0.05).ease(DisplayMotion.Easing.IN).build()));
            }
            int debris = s.count(8, 4);
            for (int piece = 0; piece < debris; piece++) {
                double angle = Math.PI * 2 * piece / debris + random.nextDouble(-0.3, 0.3);
                double out = r * random.nextDouble(0.8, 1.4);
                v.display(0, Shapes.block(piece % 2 == 0 ? Material.BLACKSTONE : Material.MAGMA_BLOCK),
                        Shapes.thrown(0, 0.4, 0, Math.cos(angle) * out, Math.sin(angle) * out,
                                random.nextDouble(0.25, 0.4), random.nextLong(600, 850), random, 400L));
            }
            for (int step = 0; step < 6; step++) {
                Location up = at.clone().add(0, 0.5 + step * 0.9, 0);
                v.particle(step * 40L, Particle.FLAME, up, s.count(10, 5), 0.3, 0.3, 0.3, 0.05, null)
                        .particle(step * 40L, Particle.LAVA, up, 2, 0.3, 0.3, 0.3, 0, null);
            }
            v.particle(0, Particle.EXPLOSION, at.clone().add(0, 0.5, 0), 1)
                    .particle(200, Particle.LARGE_SMOKE, at.clone().add(0, 2, 0), s.count(16, 8), r * 0.4, 1.2, r * 0.4,
                            0.02, null);
            Shapes.circle(v, 0, Particle.FLAME, at.clone().add(0, 0.15, 0), r, s.count(16, 8), 0.15, null);
            Shapes.sound(v, 0, "ENTITY_GENERIC_EXPLODE", 0.9f, 0.8f);
            Shapes.sound(v, 0, "ENTITY_BLAZE_SHOOT", 1.0f, 0.5f);
            Shapes.sound(v, 300, "BLOCK_LAVA_EXTINGUISH", 0.7f, 0.8f);
            shake(s, v, 0, r + 8, 2);
        }
    },

    SMITE("smite", Role.SHOCK) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location centre = spot(s);
            double r = s.skill.radius() > 0 ? radius(s, MobAim.SPOT) : MobAim.SPOT;
            Telegraphs.circle(v, 0, centre, r, w, s.main());
            Telegraphs.cross(v, 0, centre, r * 1.4, w, s.main());
            // Runes turn round the mark, and a thread of light comes down onto it at the end.
            DisplayModel rune = DisplayModel.text(Text.of(glyph(s, Role.SHOCK, "✦")).build()).light(15);
            int runes = s.count(4, 2);
            for (int piece = 0; piece < runes; piece++) {
                v.display(0, rune, orbit(r + 0.3, 0.3, 0.6, Math.PI * 2 * piece / runes, 1,
                        Math.max(50L, w), 0.8, 1.4), centre);
            }
            long thread = w / 2;
            v.beam(thread, centre.clone().add(0, 16, 0), centre.clone().add(0, 0.1, 0),
                    Shapes.glowing(Shapes.nearestGlass(s.main()), s.main()), 0.05, Math.max(50L, w - thread));
            Location hands = hands(s);
            for (long beat = 0; beat < w; beat += 100) {
                v.particle(beat, Particle.ELECTRIC_SPARK, hands, s.count(4, 2), 0.3, 0.3, 0.3, 0.02, null);
            }
            Shapes.sound(v, 0, "BLOCK_BEACON_POWER_SELECT", 0.7f, 1.4f);
            Shapes.sound(v, thread, centre, "BLOCK_RESPAWN_ANCHOR_CHARGE", 0.8f, 1.6f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = s.skill.radius() > 0 ? radius(s, MobAim.SPOT) : MobAim.SPOT;
            Location sky = at.clone().add(0, 20, 0);
            Location ground = at.clone().add(0, 0.05, 0);
            DisplayModel core = Shapes.glowing(Material.WHITE_CONCRETE, Shapes.lighter(s.main(), 0.6));
            v.beam(0, sky, ground, core, 0.35, 280);
            if (!s.lod) v.beam(0, sky, ground, Shapes.glowing(Shapes.nearestGlass(s.main()), s.main()), 0.9, 180);
            quake(s, v, 60, r + 0.5, new double[]{0.8}, 10);
            Shapes.circle(v, 0, Particle.ELECTRIC_SPARK, at.clone().add(0, 0.2, 0), r, s.count(16, 8), 0.4, null);
            v.particle(0, Particle.END_ROD, at.clone().add(0, 1, 0), s.count(20, 10), 0.3, 1.0, 0.3, 0.15, null)
                    .particle(0, Particle.FIREWORK, at.clone().add(0, 0.5, 0), s.count(10, 5), r * 0.3, 0.2, r * 0.3,
                            0.1, null);
            Shapes.sound(v, 0, "ENTITY_LIGHTNING_BOLT_IMPACT", 1.0f, 0.8f);
            Shapes.sound(v, 0, "ITEM_TRIDENT_THUNDER", 0.8f, 1.3f);
            shake(s, v, 0, r + 8, 2);
        }
    },

    JUDGEMENT("judgement", Role.CRIT) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location centre = spot(s);
            double r = radius(s, 4);
            Telegraphs.circle(v, 0, centre, r, w, s.main());
            Telegraphs.cross(v, 0, centre, r * 1.2, w, s.main());
            // Runes turn slowly round the mark while the blade is lowered.
            DisplayModel rune = DisplayModel.text(Text.of(glyph(s, Role.CRIT, "✚")).build()).light(15);
            int runes = s.count(6, 3);
            for (int piece = 0; piece < runes; piece++) {
                v.display(piece * 50L, rune, orbit(r + 0.4, 0.4, 0.4, Math.PI * 2 * piece / runes, 0.5,
                        Math.max(50L, w - piece * 50L), 1.0, 1.8), centre);
            }
            // The blade: it appears high up, hangs turning slowly, then drops in the last beat.
            long appear = w / 5;
            long drop = Math.min(350L, Math.max(100L, w / 5));
            long hang = Math.max(50L, w - appear - drop);
            DisplayModel sword = Shapes.item(Material.GOLDEN_SWORD).light(15);
            Rotation down = Rotation.around(Rotation.Axis.Z, -Math.PI * 3 / 4);
            double size = Math.clamp(r * 1.6, 4, 9);
            v.display(appear, sword, DisplayMotion.chain(
                    DisplayMotion.builder().life(hang).from(0, 24, 0).to(0, 17, 0).rotation(down)
                            .spin(Rotation.Axis.Y, 0.5).scale(size * 0.3, size).ease(DisplayMotion.Easing.OUT).build(),
                    DisplayMotion.builder().life(drop).from(0, 17, 0).to(0, size * 0.35, 0).rotation(down)
                            .scale(size, size).ease(DisplayMotion.Easing.IN).build()), centre);
            v.beam(appear + hang / 3, centre.clone().add(0, 16, 0), centre.clone().add(0, 0.1, 0),
                    Shapes.glowing(Shapes.nearestGlass(s.main()), s.main()), 0.06, Math.max(50L, hang * 2 / 3 + drop));
            for (long beat = appear; beat < w; beat += 150) {
                v.particle(beat, Particle.END_ROD, centre.clone().add(0, 0.3, 0), s.count(3, 2), r * 0.5, 0.1,
                        r * 0.5, 0.02, null);
            }
            Shapes.sound(v, 0, "BLOCK_BEACON_ACTIVATE", 0.8f, 0.6f);
            for (int toll = 0; toll < 3; toll++) {
                Shapes.sound(v, appear + hang * toll / 3, centre, "BLOCK_BELL_USE", 1.0f, 0.5f + toll * 0.1f);
            }
            Shapes.sound(v, appear + hang, centre, "ITEM_TRIDENT_THROW", 1.0f, 0.5f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 4);
            double size = Math.clamp(r * 1.6, 4, 9);
            Rotation down = Rotation.around(Rotation.Axis.Z, -Math.PI * 3 / 4);
            // Driven into the ground, it stands a moment and sinks away.
            v.display(0, Shapes.item(Material.GOLDEN_SWORD).light(15), DisplayMotion.chain(
                    DisplayMotion.builder().life(80).from(0, size * 0.35, 0).to(0, size * 0.22, 0).rotation(down)
                            .scale(size, size).ease(DisplayMotion.Easing.OUT).build(),
                    DisplayMotion.still(1100),
                    DisplayMotion.builder().life(400).from(0, size * 0.22, 0).to(0, -size * 0.4, 0).rotation(down)
                            .scale(size, size * 0.6).ease(DisplayMotion.Easing.IN).build()));
            quake(s, v, 0, r, new double[]{0.35, 0.7, 1.0}, 14);
            DisplayModel glow = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            v.beam(0, at.clone().add(0, 18, 0), at.clone().add(0, 0.1, 0), glow, 1.2, 220);
            Shapes.circle(v, 60, Particle.END_ROD, at.clone().add(0, 0.3, 0), r * 0.5, s.count(20, 10), 0.35, null);
            v.particle(0, Particle.EXPLOSION, at.clone().add(0, 0.5, 0), 2, 0.5, 0.2, 0.5, 0, null)
                    .particle(0, Particle.CRIT, at.clone().add(0, 1, 0), s.count(30, 15), r * 0.4, 0.6, r * 0.4, 0.4, null)
                    .particle(0, Particle.BLOCK, at.clone().add(0, 0.2, 0), s.count(20, 10), r * 0.4, 0.1, r * 0.4,
                            0.15, s.ground);
            Shapes.sound(v, 0, "ENTITY_GENERIC_EXPLODE", 1.0f, 0.6f);
            Shapes.sound(v, 0, "BLOCK_ANVIL_LAND", 0.8f, 0.5f);
            Shapes.sound(v, 0, "ITEM_TRIDENT_THUNDER", 0.8f, 0.8f);
            Shapes.sound(v, 150, "BLOCK_BELL_RESONATE", 1.0f, 0.7f);
            breakSound(s, v, 0, 1.0f, 0.5f);
            shake(s, v, 0, r + 10, 3);
        }
    },

    SUPERNOVA("supernova", Role.FIRE) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            double r = radius(s, 7);
            Location feet = s.origin();
            Location core = sun(s);
            Telegraphs.circle(v, 0, feet, r, w, s.main());
            pulse(s, v, 0, 0.1, w / 50);
            // A sun grows over its head, and in the last beat collapses in on itself.
            long collapse = Math.min(300L, w / 4);
            long grow = Math.max(50L, w - collapse);
            DisplayModel shell = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            DisplayModel heart = Shapes.glowing(Material.WHITE_CONCRETE, Shapes.lighter(s.main(), 0.7));
            v.display(0, shell, DisplayMotion.chain(
                    DisplayMotion.builder().life(grow).scale(0.1, 1.8).spin(1, 2, 1).ease(DisplayMotion.Easing.OUT).build(),
                    DisplayMotion.builder().life(collapse).scale(1.8, 0.2).spin(0, 3, 0).ease(DisplayMotion.Easing.IN).build()),
                    core);
            v.display(0, heart, DisplayMotion.chain(
                    DisplayMotion.builder().life(grow).scale(0.05, 0.9).spin(2, 1, 2).ease(DisplayMotion.Easing.OUT).build(),
                    DisplayMotion.builder().life(collapse).scale(0.9, 0.15).ease(DisplayMotion.Easing.IN).build()), core);
            // Light pulled in from all round: motes that fly into the sun one after another.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int motes = s.count(14, 7);
            DisplayModel mote = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            double dy = core.getY() - feet.getY();
            for (int piece = 0; piece < motes; piece++) {
                double angle = random.nextDouble(Math.PI * 2);
                double out = r * random.nextDouble(0.6, 1.0);
                long start = grow * piece / motes;
                v.display(start, mote, DisplayMotion.builder().life(Math.max(50L, Math.min(700L, grow - start)))
                        .from(Math.cos(angle) * out, random.nextDouble(0.2, 1.5) - dy, Math.sin(angle) * out)
                        .to(0, 0, 0).spin(1, 1, 1).scale(0.22, 0.05).ease(DisplayMotion.Easing.IN).build(), core);
            }
            for (long beat = 0; beat < grow; beat += 100) {
                for (int ray = 0; ray < s.count(4, 2); ray++) {
                    double angle = random.nextDouble(Math.PI * 2);
                    Location from = feet.clone().add(Math.cos(angle) * r, 0.3, Math.sin(angle) * r);
                    Vector towards = core.toVector().subtract(from.toVector());
                    v.particle(beat, Particle.END_ROD, from, 0, towards.getX(), towards.getY(), towards.getZ(),
                            0.12, null);
                }
                v.particle(beat, Particle.FLAME, core, s.count(4, 2), 0.4, 0.4, 0.4, 0.01, null);
            }
            double[] beats = {0, 0.3, 0.52, 0.68, 0.8, 0.88};
            for (int beat = 0; beat < beats.length; beat++) {
                Shapes.sound(v, Math.round(grow * beats[beat]), core, "BLOCK_RESPAWN_ANCHOR_CHARGE", 0.9f,
                        (float) (0.5 + beat * 0.15));
            }
            Shapes.sound(v, 0, "BLOCK_BEACON_AMBIENT", 1.0f, 0.5f);
            Shapes.sound(v, grow, core, "BLOCK_BEACON_DEACTIVATE", 1.0f, 0.5f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 7);
            Location core = at.clone().add(0, sun(s).getY() - s.origin().getY(), 0);
            DisplayModel heart = Shapes.glowing(Material.WHITE_CONCRETE, Shapes.lighter(s.main(), 0.7));
            v.display(0, heart, DisplayMotion.builder().life(260).scale(0.3, 4.5).ease(DisplayMotion.Easing.OUT).build(),
                    core);
            // A shell of light bursting out in every direction: points spread evenly over a sphere.
            DisplayModel shard = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            int shards = s.count(26, 12);
            double golden = Math.PI * (3 - Math.sqrt(5));
            double dy = core.getY() - at.getY();
            for (int piece = 0; piece < shards; piece++) {
                double y = 1 - 2 * (piece + 0.5) / shards;
                double ring = Math.sqrt(1 - y * y);
                double angle = golden * piece;
                double x = Math.cos(angle) * ring;
                double z = Math.sin(angle) * ring;
                double far = r * 0.9;
                v.display(0, shard, DisplayMotion.builder().life(520).from(x * 0.5, dy + y * 0.5, z * 0.5)
                        .to(x * far, Math.max(0.2, dy + y * far * 0.6), z * far).rotation(Shapes.along(x, y, z))
                        .scale(new double[]{0.35, 0.35, 0.9}, new double[]{0.05, 0.05, 0.2})
                        .ease(DisplayMotion.Easing.OUT).build());
            }
            int plates = s.count(22, 11);
            DisplayModel magma = DisplayModel.block(MobBodies.block(Material.MAGMA_BLOCK)).light(15);
            double arc = Math.PI * 2 * r / plates * 1.05;
            for (int plate = 0; plate < plates; plate++) {
                double angle = Math.PI * 2 * plate / plates;
                double x = Math.cos(angle);
                double z = Math.sin(angle);
                v.display(40, magma, DisplayMotion.builder().life(500).from(x * 0.8, 0.1, z * 0.8)
                        .to(x * r, 0.05, z * r).rotation(Shapes.facing(angle))
                        .scale(new double[]{0.5, 0.25, 0.35}, new double[]{arc, 0.02, 0.1})
                        .ease(DisplayMotion.Easing.OUT).build());
            }
            v.particle(0, Particle.EXPLOSION_EMITTER, core, 1)
                    .particle(0, Particle.FLAME, core, s.count(60, 30), 0.3, 0.3, 0.3, 0.45, null)
                    .particle(0, Particle.LAVA, at.clone().add(0, 0.5, 0), s.count(14, 7), r * 0.4, 0.3, r * 0.4, 0, null)
                    .particle(150, Particle.FIREWORK, core, s.count(30, 15), 0.5, 0.5, 0.5, 0.35, null);
            Shapes.sound(v, 0, "ENTITY_GENERIC_EXPLODE", 1.2f, 0.5f);
            Shapes.sound(v, 0, "ENTITY_DRAGON_FIREBALL_EXPLODE", 1.0f, 0.6f);
            Shapes.sound(v, 80, "ENTITY_LIGHTNING_BOLT_THUNDER", 0.7f, 0.7f);
            Shapes.sound(v, 200, "ENTITY_FIREWORK_ROCKET_TWINKLE", 0.8f, 0.8f);
            shake(s, v, 0, r + 12, 3);
        }

        /** Where the sun hangs: over its head, a little clear of it. */
        private Location sun(Stage s) {
            return s.origin().add(0, s.height + 1.4, 0);
        }
    },

    TEMPEST("tempest", Role.SHOCK) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            double r = radius(s, 6);
            Location feet = s.origin();
            Telegraphs.circle(v, 0, feet, r, w, s.main());
            // A funnel: layers turning faster the lower they are, wider the higher.
            int layers = s.count(8, 4);
            DisplayModel streak = Shapes.glowing(Material.WHITE_STAINED_GLASS, s.main());
            long life = Math.max(50L, w);
            for (int layer = 0; layer < layers; layer++) {
                double reach = 0.5 + s.width * 0.5 + layer * 0.32;
                double y = 0.2 + layer * 0.55;
                double turns = Math.max(1, life / 400.0) * (1.6 - layer * 0.08);
                for (int side = 0; side < 2; side++) {
                    double start = Math.PI * side + layer * 0.7;
                    v.display(layer * 40L, streak, orbit(reach, y, y + 0.3, start, turns, life - layer * 40L,
                            0.1, 0.45));
                }
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (long beat = 0; beat < w; beat += 100) {
                double angle = beat * 0.02;
                for (int arm = 0; arm < 3; arm++) {
                    double a = angle + Math.PI * 2 * arm / 3;
                    double reach = r * random.nextDouble(0.4, 1.0);
                    v.particle(beat, Particle.CLOUD, feet.clone().add(Math.cos(a) * reach, 0.2, Math.sin(a) * reach),
                            0, -Math.sin(a), 0.15, Math.cos(a), 0.3, null);
                }
                v.particle(beat, Particle.BLOCK, feet.clone().add(0, 0.1, 0), s.count(3, 2), r * 0.3, 0.05, r * 0.3,
                        0.1, s.ground);
            }
            for (long beat = 0; beat < w; beat += 450) Shapes.sound(v, beat, "ENTITY_BREEZE_WHIRL", 1.0f, 0.6f);
            Shapes.sound(v, 0, "ENTITY_BREEZE_CHARGE", 1.0f, 0.6f);
            Shapes.sound(v, Math.max(0, w - 300), "ENTITY_BREEZE_INHALE", 1.0f, 0.5f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 6);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            // The funnel comes apart: every streak flung out and up along the way it was turning.
            int pieces = s.count(16, 8);
            DisplayModel streak = Shapes.glowing(Material.WHITE_STAINED_GLASS, s.main());
            for (int piece = 0; piece < pieces; piece++) {
                double angle = Math.PI * 2 * piece / pieces;
                double y = 0.2 + (piece % 8) * 0.55;
                double near = 0.5 + s.width * 0.5 + (piece % 8) * 0.32;
                double swirl = angle + 1.2;
                v.display(0, streak, DisplayMotion.builder().life(450)
                        .from(Math.cos(angle) * near, y, Math.sin(angle) * near)
                        .to(Math.cos(swirl) * r, y + random.nextDouble(1, 3), Math.sin(swirl) * r)
                        .rotation(Shapes.facing(swirl)).spin(0, 2, 0)
                        .scale(new double[]{0.45, 0.06, 0.2}, new double[]{0.05, 0.02, 0.05})
                        .ease(DisplayMotion.Easing.OUT).build());
            }
            int plates = s.count(18, 9);
            DisplayModel ring = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            double arc = Math.PI * 2 * r / plates * 1.1;
            for (int plate = 0; plate < plates; plate++) {
                double angle = Math.PI * 2 * plate / plates;
                double x = Math.cos(angle);
                double z = Math.sin(angle);
                v.display(0, ring, DisplayMotion.builder().life(380).from(x * 0.6, 0.3, z * 0.6)
                        .to(x * r, 0.2, z * r).rotation(Shapes.facing(angle))
                        .scale(new double[]{0.5, 0.35, 0.25}, new double[]{arc, 0.02, 0.1})
                        .ease(DisplayMotion.Easing.OUT).build());
            }
            for (LivingEntity body : s.reached()) {
                v.particle(0, Particle.CLOUD, chest(body), s.count(8, 4), 0.2, 0.4, 0.2, 0.15, null)
                        .particle(0, Particle.SWEEP_ATTACK, chest(body), 1, 0, 0, 0, 0, null);
            }
            v.particle(0, Particle.GUST_EMITTER_LARGE, at.clone().add(0, 1, 0), 1)
                    .particle(0, Particle.CLOUD, at.clone().add(0, 1.5, 0), s.count(30, 15), 0.5, 1.2, 0.5, 0.35, null);
            Shapes.sound(v, 0, "ENTITY_BREEZE_WIND_BURST", 1.2f, 0.6f);
            Shapes.sound(v, 0, "ENTITY_WIND_CHARGE_WIND_BURST", 1.0f, 0.8f);
            Shapes.sound(v, 100, "ITEM_TRIDENT_RIPTIDE_3", 0.8f, 0.8f);
            shake(s, v, 0, r + 6, 2);
        }
    },

    STARFALL("starfall", Role.ARCANE) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location centre = spot(s);
            double r = radius(s, 7);
            pulse(s, v, 0, 0.08, w / 50);
            // A constellation drawn star by star over the area, joined as each one lights.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int stars = s.count(7, 4);
            List<Location> points = new ArrayList<>(stars);
            for (int star = 0; star < stars; star++) {
                double angle = Math.PI * 2 * star / stars + random.nextDouble(-0.3, 0.3);
                double out = r * random.nextDouble(0.3, 0.8);
                points.add(centre.clone().add(Math.cos(angle) * out, random.nextDouble(9, 12), Math.sin(angle) * out));
            }
            DisplayModel glyph = DisplayModel.text(Text.of(glyph(s, Role.ARCANE, "✦")).build()).light(15)
                    .billboard("CENTER");
            DisplayModel thread = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            long step = Math.max(50L, w * 2 / 3 / stars);
            for (int star = 0; star < stars; star++) {
                long lit = star * step;
                long left = Math.max(50L, w - lit + 200);
                v.display(lit, glyph, DisplayMotion.chain(
                        DisplayMotion.builder().life(180).scale(0.5, 4.5).ease(DisplayMotion.Easing.BACK).build(),
                        DisplayMotion.builder().life(Math.max(50L, left - 180)).scale(4.5, 3.2).build()),
                        points.get(star));
                if (star > 0) v.beam(lit, points.get(star - 1), points.get(star), thread, 0.04, left);
                Shapes.sound(v, lit, points.get(star), "BLOCK_AMETHYST_BLOCK_CHIME", 1.0f, (float) (0.8 + star * 0.15));
            }
            Location hands = hands(s);
            for (long beat = 0; beat < w; beat += 100) {
                v.particle(beat, Particle.END_ROD, hands, 0, 0, 1, 0, 0.3, null);
            }
            Shapes.sound(v, 0, "BLOCK_BEACON_POWER_SELECT", 0.8f, 1.5f);
            Shapes.sound(v, Math.max(0, w - 200), "ENTITY_FIREWORK_ROCKET_LAUNCH", 1.0f, 0.6f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            Location hands = hands(s);
            v.particle(0, Particle.END_ROD, hands, s.count(12, 6), 0.2, 0.2, 0.2, 0.15, null)
                    .particle(0, Particle.FIREWORK, hands, s.count(8, 4), 0.1, 0.1, 0.1, 0.1, null);
            Shapes.sound(v, 0, "ENTITY_ILLUSIONER_CAST_SPELL", 1.0f, 1.2f);
        }
    },

    PRISM("prism", Role.SHOCK) {
        @Override
        void windup(Stage s, Vfx v) {
            long w = s.windup();
            Location from = s.origin();
            Location end = MobAim.lineEnd(s.lock, Math.min(MobAim.reach(s.skill), MobAim.MAX_DISTANCE));
            Telegraphs.line(v, 0, from, end, MobAim.spread(s.skill), w, s.main());
            Location hands = hands(s);
            // A crystal turning faster and faster, three rings of light closing round it.
            DisplayModel crystal = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            Rotation diamond = Rotation.around(Rotation.Axis.X, Math.PI / 4).then(Rotation.around(Rotation.Axis.Z, Math.PI / 4));
            long life = Math.max(50L, w);
            v.display(0, crystal, DisplayMotion.builder().life(life).rotation(diamond).scale(0.1, 0.55)
                    .spin(0, Math.max(2, life / 150.0), 0).ease(DisplayMotion.Easing.IN).build(), hands);
            DisplayModel core = Shapes.glowing(Material.WHITE_CONCRETE, Shapes.lighter(s.main(), 0.6));
            v.display(life / 3, core, DisplayMotion.builder().life(Math.max(50L, life * 2 / 3)).scale(0.02, 0.25)
                    .spin(1, 2, 1).ease(DisplayMotion.Easing.IN).build(), hands);
            int beads = s.count(6, 3);
            for (int ring = 0; ring < 3; ring++) {
                for (int bead = 0; bead < beads; bead++) {
                    v.display(ring * life / 4, crystal, orbit(1.4 - ring * 0.35, -0.05 + ring * 0.05, 0.05,
                            Math.PI * 2 * bead / beads, 1 + ring, Math.max(50L, life - ring * life / 4), 0.12, 0.08),
                            hands.clone().subtract(0, 0.05, 0));
                }
            }
            // A thin aiming line flickers on in the last third.
            DisplayModel sight = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            Location far = end.clone().add(0, hands.getY() - from.getY(), 0);
            for (long flick = w * 2 / 3; flick < w; flick += 150) {
                v.beam(flick, hands, far, sight, 0.03, 90);
            }
            for (long beat = 0; beat < w; beat += 100) {
                v.particle(beat, Particle.ELECTRIC_SPARK, hands, s.count(4, 2), 0.35, 0.35, 0.35, 0.05, null);
            }
            for (int tone = 0; tone < 4; tone++) {
                Shapes.sound(v, w * tone / 4, "BLOCK_BEACON_POWER_SELECT", 0.7f, (float) (0.8 + tone * 0.3));
            }
            Shapes.sound(v, Math.max(0, w - 500), "ENTITY_GUARDIAN_ATTACK", 1.0f, 1.2f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            Location from = s.origin();
            Location end = MobAim.lineEnd(s.lock, Math.min(MobAim.reach(s.skill), MobAim.MAX_DISTANCE));
            Location hands = hands(s);
            Location far = end.clone().add(0, hands.getY() - from.getY(), 0);
            DisplayModel core = Shapes.glowing(Material.WHITE_CONCRETE, Shapes.lighter(s.main(), 0.6));
            DisplayModel halo = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            // The beam: a white core held a moment, a halo round it pulsing twice.
            v.beam(0, hands, far, core, 0.28, 650);
            if (!s.lod) {
                v.beam(0, hands, far, halo, 0.75, 280);
                v.beam(320, hands, far, halo, 0.6, 250);
            }
            double length = hands.distance(far);
            int marks = (int) Math.clamp(Math.round(length), 4, s.count(16, 8));
            Vector step = far.toVector().subtract(hands.toVector()).multiply(1.0 / marks);
            for (int mark = 1; mark <= marks; mark++) {
                Location on = hands.clone().add(step.clone().multiply(mark));
                Location ground = on.clone();
                ground.setY(from.getY() + 0.1);
                long when = mark * 12L;
                v.particle(when, Particle.ELECTRIC_SPARK, on, 3, 0.15, 0.15, 0.15, 0.1, null)
                        .particle(when + 100, Particle.SMOKE, ground, 2, 0.2, 0.02, 0.2, 0.01, null)
                        .particle(when, Particle.END_ROD, on, 1, 0.05, 0.05, 0.05, 0.05, null);
            }
            v.particle(0, Particle.EXPLOSION, far, 1)
                    .particle(0, Particle.FIREWORK, far, s.count(16, 8), 0.3, 0.3, 0.3, 0.2, null)
                    .particle(0, Particle.CLOUD, hands, s.count(6, 3), 0.1, 0.1, 0.1, 0.08, null);
            for (LivingEntity body : s.reached()) {
                v.particle(0, Particle.CRIT, chest(body), s.count(12, 6), 0.3, 0.4, 0.3, 0.3, null);
            }
            Shapes.sound(v, 0, "ENTITY_WARDEN_SONIC_BOOM", 0.7f, 1.8f);
            Shapes.sound(v, 0, far, "ENTITY_GENERIC_EXPLODE", 0.6f, 1.4f);
            Shapes.sound(v, 600, "BLOCK_BEACON_DEACTIVATE", 0.8f, 1.6f);
            shake(s, v, 0, 6, 1);
        }
    },

    BURST("burst", Role.ARCANE) {
        @Override
        void windup(Stage s, Vfx v) {
            gather(s, v, Particle.CLOUD);
            Shapes.sound(v, 0, "ENTITY_BREEZE_INHALE", 0.8f, 1.0f);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            double r = radius(s, 4);
            int plates = s.count(16, 8);
            DisplayModel ring = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
            double arc = Math.PI * 2 * r / plates * 1.1;
            for (int plate = 0; plate < plates; plate++) {
                double angle = Math.PI * 2 * plate / plates;
                double x = Math.cos(angle);
                double z = Math.sin(angle);
                v.display(0, ring, DisplayMotion.builder().life(380).from(x * 0.4, 0.15, z * 0.4)
                        .to(x * r, 0.12, z * r).rotation(Shapes.facing(angle))
                        .scale(new double[]{0.5, 0.1, 0.25}, new double[]{arc, 0.02, 0.1})
                        .ease(DisplayMotion.Easing.OUT).build());
            }
            Shapes.circle(v, 0, Particle.CLOUD, at.clone().add(0, 0.2, 0), 0.6, s.count(16, 8), 0.3, null);
            v.particle(0, Particle.SWEEP_ATTACK, at.clone().add(0, 1.0, 0), s.count(4, 2), r * 0.35, 0.2, r * 0.35,
                    0, null);
            Shapes.sound(v, 0, "ENTITY_BREEZE_WIND_BURST", 1.0f, 1.0f);
            Shapes.sound(v, 0, "ENTITY_PLAYER_ATTACK_SWEEP", 0.7f, 0.8f);
        }
    },

    HOP("hop", Role.ARCANE) {
        @Override
        void impact(Stage s, Vfx v, Location at) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Shapes.circle(v, 0, Particle.CLOUD, at.clone().add(0, 0.1, 0), Math.max(0.5, s.width * 0.6),
                    s.count(10, 6), 0.12, null);
            int chips = s.count(6, 3);
            DisplayModel dirt = DisplayModel.block(s.ground);
            for (int chip = 0; chip < chips; chip++) {
                double angle = Math.PI * 2 * chip / chips + random.nextDouble(-0.3, 0.3);
                double out = random.nextDouble(0.4, 0.8) + s.width * 0.4;
                double size = random.nextDouble(0.12, 0.2);
                v.display(0, dirt, Shapes.thrown(Math.cos(angle) * s.width * 0.4, 0.1, Math.sin(angle) * s.width * 0.4,
                        Math.cos(angle) * out, Math.sin(angle) * out, size, random.nextLong(350, 480), random, 200L));
            }
            for (int spark = 1; spark <= 3; spark++) {
                v.particle(spark * 100L, Particle.FIREWORK, at.clone().add(0, 0.4 + spark * 0.7, 0), 2, 0.15, 0.1,
                        0.15, 0.01, null);
            }
            Shapes.sound(v, 0, "ENTITY_RABBIT_JUMP", 1.0f, 0.8f);
            Shapes.sound(v, 0, "ENTITY_FIREWORK_ROCKET_LAUNCH", 0.4f, 1.5f);
        }
    },

    INFLATE("inflate", Role.ARCANE) {
        @Override
        void impact(Stage s, Vfx v, Location at) {
            shell(s, v, at, true);
            v.particle(0, Particle.END_ROD, at.clone().add(0, s.height * 0.5, 0), s.count(8, 4), s.width * 0.4,
                    s.height * 0.3, s.width * 0.4, 0.05, null);
            Shapes.sound(v, 0, "ENTITY_PUFFER_FISH_BLOW_UP", 1.0f, 1.0f);
            Shapes.sound(v, 0, "BLOCK_AMETHYST_BLOCK_RESONATE", 0.5f, 1.6f);
        }
    },

    ZOOM("zoom", Role.ARCANE) {
        @Override
        void impact(Stage s, Vfx v, Location at) {
            float yaw = s.caster != null ? s.caster.getLocation().getYaw() : s.yaw();
            Vector ahead = MobAim.facing(yaw);
            double angle = Math.atan2(ahead.getZ(), ahead.getX());
            DisplayModel streak = Shapes.glowing(Material.WHITE_STAINED_GLASS, s.main());
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int lines = s.count(6, 3);
            for (int line = 0; line < lines; line++) {
                double side = random.nextDouble(-0.6, 0.6) * Math.max(0.6, s.width);
                double y = s.height * random.nextDouble(0.2, 0.9);
                double x = -ahead.getZ() * side - ahead.getX() * 0.3;
                double z = ahead.getX() * side - ahead.getZ() * 0.3;
                v.display(line * 25L, streak, DisplayMotion.builder().life(300).from(x, y, z)
                        .to(x - ahead.getX() * 1.6, y, z - ahead.getZ() * 1.6).rotation(Shapes.facing(angle))
                        .scale(new double[]{0.04, 0.04, 1.2}, new double[]{0.01, 0.01, 0.2})
                        .ease(DisplayMotion.Easing.OUT).build());
            }
            for (int puff = 0; puff < s.count(6, 3); puff++) {
                v.particle(0, Particle.CLOUD, at.clone().add(0, 0.2, 0), 0, -ahead.getX(), 0.05, -ahead.getZ(),
                        0.2 + puff * 0.03, null);
            }
            Shapes.sound(v, 0, "ITEM_TRIDENT_RIPTIDE_1", 0.8f, 1.3f);
            Shapes.sound(v, 0, "ENTITY_BREEZE_WHIRL", 0.6f, 1.4f);
        }
    },

    SHRINK("shrink", Role.ARCANE) {
        @Override
        void impact(Stage s, Vfx v, Location at) {
            shell(s, v, at, false);
            v.particle(0, Particle.POOF, at.clone().add(0, s.height * 0.5, 0), s.count(8, 4), s.width * 0.4,
                    s.height * 0.3, s.width * 0.4, 0.02, null)
                    .particle(0, Particle.HEART, at.clone().add(0, s.height, 0), 2, 0.3, 0.2, 0.3, 0, null);
            Shapes.sound(v, 0, "ENTITY_PUFFER_FISH_BLOW_OUT", 1.0f, 1.2f);
            Shapes.sound(v, 0, "ENTITY_CHICKEN_EGG", 0.6f, 1.4f);
        }
    },

    PUFF("puff", Role.ARCANE) {
        @Override
        void windup(Stage s, Vfx v) {
            gather(s, v, Particle.DUST);
        }

        @Override
        void impact(Stage s, Vfx v, Location at) {
            boolean fire = s.skill.type() == MobSkill.Type.IGNITE;
            int rgb = s.tint >= 0 ? s.tint : fire ? s.colour(Role.FIRE) : MobShows.potionColour(s.skill.text(), s.main());
            List<Location> clouds = new ArrayList<>();
            for (LivingEntity body : s.reached()) {
                if (clouds.size() >= 6) break;
                clouds.add(body.getLocation());
            }
            if (clouds.isEmpty()) clouds.add(s.preview && s.skill.radius() <= 0 ? s.point() : at);
            DisplayModel wisp = Shapes.glowing(Shapes.nearestGlass(rgb), rgb);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int shown = 0;
            for (Location cloud : clouds) {
                Location centre = cloud.clone().add(0, 1.0, 0);
                v.particle(0, Particle.DUST, centre, s.count(16, 8), 0.35, 0.5, 0.35, 0, Shapes.dust(rgb, 1.3f));
                if (fire) {
                    v.particle(0, Particle.FLAME, centre, s.count(12, 6), 0.3, 0.5, 0.3, 0.02, null)
                            .particle(0, Particle.LAVA, centre, 2, 0.2, 0.2, 0.2, 0, null);
                }
                if (shown++ < 3) {
                    for (int piece = 0; piece < s.count(4, 2); piece++) {
                        double size = random.nextDouble(0.1, 0.18);
                        double x = random.nextDouble(-0.35, 0.35);
                        double z = random.nextDouble(-0.35, 0.35);
                        v.display(piece * 40L, wisp, DisplayMotion.builder().life(600).from(x, 0.6, z)
                                .to(x * 1.4, 1.5, z * 1.4).scale(size, 0.02).ease(DisplayMotion.Easing.OUT).build(),
                                cloud);
                    }
                }
            }
            if (s.skill.type() == MobSkill.Type.POTION && s.skill.radius() > 0) {
                double r = s.skill.radius();
                Shapes.circle(v, 0, Particle.DUST, at.clone().add(0, 0.25, 0), r * 0.5, s.count(16, 8), 0,
                        Shapes.dust(rgb, 1.2f));
                Shapes.circle(v, 120, Particle.DUST, at.clone().add(0, 0.25, 0), r, s.count(24, 12), 0,
                        Shapes.dust(rgb, 1.2f));
            }
            Shapes.sound(v, 0, fire ? "ITEM_FIRECHARGE_USE" : "ENTITY_SPLASH_POTION_BREAK", 0.8f, fire ? 1.2f : 1.1f);
        }
    };

    /** How long a pounce is in the air, give or take, for its landing circle to fill in time. */
    static final long AIRTIME = 650L;

    /** How long a slam's hop is in the air: 0.45 blocks a tick up, vanilla gravity and drag. */
    static final long HOP_AIR = 600L;

    final String id;
    final Role role;

    MobStyle(String id, Role role) {
        this.id = id;
        this.role = role;
    }

    /**
     * The warning, drawn as the wind-up starts; only called with a wind-up.
     *
     * @param v an effect anchored at where the caster stands
     */
    void windup(Stage s, Vfx v) {
    }

    /**
     * The moment it lands.
     *
     * @param v  an effect anchored at {@code at}
     * @param at where it lands: the caster now, or the spot its aim struck
     */
    void impact(Stage s, Vfx v, Location at) {
    }

    /** A style by id, in any case, or {@code null} for none and for an id this version does not draw. */
    static @Nullable MobStyle of(String id) {
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (MobStyle style : values()) {
            if (style.id.equals(wanted)) return style;
        }
        return null;
    }

    // ------------------------------------------------------------ geometry

    /** A skill's reach, or a style's own when it has none, kept to what reads on screen. */
    static double radius(Stage s, double fallback) {
        double radius = s.skill.radius() > 0 ? s.skill.radius() : fallback;
        return Math.clamp(radius, 0.8, 16);
    }

    /**
     * Where the skill will land, as known when its wind-up starts: the caster
     * for what it does around itself, the target's spot for what it throws.
     */
    static Location spot(Stage s) {
        MobSkill skill = s.skill;
        return switch (skill.cast().aim()) {
            case SELF, ALL, CONE, LINE -> s.origin();
            case GROUND, TARGET, NEAREST, FARTHEST, RANDOM -> s.point();
            case AUTO -> switch (skill.type()) {
                case ZONE, BARRAGE, LEAP, LIGHTNING, IGNITE, PULL -> s.point();
                case POTION -> skill.radius() > 0 ? s.origin() : s.point();
                default -> s.origin();
            };
        };
    }

    /** The way the cast faced as it began, as an angle from east towards south. */
    static double heading(Stage s) {
        Vector ahead = MobAim.facing(s.yaw());
        return Math.atan2(ahead.getZ(), ahead.getX());
    }

    /** In front of its chest: where a caster's hands are. */
    static Location hands(Stage s) {
        Vector ahead = MobAim.facing(s.yaw()).multiply(s.width * 0.5 + 0.25);
        return s.origin().add(ahead).add(0, s.height * 0.68, 0);
    }

    static Location chest(Entity body) {
        return body.getLocation().add(0, body.getHeight() * 0.6, 0);
    }

    /** Where a dash will run to: its reach, the way it faced as it wound up. */
    static Location dashEnd(Stage s) {
        return s.origin().add(MobAim.facing(s.yaw()).multiply(MobMoves.dashReach(s.skill)));
    }

    /** How far out a shield's rings stand, from the body's size. */
    static double bubble(Stage s) {
        return Math.max(s.width / 2 + 0.7, s.height * 0.45);
    }

    /** A sword turned so its tip points out along {@code angle} and its flat stands upright. */
    static Rotation blade(double angle) {
        return Rotation.around(Rotation.Axis.Z, -Math.PI / 4)
                .then(Rotation.around(Rotation.Axis.Y, -Math.PI / 2))
                .then(Shapes.facing(angle));
    }

    // ---------------------------------------------------------------- pieces

    /**
     * Rings of the ground bursting up as broken slabs and settling back: a
     * slam's shockwave, a pounce's landing.
     *
     * @param shares each ring's share of the radius, inner first
     * @param most   the most slabs one ring gets
     */
    static void quake(Stage s, Vfx v, long atMillis, double radius, double[] shares, int most) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        DisplayModel slab = DisplayModel.block(s.ground);
        for (int ring = 0; ring < shares.length; ring++) {
            double reach = radius * shares[ring];
            int pieces = s.count((int) Math.clamp(Math.round(Math.PI * 2 * reach), 6, most), 4);
            long delay = atMillis + ring * 70L;
            for (int piece = 0; piece < pieces; piece++) {
                double angle = Math.PI * 2 * piece / pieces + random.nextDouble(-0.15, 0.15);
                double x = Math.cos(angle) * reach;
                double z = Math.sin(angle) * reach;
                double width = random.nextDouble(0.55, 0.8);
                double height = random.nextDouble(0.55, 0.9);
                double[] shape = {width, height, width * 0.8};
                double top = height * 0.45;
                Rotation lean = Shapes.outward(angle, random.nextDouble(0.3, 0.55));
                v.display(delay, slab, DisplayMotion.chain(
                        DisplayMotion.builder().life(170).from(x, -height * 0.5 - 0.1, z).to(x, top, z).rotation(lean)
                                .scale(shape, shape).ease(DisplayMotion.Easing.BACK).build(),
                        DisplayMotion.still(140),
                        DisplayMotion.builder().life(280).from(x, top, z).to(x, -height * 0.6, z).rotation(lean)
                                .scale(shape, new double[]{width * 0.9, height * 0.6, width * 0.7})
                                .ease(DisplayMotion.Easing.IN).build()));
            }
            Shapes.circle(v, delay, Particle.BLOCK, v.origin().add(0, 0.15, 0), reach, s.count(12, 6), 0, s.ground);
        }
    }

    /**
     * A chain-lightning arc: a white core and a halo in the shock colour, three
     * crooked pieces, and a flicker a moment later.
     */
    static void arc(Stage s, Vfx v, long atMillis, Location from, Location to, int jump) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int colour = s.main();
        double swing = Math.min(0.9, from.distance(to) / 8);
        DisplayModel core = Shapes.glowing(Material.WHITE_CONCRETE, Shapes.lighter(colour, 0.6));
        List<Location> path = Shapes.jagged(from, to, 3, swing, random);
        Shapes.bolt(v, atMillis, path, core, 0.07, 240);
        if (!s.lod) {
            Shapes.bolt(v, atMillis, path, Shapes.glowing(Shapes.nearestGlass(colour), colour), 0.22, 170);
        }
        Shapes.bolt(v, atMillis + 80, Shapes.jagged(from, to, 3, swing, random), core, 0.05, 120);
        v.particle(atMillis, Particle.END_ROD, to, s.count(8, 4), 0.2, 0.3, 0.2, 0.05, null)
                .particle(atMillis, Particle.ELECTRIC_SPARK, to, s.count(14, 7), 0.3, 0.4, 0.3, 0.2, null);
        Shapes.sound(v, atMillis, to, "ENTITY_LIGHTNING_BOLT_IMPACT", 0.6f, (float) Math.min(2.0, 1.6 + 0.1 * jump));
    }

    /** A thin ring of ice rising round a frozen body's feet and staying as long as it is slowed. */
    static void encase(Stage s, Vfx v, LivingEntity body, long millis) {
        int pieces = s.count(6, 4);
        double reach = Math.max(0.45, body.getWidth() * 0.5 + 0.2);
        DisplayModel ice = Shapes.block(Material.PACKED_ICE);
        double[] shape = {0.3, 0.5, 0.12};
        for (int piece = 0; piece < pieces; piece++) {
            double angle = Math.PI * 2 * piece / pieces;
            double x = Math.cos(angle) * reach;
            double z = Math.sin(angle) * reach;
            Rotation lean = Shapes.outward(angle, 0.3);
            v.ride(0, ice, DisplayMotion.chain(
                    DisplayMotion.builder().life(120).from(x, -0.2, z).to(x, 0.22, z).rotation(lean)
                            .scale(new double[]{0.3, 0.05, 0.12}, shape).ease(DisplayMotion.Easing.BACK).build(),
                    DisplayMotion.still(Math.max(50L, millis - 370L)),
                    DisplayMotion.builder().life(250).from(x, 0.22, z).to(x, 0.05, z).rotation(lean)
                            .scale(shape, new double[]{0.02, 0.02, 0.02}).ease(DisplayMotion.Easing.IN).build()), body);
        }
    }

    /**
     * Plates of glass round the body flying out as it swells, or in as it
     * shrinks.
     */
    static void shell(Stage s, Vfx v, Location at, boolean out) {
        int plates = s.count(10, 6);
        DisplayModel glass = Shapes.glowing(Shapes.nearestGlass(s.main()), s.main());
        double near = s.width * 0.5 + 0.1;
        double far = near + 0.9;
        for (int plate = 0; plate < plates; plate++) {
            double angle = Math.PI * 2 * plate / plates;
            double y = s.height * (0.2 + 0.7 * ((plate * 3) % plates) / (double) plates);
            double x = Math.cos(angle);
            double z = Math.sin(angle);
            double from = out ? near : far;
            double to = out ? far : near * 0.3;
            v.display(0, glass, DisplayMotion.builder().life(out ? 280 : 260)
                    .from(x * from, y, z * from).to(x * to, out ? y : s.height * 0.5, z * to)
                    .rotation(Shapes.facing(angle))
                    .scale(new double[]{0.35, 0.35, 0.03}, new double[]{0.02, 0.02, 0.02})
                    .ease(out ? DisplayMotion.Easing.OUT : DisplayMotion.Easing.IN).build(), at);
        }
    }

    /** Particles drawn in from around the body over the wind-up. */
    static void gather(Stage s, Vfx v, Particle particle) {
        long w = s.windup();
        Location body = s.origin().add(0, s.height * 0.5, 0);
        int points = s.count(8, 4);
        Particle.DustOptions dust = particle == Particle.DUST ? Shapes.dust(s.main(), 1.1f) : null;
        for (long beat = 0; beat < w; beat += 100) {
            for (int point = 0; point < points; point++) {
                double angle = Math.PI * 2 * point / points + beat * 0.003;
                double x = Math.cos(angle) * 1.4;
                double z = Math.sin(angle) * 1.4;
                if (dust != null) {
                    double share = 1 - (double) beat / Math.max(1, w);
                    v.particle(beat, particle, body.clone().add(x * share, 0, z * share), 1, 0, 0, 0, 0, dust);
                } else {
                    v.particle(beat, particle, body.clone().add(x, 0, z), 0, -x, 0, -z, 0.1, null);
                }
            }
        }
    }

    /**
     * Pieces going round and up: hearts round a healer.
     *
     * @param start  where on the circle it starts, in radians
     * @param turns  how many times round over its life
     */
    static DisplayMotion orbit(double radius, double fromY, double toY, double start, double turns, long life,
                               double fromScale, double toScale) {
        int poses = (int) Math.clamp(Math.round(life / 50.0), 4, 24);
        List<DisplayKeyframe> frames = new ArrayList<>(poses + 1);
        for (int pose = 0; pose <= poses; pose++) {
            double t = (double) pose / poses;
            double angle = start + Math.PI * 2 * turns * t;
            // Swells in over the first fifth, shrinks away over the last.
            double scale = t < 0.2 ? fromScale + (toScale - fromScale) * (t / 0.2)
                    : t > 0.8 ? toScale * (1 - (t - 0.8) / 0.2) + 0.05 : toScale;
            frames.add(new DisplayKeyframe(Math.round(life * t), (float) (Math.cos(angle) * radius),
                    (float) (fromY + (toY - fromY) * t), (float) (Math.sin(angle) * radius), Rotation.NONE,
                    (float) scale, (float) scale, (float) scale));
        }
        return DisplayMotion.of(frames, life);
    }

    // ---------------------------------------------------------- the caster

    /** Scales the caster by {@code delta} for a while: a crouch before a jump, a swell before a roar. */
    static void pulse(Stage s, Vfx v, long atMillis, double delta, long ticks) {
        LivingEntity caster = s.caster;
        if (caster == null || ticks <= 0) return;
        v.call(atMillis, () -> MobShows.pulse(s.plugin, caster, delta, ticks));
    }

    /** A hop straight up, part of the wind-up; only a caster standing on the ground. */
    static void hop(Stage s, Vfx v, long atMillis, double speed) {
        LivingEntity caster = s.caster;
        if (caster == null) return;
        v.call(atMillis, () -> {
            if (caster.isValid() && caster.isOnGround()) {
                caster.setVelocity(caster.getVelocity().setY(speed));
            }
        });
    }

    static void shake(Stage s, Vfx v, long atMillis, double radius, int designed) {
        int beats = s.shakes(designed);
        if (beats > 0) v.shake(atMillis, radius, beats, 90L);
    }

    static void breakSound(Stage s, Vfx v, long atMillis, float volume, float pitch) {
        try {
            if (s.ground.getSoundGroup() != null) v.sound(atMillis, s.ground.getSoundGroup().getBreakSound(), volume, pitch);
        } catch (RuntimeException | LinkageError noServer) {
            // No sound group without a live server; the rest of the slam still plays.
        }
    }

    /**
     * A glyph in a role's colour as text notation: a palette token stays a
     * token, so a palette reload recolours it; a tint or a hex is a colour tag.
     */
    static String glyph(Stage s, Role role, String glyph) {
        String written = s.theme.of(role).trim();
        boolean tinted = s.tint >= 0 && s.style != null && s.style.role == role;
        if (!tinted && written.startsWith("{") && Shapes.colour(written) >= 0) return written + glyph;
        return "<#" + String.format(Locale.ROOT, "%06x", s.colour(role) & 0xFFFFFF) + ">" + glyph;
    }

    /** Whether a projectile burns: its muzzle flashes fire rather than smoke. */
    static boolean fiery(String kind) {
        String upper = kind.trim().toUpperCase(Locale.ROOT);
        return upper.isEmpty() || upper.contains("FIREBALL");
    }

    /** The first potion line's length in milliseconds: {@code NAME|LEVEL|SECONDS}, 3 s when it says none. */
    static long potionMillis(String lines) {
        String[] parts = lines.lines().findFirst().orElse("").split("\\|");
        if (parts.length < 3) return 3000L;
        try {
            return Math.round(Double.parseDouble(parts[2].trim()) * 1000);
        } catch (NumberFormatException notANumber) {
            return 3000L;
        }
    }

    /** The block the ground is made of, for slabs that burst out of it; dirt where it is not solid. */
    static BlockData groundOf(Location at) {
        try {
            var block = at.clone().subtract(0, 0.2, 0).getBlock();
            if (block.getType().isSolid() && block.getType().isOccluding()) return block.getBlockData();
        } catch (RuntimeException | LinkageError unreadable) {
            // Not this thread's region, or no world behind it.
        }
        return MobBodies.block(Material.COARSE_DIRT);
    }

    /** Whether a style draws anything on its own before it lands. */
    boolean windsUp() {
        return switch (this) {
            case HOP, INFLATE, ZOOM, SHRINK -> false;
            default -> true;
        };
    }

    /** Roughly how long its impact lasts, for the large-effect cap. */
    long length(MobSkill skill) {
        long lingering = switch (skill.type()) {
            case ZONE, SHIELD -> Math.min(30_000L, skill.duration().toMillis());
            case BARRAGE -> 1_000L + 200L * Math.max(1, Math.round(skill.amount()));
            default -> 0L;
        };
        return Math.max(lingering, this == NOVA || this == DREAD || this == ERUPTION || this == JUDGEMENT || this == SUPERNOVA ? 2_400L : 1_200L);
    }

}
