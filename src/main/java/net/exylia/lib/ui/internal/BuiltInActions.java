package net.exylia.lib.ui.internal;

import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSection;
import net.exylia.lib.ui.UiSession;

/**
 * The actions a menu can use without any plugin writing them.
 *
 * <p>Turning a page is not a feature of a plugin, and every menu in the
 * ecosystem already writes these:
 *
 * <pre>
 * actions:
 *   - 'next_page'
 * </pre>
 *
 * <p>Registered per plugin rather than globally, because an action id is
 * namespaced and a menu writes them unqualified. Registered once, when the
 * plugin first asks for its menus.
 *
 * <p>{@code next_page} with no argument moves the only list, which is what all
 * five hundred existing uses mean. A menu with several lists names the one it
 * means: {@code next_page players}.
 */
public final class BuiltInActions {

    private BuiltInActions() {
    }

    /** Registers them, leaving alone any id the plugin already took. */
    public static void register(PluginActions actions) {
        add(actions, "next_page", (session, argument) -> page(session, argument, 1));
        add(actions, "previous_page", (session, argument) -> page(session, argument, -1));
        add(actions, "back", (session, argument) -> session.runtime().back(session.viewer())
                ? ActionResult.success()
                : ActionResult.stop("nowhere to go back to"));
        add(actions, "close", (session, argument) -> {
            session.close();
            return ActionResult.success();
        });
        add(actions, "refresh", (session, argument) -> {
            session.refresh();
            return ActionResult.success();
        });
    }

    /** Moves a list, naming it or letting the menu's only one be understood. */
    private static ActionResult page(Session session, String argument, int step) {
        String section = argument.isEmpty() ? onlySectionOf(session) : argument;
        if (section == null) {
            return ActionResult.stop("this menu has no single list to page");
        }
        return session.turn(section, step)
                ? ActionResult.success()
                : ActionResult.stop("no such page");
    }

    private static void add(PluginActions actions, String id, Handler handler) {
        try {
            actions.registerSync(id, (context, arguments) -> {
                UiSession open = context.get(UiKeys.SESSION).orElse(null);
                if (!(open instanceof Session session)) {
                    // The action was written outside a menu. Not an error: an
                    // item in a hand can carry the same string.
                    return ActionResult.stop("not in a menu");
                }
                return handler.apply(session, arguments.string(0, ""));
            });
        } catch (IllegalStateException alreadyTaken) {
            // A plugin registered its own action by this name. Theirs wins:
            // ours is a convenience, and silently replacing a plugin's handler
            // would be far worse than not having the convenience.
        }
    }

    /**
     * The list a menu means when it does not say.
     *
     * <p>A menu with one list is the ordinary case, and naming it in every
     * button would be ceremony. A menu with several has to say.
     */
    private static String onlySectionOf(Session session) {
        UiSection only = session.definition().section();
        return only == null ? null : only.id();
    }

    @FunctionalInterface
    private interface Handler {
        ActionResult apply(Session session, String argument);
    }
}
