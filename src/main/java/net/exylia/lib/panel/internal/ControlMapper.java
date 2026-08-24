package net.exylia.lib.panel.internal;

import net.exylia.lib.config.Schema;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Which control a component gets, decided from its declared type alone.
 *
 * <p>This is the class the whole module rests on: because the answer comes from
 * a {@link Schema} and nothing else, the effects editor is the settings panel
 * pointed at {@code EffectConfig}, with no code that knows what an effect is.
 * A record this library has never seen edits exactly as well as one it ships.
 *
 * <h2>A table, not control flow</h2>
 * The mapping is a lookup keyed by declared type, plus three structural
 * questions — is it an enum, is it a list, is it a nested record. Adding a
 * supported type is one entry in {@link #BY_TYPE}; there is deliberately no
 * {@code switch} over a domain type anywhere, because the first one would be the
 * thing that made a second panel necessary.
 *
 * <p>Chosen from the <em>declared</em> type, never from the value: a {@code long}
 * holding zero is still a number control, and a null {@code String} is still
 * text. Deciding from the value would make a screen change shape as it is
 * edited.
 *
 * <h2>Threads</h2>
 * Pure and any-thread. No Bukkit API, no state, nothing cached.
 */
@ApiStatus.Internal
public final class ControlMapper {

    /**
     * Declared type to control.
     *
     * <p>Boxed and primitive both listed rather than unboxed first: a component
     * declared {@code Integer} may be null and one declared {@code int} may not,
     * and collapsing them would hide that from anything that reads this table
     * later.
     */
    private static final Map<Class<?>, ControlKind> BY_TYPE = Map.ofEntries(
            Map.entry(int.class, ControlKind.INTEGER),
            Map.entry(Integer.class, ControlKind.INTEGER),
            Map.entry(long.class, ControlKind.INTEGER),
            Map.entry(Long.class, ControlKind.INTEGER),
            Map.entry(short.class, ControlKind.INTEGER),
            Map.entry(Short.class, ControlKind.INTEGER),
            Map.entry(byte.class, ControlKind.INTEGER),
            Map.entry(Byte.class, ControlKind.INTEGER),
            Map.entry(double.class, ControlKind.DECIMAL),
            Map.entry(Double.class, ControlKind.DECIMAL),
            Map.entry(float.class, ControlKind.DECIMAL),
            Map.entry(Float.class, ControlKind.DECIMAL),
            Map.entry(boolean.class, ControlKind.TOGGLE),
            Map.entry(Boolean.class, ControlKind.TOGGLE),
            Map.entry(String.class, ControlKind.TEXT));

    private ControlMapper() {
        throw new AssertionError("No instances.");
    }

    /**
     * The control for one component.
     *
     * <p>Answers {@link ControlKind#UNSUPPORTED} rather than throwing for a type
     * with no control. That is not politeness: a component nobody can edit is
     * still a component whose value must survive a save, and refusing the whole
     * screen over one field would lose the other twelve.
     *
     * @param field the component, as the schema projects it
     * @return its control kind; never {@code null}
     */
    public static @NotNull ControlKind kindOf(@NotNull Schema.Field field) {
        // Structure first: a nested record and a list are recognised by shape
        // rather than by being listed, since neither has a fixed class.
        if (field.isSection()) {
            return ControlKind.SUB_PANEL;
        }
        Class<?> type = field.type();
        if (type.isEnum()) {
            return ControlKind.CHOICE;
        }
        if (List.class.isAssignableFrom(type)) {
            return ControlKind.LIST;
        }
        return BY_TYPE.getOrDefault(type, ControlKind.UNSUPPORTED);
    }

    /**
     * Whether a control can be edited at all.
     *
     * <p>Asked before a click is acted on, so an unsupported control is
     * genuinely read-only rather than merely undrawn.
     *
     * @param kind the control
     * @return {@code false} for {@link ControlKind#UNSUPPORTED}
     */
    public static boolean isEditable(@NotNull ControlKind kind) {
        return kind != ControlKind.UNSUPPORTED;
    }
}
