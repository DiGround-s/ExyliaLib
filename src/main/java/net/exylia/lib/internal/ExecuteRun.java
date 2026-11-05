package net.exylia.lib.internal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Lets every Lamp command run under {@code execute as <player> run <command>}.
 *
 * <p>Lamp's brigadier action reads {@link CommandContext#getInput()}, which is the whole line: under
 * {@code execute ... run} it finds {@code execute} where its own name should be, tells the executor
 * "Expected supplydrop, found execute." and runs nothing. Command blocks and NPC plugins call
 * player commands exactly this way. The command's own text starts at its context's range, so once
 * every plugin has registered its commands, each Lamp action in the server's tree is wrapped to be
 * handed that text. Lamp is loaded once, by this library, so this covers every plugin; Lamp 4.0.1
 * still reads the whole line.
 */
final class ExecuteRun {

    private ExecuteRun() {
        throw new AssertionError("No instances.");
    }

    /** Wraps Lamp's actions each time the server builds its command tree, after every plugin added to it. */
    static void install(JavaPlugin lib) {
        Field command = commandField();
        if (command == null) return;
        try {
            lib.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS.newHandler(event -> {
                try {
                    wrap(serverRoot(event.registrar().getDispatcher().getRoot()), command, ExecuteRun::isLamp);
                } catch (RuntimeException failed) {
                    lib.getLogger().warning("Lamp commands will not run under /execute: " + failed);
                }
            }).monitor());
        } catch (RuntimeException | LinkageError unsupported) {
            lib.getLogger().warning("Lamp commands will not run under /execute: " + unsupported);
        }
    }

    /**
     * Paper copies each plugin node into the server's own dispatcher, which is what runs: that tree when
     * it can be reached, the plugin-facing one otherwise.
     */
    private static CommandNode<?> serverRoot(CommandNode<?> pluginRoot) {
        try {
            Method dispatcher = pluginRoot.getClass().getMethod("getDispatcher");
            dispatcher.setAccessible(true);
            if (dispatcher.invoke(pluginRoot) instanceof CommandDispatcher<?> server) return server.getRoot();
        } catch (ReflectiveOperationException | RuntimeException notPaper) {
            // Not Paper's mirror: its own nodes are the ones that run.
        }
        return pluginRoot;
    }

    private static Field commandField() {
        try {
            Field field = CommandNode.class.getDeclaredField("command");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    /** An action Lamp's brigadier bridge built: the unrelocated copy this library loads. */
    static boolean isLamp(Command<?> action) {
        return action.getClass().getName().startsWith("revxrsal.commands.brigadier.");
    }

    /**
     * Wraps every matching action under {@code root} to see only its own command's text.
     *
     * @return how many were wrapped
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int wrap(CommandNode<?> root, Field commandField, Predicate<Command<?>> matches) {
        int wrapped = 0;
        Set<CommandNode<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CommandNode<?>> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            CommandNode node = todo.pop();
            if (!seen.add(node)) continue;
            Command action = node.getCommand();
            if (action != null && matches.test(action)) {
                try {
                    commandField.set(node, (Command) context -> action.run(own((CommandContext) context)));
                    wrapped++;
                } catch (IllegalAccessException locked) {
                    return wrapped;
                }
            }
            todo.addAll(node.getChildren());
        }
        return wrapped;
    }

    /** The context as its own command sees it: the line from where that command starts. */
    static <S> CommandContext<S> own(CommandContext<S> context) {
        int start = context.getRange().getStart();
        if (start <= 0) return context;
        String input = context.getInput().substring(start);
        return new CommandContext<>(context.getSource(), input, Map.of(), context.getCommand(), context.getRootNode(),
                context.getNodes(), StringRange.between(0, input.length()), context.getChild(),
                context.getRedirectModifier(), context.isForked());
    }
}
