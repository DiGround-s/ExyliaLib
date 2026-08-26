package net.exylia.lib.item.internal;

import io.papermc.paper.datacomponent.DataComponentType;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hides the tooltip block an item type writes for itself.
 *
 * <p>Confined here for two reasons. {@code DataComponentTypes} resolves its
 * constants against the server's registry in a static initialiser, so naming it
 * needs a live server; and this is the one place in the library that reaches a
 * component through reflection, which the rest of the code is not allowed to do.
 *
 * <h2>Why reflection, when the rule says none</h2>
 *
 * <p>The rule exists to stop us copying ExyliaCommons, which reflected because
 * it supported servers older than data components. This is a different problem:
 * the component <em>changed name</em> between two versions the library supports
 * at once, and neither name compiles on both.
 *
 * <ul>
 *   <li>On 1.21.4 it is {@code hide_additional_tooltip}, a non-valued
 *       component. Gone by 1.21.11.</li>
 *   <li>On 1.21.11 it is {@code tooltip_display}, whose value lists the
 *       components to hide. Its type does not exist in paper-api 1.21.4, so
 *       naming it does not compile here at all.</li>
 * </ul>
 *
 * <p>Naming the first as a field is what threw {@code NoSuchFieldError} through
 * the renderer on a live 1.21.11 server and stopped menus opening. The registry
 * answers for the first by name; only the second needs reflection, because the
 * library cannot name a type its compile target has never heard of.
 *
 * <p>Nothing here throws. A server that supports neither draws the item with
 * the flags alone and says so once.
 */
final class ItemComponents implements ItemRenderer.Components {

    static final ItemRenderer.Components INSTANCE = new ItemComponents();

    /** The component on 1.21.4, and on every version before it moved. */
    private static final NamespacedKey HIDE_ADDITIONAL_TOOLTIP =
            NamespacedKey.minecraft("hide_additional_tooltip");

    /** Its replacement from 1.21.5 on. */
    private static final NamespacedKey TOOLTIP_DISPLAY =
            NamespacedKey.minecraft("tooltip_display");

    /**
     * What the old component used to cover, named one by one.
     *
     * <p>{@code tooltip_display} hides components rather than a category, so
     * the category has to be spelled out. These are the ones an item type
     * writes about itself: a smithing template's "Applies to: Armor" comes from
     * {@code provides_trim_material}, a potion's effect list from
     * {@code potion_contents}, and so on. Names absent from a given server are
     * skipped, which is how one list serves several versions.
     */
    private static final List<String> WRITTEN_BY_TYPE = List.of(
            "provides_trim_material",
            "provides_banner_patterns",
            "potion_contents",
            "fireworks",
            "firework_explosion",
            "instrument",
            "banner_patterns",
            "bundle_contents",
            "container",
            "charged_projectiles",
            "written_book_content",
            "stored_enchantments",
            "jukebox_playable",
            "ominous_bottle_amplifier",
            "map_id",
            "bucket_entity_data",
            "suspicious_stew_effects");

    /**
     * What the flags cover, named here as well.
     *
     * <p>From 1.21.5 an {@code ItemFlag} <em>is</em> an entry in this same
     * component, so writing the component below replaces what
     * {@code addItemFlags} put in it. That is why an item with
     * {@code hide-attributes: true} still showed its armour and damage lines,
     * its trim and its dye: the flags were set on the meta, and then the write
     * that hid the type-written block threw them away.
     *
     * <p>The three named here are the ones
     * {@code ItemRenderer.appearance} asks for alongside the additional
     * tooltip. Anything else already on the item is kept by merging, not by
     * being listed.
     */
    private static final List<String> HIDDEN_BY_FLAGS = List.of(
            "attribute_modifiers",
            "trim",
            "dyed_color");

    /** Reported once per server, not once per item drawn. */
    private static volatile boolean reported;

    /** Whether the diagnostic line has been printed. */
    private static volatile boolean explained;

    private ItemComponents() {
    }

    @Override
    public void hideAdditionalTooltip(ItemStack item, TraitApplier.Reporter problems) {
        try {
            String route = hideThroughOldComponent(item) ? "hide_additional_tooltip"
                    : hideThroughTooltipDisplay(item) ? "tooltip_display"
                    : null;
            if (route != null) {
                explainOnce(problems, item, route);
                return;
            }
            reportOnce(problems, "this server knows neither the "
                    + "\"hide_additional_tooltip\" nor the \"tooltip_display\" "
                    + "component, so the block an item type writes for itself "
                    + "stays visible");
        } catch (Throwable unsupported) {
            // Never out of here. Whatever a later version does to this, an item
            // that cannot hide one line still has to draw: a tooltip is not
            // worth a menu that fails to open.
            reportOnce(problems, "the tooltip component could not be written, "
                    + "so the block an item type writes for itself stays "
                    + "visible: " + unsupported);
        }
    }

    /** The 1.21.4 way: a non-valued component, set and done. */
    private static boolean hideThroughOldComponent(ItemStack item) {
        DataComponentType type = Registry.DATA_COMPONENT_TYPE.get(HIDE_ADDITIONAL_TOOLTIP);
        if (!(type instanceof DataComponentType.NonValued nonValued)) {
            return false;
        }
        item.setData(nonValued);
        lastHidden = "[" + HIDE_ADDITIONAL_TOOLTIP.value() + "]";
        return true;
    }

    /**
     * The 1.21.5+ way, reached by reflection because its type cannot be named
     * against paper-api 1.21.4.
     *
     * <p>Hides every type-written component this server knows, whether or not
     * the item already carries it. The block being hidden — "Applies to: Armor"
     * and the like — is drawn by the client because the component is declared
     * for the item's <em>type</em>, and {@code getDataTypes()} does not report
     * those: hiding only the ones it does leaves the block on screen.
     *
     * <p>Merged onto whatever is already hidden. This is the component an
     * {@code ItemFlag} writes on these versions, so overwriting it undid every
     * flag the meta had set.
     */
    @SuppressWarnings("unchecked")
    private static boolean hideThroughTooltipDisplay(ItemStack item) throws Exception {
        if (!(Registry.DATA_COMPONENT_TYPE.get(TOOLTIP_DISPLAY)
                instanceof DataComponentType.Valued<?> type)) {
            return false;
        }
        Set<DataComponentType> hidden = hiddenComponents();
        if (hidden.isEmpty()) {
            return false;
        }

        // The builder and its methods are reached by name: TooltipDisplay does
        // not exist in paper-api 1.21.4, so none of this can be written as a
        // call.
        //
        // Looked up on the interfaces, never on builder.getClass(): the object
        // that comes back is Paper's own implementation, and a method found on
        // a non-public class cannot be invoked without setAccessible — which
        // this deliberately does not use. The interfaces are public API.
        Class<?> tooltipDisplay = Class.forName(
                "io.papermc.paper.datacomponent.item.TooltipDisplay");

        // Whatever is already hidden stays hidden. The flags write this same
        // component, so replacing it wholesale is how a file naming
        // HIDE_ENCHANTS next to hide-attributes got its enchantment lines back.
        Object present = item.getData(type);
        if (present != null) {
            hidden.addAll((Set<DataComponentType>) tooltipDisplay
                    .getMethod("hiddenComponents").invoke(present));
        }

        Object builder = tooltipDisplay.getMethod("tooltipDisplay").invoke(null);

        Class<?> builderType = Class.forName(
                "io.papermc.paper.datacomponent.item.TooltipDisplay$Builder");
        Object withHidden = builderType.getMethod("hiddenComponents", Set.class)
                .invoke(builder, hidden);

        // build() is declared on DataComponentBuilder, which Builder extends.
        Class<?> componentBuilder = Class.forName(
                "io.papermc.paper.datacomponent.DataComponentBuilder");
        Object built = componentBuilder.getMethod("build").invoke(withHidden);

        // Checked as far as the language allows: the registry answers with a
        // raw type, and the value was built by the component's own builder.
        item.setData((DataComponentType.Valued<Object>) type, built);
        lastHidden = names(hidden);
        return true;
    }

    /**
     * The components to hide: the type-written ones and the ones the flags
     * cover.
     *
     * <p>Not filtered against the item: a component declared for the item's
     * type does not appear in {@code getDataTypes()}, and that declared
     * component — not one written onto the stack — is what draws the block
     * being hidden. Commons hid them all by name; so does this.
     */
    private static Set<DataComponentType> hiddenComponents() {
        Set<DataComponentType> hidden = new LinkedHashSet<>();
        for (String name : WRITTEN_BY_TYPE) {
            add(hidden, name);
        }
        for (String name : HIDDEN_BY_FLAGS) {
            add(hidden, name);
        }
        return hidden;
    }

    /** Adds a component by name, if this server has one. */
    private static void add(Set<DataComponentType> hidden, String name) {
        DataComponentType type =
                Registry.DATA_COMPONENT_TYPE.get(NamespacedKey.minecraft(name));
        if (type != null) {
            hidden.add(type);
        }
    }

    /** Their names, for the diagnostic line. */
    private static String names(Set<DataComponentType> hidden) {
        List<String> named = new ArrayList<>(hidden.size());
        for (DataComponentType type : hidden) {
            named.add(String.valueOf(type.key().value()));
        }
        return named.toString();
    }

    /** The components just hidden, for the diagnostic line. */
    private static volatile String lastHidden = "[]";

    /**
     * Says which route was taken and what the item ended up carrying, once,
     * and only when asked for on the command line.
     *
     * <p>Off unless {@code -Dexylia.item.components=true} is set. It exists
     * because this took four attempts to get right and every wrong guess cost
     * a deploy: the write was happening and being wiped a moment later by a
     * later {@code setItemMeta}, which reports nothing and looks exactly like
     * a client that ignored the component. Reading back what survived tells
     * those two apart in one look.
     */
    private static void explainOnce(TraitApplier.Reporter problems, ItemStack item,
                                    String route) {
        if (!Boolean.getBoolean("exylia.item.components") || explained) {
            return;
        }
        explained = true;
        String survived;
        try {
            survived = String.valueOf(item.getDataTypes());
        } catch (Throwable unreadable) {
            survived = "unreadable: " + unreadable;
        }
        problems.found("hide-attributes", "written through \"" + route
                + "\", hiding " + lastHidden
                + "; the finished item carries " + survived);
    }

    /**
     * Says it once.
     *
     * <p>A version this library does not know how to reach is a fact about the
     * server, not an incident about the item: repeating it per rendered item
     * buries the log the first time a menu opens, which is exactly what the
     * first attempt did.
     */
    private static void reportOnce(TraitApplier.Reporter problems, String problem) {
        if (reported) {
            return;
        }
        reported = true;
        problems.found("hide-attributes", problem);
    }

    /** The component names this hides, by name. Tests only. */
    static List<String> hiddenNamesForTests() {
        List<String> names = new ArrayList<>(WRITTEN_BY_TYPE);
        names.addAll(HIDDEN_BY_FLAGS);
        return names;
    }

    /** Forgets what has been said. Tests only. */
    static void forgetReportedForTests() {
        reported = false;
        explained = false;
    }
}
