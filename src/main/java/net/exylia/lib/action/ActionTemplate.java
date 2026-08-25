package net.exylia.lib.action;

import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Template;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * An action whose id or arguments are only known when it is shown.
 *
 * <p>Menus build action strings from placeholders — a members list where each
 * row's kick button carries that member's id, or resolves to nothing at all
 * for a row that cannot be kicked:
 *
 * <pre>{@code
 * // "practice:party_kick %member_id%", or "none" when the viewer is not the leader
 * ActionTemplate kick = actions.template(config.getString("kick-action"));
 * kick.resolve(viewer, Map.of("member_id", member.id())).execute(context);
 * }</pre>
 *
 * <p>A template that turns out to contain no placeholders is compiled once, at
 * load, and {@link #resolve} then returns that same call: the common case pays
 * nothing for the feature. Only a genuinely dynamic string is rendered and
 * looked up per use.
 *
 * @since 1.21.0
 */
public final class ActionTemplate {

    /** Set when the string is static, in which case nothing is rendered later. */
    private final ActionCall fixed;

    private final Template template;
    private final String namespace;
    private final String raw;

    ActionTemplate(String raw, String namespace) {
        this.raw = raw == null ? "" : raw;
        this.namespace = namespace;
        if (!Placeholders.isDynamic(this.raw)) {
            // Resolved now, so a click costs a field read. This also means a
            // typo in a static action is reported when the menu loads rather
            // than when somebody presses it.
            this.fixed = ActionCompiler.compile(this.raw, namespace);
            this.template = null;
        } else {
            this.fixed = null;
            this.template = Placeholders.compile(this.raw);
        }
    }

    /** Returns whether this template has to be rendered before it can be used. */
    public boolean isDynamic() {
        return fixed == null;
    }

    /** The string this template was built from. */
    public @NotNull String raw() {
        return raw;
    }

    /**
     * Resolves the action for a viewer.
     *
     * @param viewer who the placeholders are rendered for
     * @return the compiled call
     * @throws IllegalArgumentException if the rendered action does not exist
     */
    public @NotNull ActionCall resolve(Player viewer) {
        return fixed != null ? fixed : ActionCompiler.compile(template.render(viewer), namespace);
    }

    /**
     * Resolves the action with extra values the placeholders can read.
     *
     * <p>The values win over a registered placeholder of the same name, so a
     * row's button means the row: a spectator menu listing players draws each
     * head from the row's {@code %player_name%} and its button has to teleport
     * to that player rather than to whoever is looking at the menu.
     *
     * @param viewer who the placeholders are rendered for
     * @param data   values for the resolvers, such as the row being drawn
     * @return the compiled call
     * @throws IllegalArgumentException if the rendered action does not exist
     */
    public @NotNull ActionCall resolve(Player viewer, @NotNull Map<String, Object> data) {
        return fixed != null ? fixed : ActionCompiler.compile(
                Placeholders.renderValuesFirst(template, viewer, data), namespace);
    }

    /**
     * Resolves without failing on an unknown action.
     *
     * <p>For a menu drawing a row whose action came from data it does not
     * control: an id that no longer exists should leave a dead button, not
     * stop the menu from opening.
     *
     * @param viewer who the placeholders are rendered for
     * @param data   values for the resolvers
     * @return the compiled call, or a no-op when it cannot be resolved
     */
    public @NotNull ActionCall resolveOrNoop(Player viewer, @NotNull Map<String, Object> data) {
        try {
            return resolve(viewer, data);
        } catch (IllegalArgumentException unknown) {
            return ActionCompiler.compile("none", namespace);
        }
    }

    @Override
    public String toString() {
        return "ActionTemplate[" + raw + (isDynamic() ? ", dynamic]" : ", fixed]");
    }
}
