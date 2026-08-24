package net.exylia.lib.util.loot;

import org.jetbrains.annotations.NotNull;

/**
 * What one {@link LootEntry} gives when it comes up.
 *
 * <p>{@link #ITEM} is what every loot table in the ecosystem was until
 * ExyliaCommons grew {@link #COMMAND} for mines, and it stays the default: an
 * entry stored before the distinction existed carries no type at all, and it
 * meant an item.
 *
 * <p>A caller that only deals in items — a chest, a spawner — never has to know
 * this exists. {@link Loot#roll} resolves items and skips the rest.
 *
 * @since 1.56.0
 */
public enum LootType {

    /** An item, described by {@link LootEntry#itemSnapshot()}. */
    ITEM("BARRIER"),

    /** A console command, held in {@link LootEntry#command()}. */
    COMMAND("COMMAND_BLOCK");

    private final String defaultIcon;

    LootType(String defaultIcon) {
        this.defaultIcon = defaultIcon;
    }

    /**
     * The material a menu draws for an entry of this type that has no payload
     * to draw itself with.
     *
     * <p>The same two materials ExyliaCommons picked, so an editor ported over
     * looks the same.
     *
     * @return a material name
     */
    public @NotNull String defaultIcon() {
        return defaultIcon;
    }
}
