package net.exylia.lib.platform;

/**
 * Which newer server features this server has.
 *
 * <p>Exylia plugins run on Minecraft 1.21.1 and newer while compiling against
 * a newer API. Each probe here asks whether the class or method behind a
 * feature exists, never which version the server reports, so a fork that
 * backports a feature gets it. Every answer is computed once, when this class
 * loads, and kept in a {@code static final} field: asking costs nothing.
 *
 * <pre>{@code
 * if (Features.itemModel()) {
 *     ModernModels.apply(meta, key);   // a @RequiresServer("1.21.4") class
 * }
 * }</pre>
 *
 * @see RequiresServer
 * @since 1.448.0
 */
public final class Features {

    private static final boolean DATA_COMPONENTS =
            hasClass("io.papermc.paper.datacomponent.DataComponentTypes");
    private static final boolean ITEM_MODEL =
            hasMethod("org.bukkit.inventory.meta.ItemMeta", "setItemModel", "org.bukkit.NamespacedKey");
    private static final boolean TOOLTIP_STYLE =
            hasMethod("org.bukkit.inventory.meta.ItemMeta", "setTooltipStyle", "org.bukkit.NamespacedKey");
    private static final boolean TEXT_SHADOW =
            hasClass("net.kyori.adventure.text.format.ShadowColor");
    private static final boolean PLAYER_INPUT =
            hasClass("org.bukkit.event.player.PlayerInputEvent");
    private static final boolean MENU_BUILDERS =
            hasClass("org.bukkit.inventory.view.builder.LocationInventoryViewBuilder");
    private static final boolean EQUIPMENT_EVENT =
            hasClass("io.papermc.paper.event.entity.EntityEquipmentChangedEvent");
    private static final boolean TOOLTIP_DISPLAY =
            hasClass("io.papermc.paper.datacomponent.item.TooltipDisplay");
    private static final boolean SPRITES =
            hasClass("net.kyori.adventure.text.ObjectComponent")
                    && hasMethod("net.kyori.adventure.text.minimessage.tag.standard.StandardTags", "sprite", null);

    private Features() {
    }

    /** Paper's data component API ({@code ItemStack#setData}, 1.21.4). */
    public static boolean dataComponents() {
        return DATA_COMPONENTS;
    }

    /** {@code item_model}: an item drawn with any model by key (1.21.4). */
    public static boolean itemModel() {
        return ITEM_MODEL;
    }

    /** {@code tooltip_style}: a custom tooltip background (1.21.2). */
    public static boolean tooltipStyle() {
        return TOOLTIP_STYLE;
    }

    /** Text shadow colour in Adventure ({@code ShadowColor}, Minecraft 1.21.4). */
    public static boolean textShadow() {
        return TEXT_SHADOW;
    }

    /** {@code PlayerInputEvent}: the keys a player holds (1.21.3). */
    public static boolean playerInput() {
        return PLAYER_INPUT;
    }

    /** {@code MenuType} view builders, such as an anvil opened by code (1.21.4). */
    public static boolean menuBuilders() {
        return MENU_BUILDERS;
    }

    /** {@code EntityEquipmentChangedEvent} (1.21.4). */
    public static boolean equipmentEvent() {
        return EQUIPMENT_EVENT;
    }

    /**
     * {@code tooltip_display}: one component that hides the others (1.21.5).
     * Before it, each component carried its own {@code show_in_tooltip}.
     *
     * @since 1.475.0
     */
    public static boolean tooltipDisplay() {
        return TOOLTIP_DISPLAY;
    }

    /**
     * Sprites in text: an item, block, particle or interface icon drawn inside
     * a line, as {@code {icon:...}} writes (Minecraft 1.21.9, Adventure 4.25).
     *
     * @since 1.477.0
     */
    public static boolean sprites() {
        return SPRITES;
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, Features.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    private static boolean hasMethod(String owner, String name, String parameter) {
        try {
            ClassLoader loader = Features.class.getClassLoader();
            Class<?> type = Class.forName(owner, false, loader);
            if (parameter == null) {
                type.getMethod(name);
            } else {
                type.getMethod(name, Class.forName(parameter, false, loader));
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError absent) {
            return false;
        }
    }
}
