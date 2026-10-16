package net.exylia.lib.web.internal;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.web.Subject;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * A registered subject. Setters are volatile writes: a registration is read by the command
 * thread and written by the plugin's enable, and neither needs to see the other mid-chain.
 */
final class SubjectImpl implements Subject {

    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,48}");

    private final Plugin plugin;
    private final String id;
    private final ConfigFile<?> file;
    private final String kind;
    private volatile String title;
    private volatile String permission;
    private final List<String> sensitive = new CopyOnWriteArrayList<>();
    private volatile Runnable applied = () -> { };

    SubjectImpl(Plugin plugin, String id, ConfigFile<?> file, String kind) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not a subject id: '" + id
                    + "' (letters, digits, - and _, up to 48)");
        }
        this.plugin = plugin;
        this.id = id;
        this.file = file;
        this.kind = kind == null || kind.isBlank() ? GENERIC : kind.trim().toLowerCase(Locale.ROOT);
        this.title = Character.toUpperCase(id.charAt(0)) + id.substring(1).replace('_', ' ').replace('-', ' ');
    }

    @Override
    public @NotNull Plugin plugin() {
        return plugin;
    }

    @Override
    public @NotNull String id() {
        return id;
    }

    @Override
    public @NotNull String key() {
        return plugin.getName() + "/" + id;
    }

    @Override
    public @NotNull ConfigFile<?> file() {
        return file;
    }

    @Override
    public @NotNull String kind() {
        return kind;
    }

    @Override
    public @NotNull String title() {
        return title;
    }

    @Override
    public @Nullable String permission() {
        return permission;
    }

    @Override
    public @NotNull List<String> sensitive() {
        return List.copyOf(sensitive);
    }

    @Override
    public @NotNull Subject title(@NotNull String title) {
        this.title = title;
        return this;
    }

    @Override
    public @NotNull Subject permission(@Nullable String permission) {
        this.permission = permission == null || permission.isBlank() ? null : permission;
        return this;
    }

    @Override
    public @NotNull Subject sensitive(@NotNull String... paths) {
        for (String path : paths) {
            if (path != null && !path.isBlank() && !sensitive.contains(path)) {
                sensitive.add(path);
            }
        }
        return this;
    }

    @Override
    public @NotNull Subject onApplied(@NotNull Runnable listener) {
        this.applied = listener;
        return this;
    }

    Runnable applied() {
        return applied;
    }

    /** The record the file is declared as. */
    Class<?> type() {
        return file.schema().type();
    }

    /** Whether this sender may see and change the subject. The console always may. */
    boolean visibleTo(CommandSender sender) {
        String node = permission;
        return node == null || sender.hasPermission(node);
    }
}
