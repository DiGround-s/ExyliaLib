package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.npc.internal.NpcRuntime;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceStep;
import net.exylia.lib.util.sequence.SequenceTarget;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The steps that are not shapes.
 *
 * <p>Each is a small immutable record holding what its line resolved to. They
 * are grouped here because individually they are a handful of lines each, and a
 * file per token would be twenty files that say almost nothing.
 */
final class Steps {

    private Steps() {
    }

    /** A burst of particles at the anchor. */
    record Particles(ParticlePaint paint, double yShift) implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            List<Player> observers = target.observers();
            if (observers.isEmpty()) {
                return;
            }
            Location where = yShift == 0.0
                    ? target.location()
                    : target.location().clone().add(0, yShift, 0);
            paint.draw(observers, where);
        }
    }

    /**
     * A sound at the anchor.
     *
     * <p>Held as its key rather than as a {@code Sound}: the enum's constants
     * move between versions and several of them stopped being enum constants at
     * all, while the key is what the client actually receives. It is also what
     * lets a resource pack's own sound be written in a file.
     */
    record Noise(String key, float volume, float pitch) implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            Location where = target.location();
            for (Player observer : target.observers()) {
                observer.playSound(where, key, volume, pitch);
            }
        }
    }

    /**
     * The look and sound of a lightning strike, without the strike.
     *
     * <p>No entity, no fire, no damage: a flash, sparks and thunder. What every
     * cosmetic actually wants, and what makes it safe to play in a lobby.
     */
    record Lightning(float volume, float pitch) implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            List<Player> observers = target.observers();
            if (observers.isEmpty()) {
                return;
            }
            Location at = target.location();
            Location above = at.clone().add(0, 1.5, 0);
            for (Player observer : observers) {
                if (ParticlePaint.FLASH != null) {
                    observer.spawnParticle(ParticlePaint.FLASH, at, 1);
                }
                if (ParticlePaint.SPARK != null) {
                    observer.spawnParticle(ParticlePaint.SPARK, above, 60, 0.3, 1.5, 0.3, 0.3);
                }
                observer.playSound(at, "entity.lightning_bolt.thunder", volume, pitch);
            }
        }
    }

    /** A firework that goes off where it is told, with nothing left behind. */
    record Fireworks(Color colour, Color fade, FireworkEffect.Type type,
                     boolean trail, boolean flicker, int power) implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            Location at = target.location();
            if (at.getWorld() == null) {
                return;
            }
            Firework firework = at.getWorld().spawn(at, Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .withColor(colour)
                    .withFade(fade)
                    .with(type)
                    .trail(trail)
                    .flicker(flicker)
                    .build());
            meta.setPower(power);
            firework.setFireworkMeta(meta);
            // Detonating in the same tick, rather than a task 50ms later: the
            // entity never ticks, never moves, never needs hiding from distant
            // players, and cannot be left behind by a cancelled sequence.
            // ExyliaCommons scheduled the detonation, tagged the entity so its
            // own listener could cancel the damage, and hid it by hand from
            // everyone out of range.
            firework.detonate();
        }
    }

    /** A console command, with the anchor's coordinates available to it. */
    record Command(String template) implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            Location at = target.location();
            Player source = target.source();
            String command = template
                    .replace("{player}", source != null ? source.getName() : "")
                    .replace("{world}", at.getWorld() != null ? at.getWorld().getName() : "")
                    .replace("{x}", String.valueOf(at.getBlockX()))
                    .replace("{y}", String.valueOf(at.getBlockY()))
                    .replace("{z}", String.valueOf(at.getBlockZ()));
            run.scheduler().run(() ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
    }

    /** A potion effect on whatever the sequence happened to. */
    record Potion(PotionEffectType type, int durationTicks, int amplifier) implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            if (!(target.target() instanceof LivingEntity living)) {
                return;
            }
            run.scheduler().runAtEntity(living, () ->
                    living.addPotionEffect(new PotionEffect(type, durationTicks, amplifier)));
        }
    }

    /** A title, on whoever caused this. */
    record TitleStep(String title, String subtitle, long fadeIn, long stay, long fadeOut)
            implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            Player source = target.source();
            if (source == null) {
                return;
            }
            // Through Text, so a title in a sequence follows the palette and
            // resolves placeholders like every other message. Commons used the
            // legacy serialiser here, so {primary} arrived on screen literally.
            source.showTitle(net.kyori.adventure.title.Title.title(
                    Text.of(title).forPlayer(source).build(),
                    Text.of(subtitle).forPlayer(source).build(),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(fadeIn),
                            java.time.Duration.ofMillis(stay),
                            java.time.Duration.ofMillis(fadeOut))));
        }
    }

    /** An action bar, on whoever caused this. */
    record ActionBarStep(String text) implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            Player source = target.source();
            if (source == null) {
                return;
            }
            source.sendActionBar(Text.of(text).forPlayer(source).build());
        }
    }

    /**
     * A line of chat, to whoever the sequence is for.
     *
     * <p>The one thing ExyliaCommons' effect entries could do that a sequence
     * could not. Centring is {@code Text}'s, through the same {@code <center>}
     * tag every other message in the ecosystem uses, so there is no flag here.
     */
    record Message(String text) implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            Player source = target.source();
            if (source == null) {
                return;
            }
            source.sendMessage(Text.of(text).forPlayer(source).build());
        }
    }

    /**
     * A player-shaped body, left where the sequence happened.
     *
     * <p>The one step that draws a person. Everything else in this module is
     * light or objects; a body is the thing that makes a death read as having
     * happened to somebody in particular.
     *
     * <p>Whose face it wears is decided when the sequence plays rather than
     * when the file is read, because the answer is whoever just died.
     */
    record Corpse(String owner, Face face, String texture, NpcPose pose, long lifeMillis,
                  boolean equipped, int glowArgb, double yShift, boolean facesSource)
            implements SequenceStep {

        /** Whose face the body wears. */
        enum Face {

            /** A texture written in the file. */
            FIXED,

            /** Whoever set the sequence off. */
            KILLER,

            /** Whoever it happened to. */
            VICTIM
        }

        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            List<Player> observers = target.observers();
            if (observers.isEmpty()) {
                return;
            }
            NpcModel model = model(target);
            if (model == null) {
                return;
            }
            Location where = yShift == 0.0
                    ? target.location().clone()
                    : target.location().clone().add(0, yShift, 0);
            Player source = target.source();
            if (facesSource && source != null) {
                // Facing whoever did it, which is the difference between a body
                // and a body that was looking at something.
                where.setYaw((float) Math.toDegrees(Math.atan2(
                        where.getX() - source.getLocation().getX(),
                        source.getLocation().getZ() - where.getZ())));
            }
            NpcHandle handle = NpcRuntime.show(owner, model, where, lifeMillis, observers);
            if (handle == null) {
                return;
            }
            // Owned by the run as well as by the module, so a preview the player
            // closed does not leave somebody standing in the arena.
            run.owns(new TaskHandle() {
                @Override
                public void cancel() {
                    handle.remove();
                }

                @Override
                public boolean isCancelled() {
                    return !handle.isShowing();
                }

                @Override
                public boolean isRepeating() {
                    return false;
                }
            });
        }

        private @Nullable NpcModel model(SequenceTarget target) {
            NpcModel model = switch (face) {
                case KILLER -> wearer(target.source());
                case VICTIM -> wearer(target.target() instanceof Player player ? player : null);
                case FIXED -> texture == null ? null : NpcModel.of("Body", texture, null);
            };
            if (model == null) {
                return null;
            }
            model = model.pose(pose);
            if (glowArgb >= 0) {
                model = model.glow(glowArgb);
            }
            return model;
        }

        private @Nullable NpcModel wearer(@Nullable Player player) {
            if (player == null) {
                return texture == null ? null : NpcModel.of("Body", texture, null);
            }
            NpcModel model = NpcModel.of(player);
            return equipped ? model.wearing(player) : model;
        }

        @Override
        public long trailMillis() {
            return lifeMillis;
        }
    }

    /** A pause before the rest of the sequence. */
    record Delay(long millis) implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
            // Nothing to do: the runner reads holdMillis and schedules the rest.
        }

        @Override
        public long holdMillis() {
            return millis;
        }
    }

    /** A step that does nothing, for a line that could not be understood. */
    record Broken() implements SequenceStep {
        @Override
        public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
        }
    }

    static @Nullable PotionEffectType potion(@NotNull String name) {
        return org.bukkit.Registry.EFFECT.get(
                org.bukkit.NamespacedKey.minecraft(name.trim().toLowerCase(java.util.Locale.ROOT)));
    }
}
