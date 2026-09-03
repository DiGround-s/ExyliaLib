package net.exylia.lib.effect.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.sound.Sounds;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;

/**
 * Sends effects as packets.
 *
 * <p>Every PacketEvents type in the module is referenced here and nowhere else,
 * so a server without the plugin never loads this class and never sees a missing
 * one. {@link Packets} decides whether it is safe to call.
 *
 * <p>Only the effects that would otherwise leave server-side state live here: a
 * boss bar sent as a packet is nothing the server has to track, and a sound or a
 * particle skips the enum round trip. Titles and action bars went back to the
 * server's own Adventure API, which serialises off the server thread; see
 * {@link Bars}.
 */
final class PacketSender {

    private PacketSender() {
    }

    /** Returns whether PacketEvents is loaded and ready to accept packets. */
    static boolean ready() {
        try {
            var api = PacketEvents.getAPI();
            return api != null && api.isLoaded() && api.isInitialized();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    // ------------------------------------------------------------------
    // Boss bar
    // ------------------------------------------------------------------

    static void bossBarAdd(Player player, UUID id, Component title, float progress,
                           String colour, String overlay) {
        WrapperPlayServerBossBar packet = new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.ADD);
        packet.setTitle(title);
        packet.setHealth(progress);
        packet.setColor(colour(colour));
        packet.setOverlay(overlay(overlay));
        packet.setFlags(EnumSet.noneOf(BossBar.Flag.class));
        send(player, packet);
    }

    /** Updates only the fill, which is what a timer changes every tick. */
    static void bossBarProgress(Player player, UUID id, float progress) {
        WrapperPlayServerBossBar packet =
                new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.UPDATE_HEALTH);
        packet.setHealth(progress);
        send(player, packet);
    }

    static void bossBarTitle(Player player, UUID id, Component title) {
        WrapperPlayServerBossBar packet =
                new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.UPDATE_TITLE);
        packet.setTitle(title);
        send(player, packet);
    }

    static void bossBarStyle(Player player, UUID id, String colour, String overlay) {
        WrapperPlayServerBossBar packet =
                new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.UPDATE_STYLE);
        packet.setColor(colour(colour));
        packet.setOverlay(overlay(overlay));
        send(player, packet);
    }

    static void bossBarRemove(Player player, UUID id) {
        send(player, new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.REMOVE));
    }

    private static BossBar.Color colour(String name) {
        try {
            return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BossBar.Color.PURPLE;
        }
    }

    private static BossBar.Overlay overlay(String name) {
        try {
            return BossBar.Overlay.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BossBar.Overlay.PROGRESS;
        }
    }

    // ------------------------------------------------------------------
    // Particles and sound
    // ------------------------------------------------------------------

    /**
     * Sends particles to one player.
     *
     * <p>Per-player rather than to everyone in range, which is the point: a
     * preview outline, a selection marker or a private effect is drawn for the
     * one player who should see it, and the server never spawns anything.
     */
    static boolean particle(Player player, String name, double x, double y, double z,
                            float offsetX, float offsetY, float offsetZ,
                            float speed, int count, boolean longDistance) {
        ParticleType<?> type = ParticleTypes.getByName(particleKey(name));
        if (type == null) {
            return false;
        }
        send(player, new WrapperPlayServerParticle(
                new Particle<>(type),
                longDistance,
                new Vector3d(x, y, z),
                new Vector3f(offsetX, offsetY, offsetZ),
                speed,
                count));
        return true;
    }

    /**
     * Cleared the first time the sound wrapper fails to link.
     *
     * <p>The wrapper is compiled against a PacketEvents newer than some servers
     * run: the {@code Vector3d} constructor arrived in 2.13, and on 2.11 every
     * call threw {@code NoSuchMethodError} into the caller's catch before
     * falling back to Bukkit. The fallback is right; paying for the throw on
     * every sound, and for the JVM to resolve the missing call each time, was
     * not. One failure is enough to know.
     */
    private static volatile boolean soundLinks = true;

    static boolean sound(Player player, String name, String category,
                         double x, double y, double z, float volume, float pitch) {
        if (!soundLinks) {
            return false;
        }
        var sound = Sounds.getByName(soundKey(name));
        if (sound == null) {
            return false;
        }
        try {
            send(player, new WrapperPlayServerSoundEffect(
                    sound,
                    category(category),
                    new Vector3d(x, y, z),
                    volume,
                    pitch));
        } catch (LinkageError outdated) {
            soundLinks = false;
            return false;
        }
        return true;
    }

    private static SoundCategory category(String name) {
        try {
            return SoundCategory.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SoundCategory.MASTER;
        }
    }

    /**
     * Builds the registry key for a sound.
     *
     * <p>Sound keys are dotted, such as {@code minecraft:entity.player.levelup},
     * but server owners write the Bukkit enum name
     * {@code ENTITY_PLAYER_LEVELUP}. Both are accepted, and so is a key that
     * already carries a namespace, so a resource pack's own sound works
     * unchanged.
     */
    private static String soundKey(String name) {
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (trimmed.indexOf(':') >= 0) {
            return trimmed;
        }
        // Only an all-underscore name is a Bukkit enum; one that already has
        // dots is a real key missing its namespace.
        return "minecraft:" + (trimmed.indexOf('.') >= 0 ? trimmed : trimmed.replace('_', '.'));
    }

    /**
     * Builds the registry key for a particle.
     *
     * <p>Unlike sounds, particle keys keep their underscores:
     * {@code minecraft:angry_villager}. Converting them to dots the way a sound
     * needs would make every particle name fail to resolve.
     */
    private static String particleKey(String name) {
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }
}
