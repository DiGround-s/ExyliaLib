package net.exylia.lib.command;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point of the command module.
 *
 * <p>Configured commands are not a menu feature. Menus use them, but so do
 * items, rewards and anything else that lets an administrator write
 * {@code "console: give %player_name% diamond 1"} in a file. This module owns
 * that syntax once, so those consumers agree on what a line means.
 *
 * <pre>{@code
 * PluginCommands commands = Commands.of(this);
 * List<CommandLine> onClick = Commands.fromConfig(section, "commands");
 * commands.run(onClick, player);
 * }</pre>
 *
 * <p>Instances are cached per plugin and released when it is disabled, so
 * calling {@link #of(Plugin)} repeatedly is cheap.
 *
 * @since 1.22.0
 */
public final class Commands {

    private static final Map<String, PluginCommands> BY_PLUGIN = new ConcurrentHashMap<>();

    private Commands() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns the command runner for a plugin.
     *
     * @param plugin the plugin the commands belong to
     * @return its runner, never {@code null}
     */
    public static @NotNull PluginCommands of(@NotNull Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), ignored -> new PluginCommands(plugin));
    }

    /**
     * Compiles the commands under a key, accepting a single string or a list.
     *
     * <p>Both spellings appear in existing files, and a single command written
     * without a list is the common case.
     *
     * @param section the section holding the key
     * @param key     the key to read
     * @return the compiled commands, empty when the key is absent
     * @throws IllegalArgumentException if a line has no command in it
     */
    public static @NotNull List<CommandLine> fromConfig(@NotNull ConfigurationSection section,
                                                        @NotNull String key) {
        if (!section.contains(key)) {
            return List.of();
        }
        if (section.isList(key)) {
            return compileAll(section.getStringList(key));
        }
        String single = section.getString(key);
        return single == null || single.isBlank()
                ? List.of()
                : List.of(CommandLine.compile(single));
    }

    /**
     * Compiles a list of configured lines.
     *
     * @param lines the lines as written
     * @return the compiled commands
     * @throws IllegalArgumentException if a line has no command in it
     */
    public static @NotNull List<CommandLine> compileAll(@NotNull List<String> lines) {
        return lines.stream().map(CommandLine::compile).toList();
    }

    /** Forgets a plugin's runner; lifecycle calls this. */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
    }

    /** Forgets every runner. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
    }
}
