package net.exylia.lib.command;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Commands whose names are not known until a plugin reads its data: a shop
 * opened with {@code /blocks}, a currency with {@code /gems}.
 *
 * <p>A Lamp command is declared by annotation, so its name is fixed at compile
 * time. These go straight into the server's command map instead, and the whole
 * set is replaced every time the data changes:
 *
 * <pre>{@code
 * RuntimeCommands.of(this).install(shops.stream()
 *         .map(shop -> RuntimeCommand.of(shop.command(), (sender, args) -> open(sender, shop.id()))
 *                 .withAliases(shop.aliases())
 *                 .withPermission("myplugin.shop"))
 *         .toList());
 * }</pre>
 *
 * <p>{@link #install} may be called from any thread: the command map is only
 * touched on the global thread. Players are sent a new command tree only when
 * the names changed. A name another plugin already holds still works under the
 * plugin's prefix ({@code /myplugin:blocks}) and is reported once in the console.
 * Everything installed is taken down when the plugin is disabled.
 *
 * @since 1.333.0
 */
public final class RuntimeCommands {

    private static final Map<String, RuntimeCommands> BY_PLUGIN = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final List<Command> registered = new ArrayList<>();
    /** Every name installed last, so a change that renames nothing sends no command tree. */
    private List<List<String>> names = List.of();

    private RuntimeCommands(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns the runtime commands of a plugin.
     *
     * @param plugin the plugin the commands belong to
     * @return its set, never {@code null}
     */
    public static @NotNull RuntimeCommands of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), ignored -> new RuntimeCommands(plugin));
    }

    /**
     * Replaces every command this plugin installed with {@code commands}.
     *
     * @param commands the commands wanted now; empty takes them all down
     */
    public void install(@NotNull Collection<RuntimeCommand> commands) {
        List<RuntimeCommand> wanted = List.copyOf(commands);
        Tasks.of(plugin).execute(() -> apply(wanted));
    }

    /** Takes every command this plugin installed down. */
    public void uninstall() {
        install(List.of());
    }

    /** The names installed, each command's name first and its aliases after it. */
    public @NotNull List<List<String>> installed() {
        return names;
    }

    private synchronized void apply(List<RuntimeCommand> wanted) {
        unregisterAll();
        String prefix = plugin.getName().toLowerCase(Locale.ROOT);
        List<List<String>> now = new ArrayList<>();
        for (RuntimeCommand command : wanted) {
            Entry entry = new Entry(command);
            if (!Bukkit.getCommandMap().register(prefix, entry)) {
                Debug.of(plugin).warn("The command /" + command.name() + " is taken by another plugin; it answers as /"
                        + prefix + ":" + command.name() + ".");
            }
            registered.add(entry);
            List<String> key = new ArrayList<>(command.aliases());
            key.add(0, command.name());
            now.add(List.copyOf(key));
        }
        if (now.equals(names)) return;
        names = List.copyOf(now);
        for (Player online : Bukkit.getOnlinePlayers()) online.updateCommands();
    }

    private void unregisterAll() {
        for (Command old : registered) {
            old.unregister(Bukkit.getCommandMap());
            Bukkit.getCommandMap().getKnownCommands().values().removeIf(known -> known == old);
        }
        registered.clear();
    }

    /** Takes a plugin's commands down and forgets them; lifecycle calls this. */
    public static void release(@NotNull String pluginName) {
        RuntimeCommands commands = BY_PLUGIN.remove(pluginName);
        if (commands == null) return;
        synchronized (commands) {
            commands.unregisterAll();
            commands.names = List.of();
        }
    }

    /** Takes every plugin's commands down. */
    public static void releaseAll() {
        List.copyOf(BY_PLUGIN.keySet()).forEach(RuntimeCommands::release);
    }

    /** One installed command, answering through its {@link RuntimeCommand}. */
    private static final class Entry extends Command {

        private final RuntimeCommand command;

        Entry(RuntimeCommand command) {
            super(command.name(), command.description(), "/" + command.name(), command.aliases());
            this.command = command;
            if (!command.permission().isEmpty()) setPermission(command.permission());
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
            if (!testPermission(sender)) return true;
            command.executor().accept(sender, args);
            return true;
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias,
                                                 @NotNull String[] args) {
            if (!command.permission().isEmpty() && !sender.hasPermission(command.permission())) return List.of();
            List<String> offered = command.completer().apply(sender, args);
            return offered == null ? List.of() : offered;
        }
    }
}
