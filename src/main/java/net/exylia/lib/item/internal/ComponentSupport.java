package net.exylia.lib.item.internal;

/**
 * Whether this server has data components, asked without naming one.
 *
 * <p>The gate in front of {@link Components}. Deliberately mentions no Paper
 * type — not in a field, not in a signature, not in a method body — so that on
 * Spigot the class that does is never loaded, and the library starts.
 *
 * <p>Asked once and remembered: a class lookup is cheap but not free, and this
 * sits on the path of every consumable item that is drawn.
 */
final class ComponentSupport {

    private static final boolean AVAILABLE = detect();

    private ComponentSupport() {
    }

    /** Returns whether data components can be written. */
    static boolean available() {
        return AVAILABLE;
    }

    private static boolean detect() {
        try {
            Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            return true;
        } catch (ClassNotFoundException spigot) {
            return false;
        }
    }
}
