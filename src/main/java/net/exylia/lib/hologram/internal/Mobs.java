package net.exylia.lib.hologram.internal;

import org.bukkit.entity.EntityType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a mob line needs to know about its mob without a server entity: whether
 * the type is one, and how tall it stands.
 */
final class Mobs {

    /** How tall a mob is taken to be when the server cannot say: a standing player. */
    static final double FALLBACK_HEIGHT = 1.8;

    private static final Map<String, Double> HEIGHTS = new ConcurrentHashMap<>();

    private Mobs() {
    }

    /** Whether a name is a living entity type a packet can draw on its own: a player needs a profile, so not that. */
    static boolean living(String name) {
        try {
            EntityType type = EntityType.valueOf(name);
            return type.isAlive() && type != EntityType.PLAYER;
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return false;
        }
    }

    /**
     * How tall the mob stands at scale 1, in blocks, read once per type from
     * the server's own entity type. The API has no such question, so it is
     * asked of CraftBukkit, which Paper and Folia run with Mojang's names.
     */
    static double height(String name) {
        return HEIGHTS.computeIfAbsent(name, Mobs::lookUp);
    }

    private static double lookUp(String name) {
        try {
            Class<?> craft = Class.forName("org.bukkit.craftbukkit.entity.CraftEntityType");
            Object type = craft.getMethod("bukkitToMinecraft", EntityType.class).invoke(null, EntityType.valueOf(name));
            float height = (float) type.getClass().getMethod("getHeight").invoke(type);
            return height > 0 ? height : FALLBACK_HEIGHT;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) {
            return FALLBACK_HEIGHT;
        }
    }
}
