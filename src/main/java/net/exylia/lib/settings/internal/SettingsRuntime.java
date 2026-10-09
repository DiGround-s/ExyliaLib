package net.exylia.lib.settings.internal;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.RowChange;
import net.exylia.lib.settings.Setting;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.internal.CrossServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Every plugin's settings and every online player's values for them.
 *
 * <p>One {@link PluginStore} per plugin, kept in that plugin's own database the
 * way {@code NetworkCooldowns} is: the plugin's {@code database.yml} already says
 * where its players' data lives and which servers share it, so a network that
 * shares a plugin's database shares its settings.
 */
public final class SettingsRuntime {

    /** How long a failed read waits before it is tried again. */
    private static final long RETRY_TICKS = 100L;

    /** By plugin name; the load that owns each is compared by identity, see {@link #release}. */
    private static final Map<String, PluginStore> STORES = new ConcurrentHashMap<>();

    /** A category's card. */
    public record Category(String id, String icon, String name, List<String> description) {
    }

    private SettingsRuntime() {
    }

    /** A plugin's store, created on its first registration. */
    public static @NotNull PluginStore store(@NotNull Plugin plugin) {
        STORES.computeIfPresent(plugin.getName(), (name, open) -> open.plugin == plugin ? open : null);
        PluginStore[] created = {null};
        PluginStore store = STORES.computeIfAbsent(plugin.getName(), name -> created[0] = new PluginStore(plugin));
        if (created[0] != null) {
            try {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    store.open(online.getUniqueId());
                }
            } catch (RuntimeException noServer) {
                // No server behind this call — a test — and nobody to read.
            }
        }
        return store;
    }

    /** A plugin's store, when it registered anything. */
    public static @NotNull Optional<PluginStore> find(@NotNull Plugin plugin) {
        PluginStore store = STORES.get(plugin.getName());
        return store != null && store.plugin == plugin ? Optional.of(store) : Optional.empty();
    }

    /** A plugin's store by name, for a menu row. */
    public static @NotNull Optional<PluginStore> find(@NotNull String plugin) {
        return Optional.ofNullable(STORES.get(plugin));
    }

    /** Every store, by plugin name. */
    public static @NotNull List<PluginStore> stores() {
        List<PluginStore> all = new ArrayList<>(STORES.values());
        all.sort(Comparator.comparing(store -> store.plugin.getName(), String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    /** Reads a joining player's values in every plugin. Called by the library on join. */
    public static void joined(@NotNull UUID player) {
        for (PluginStore store : STORES.values()) {
            store.open(player);
        }
    }

    /** Forgets a leaving player. Called by the library on quit. */
    public static void left(@NotNull UUID player) {
        for (PluginStore store : STORES.values()) {
            store.sessions.remove(player);
        }
    }

    /** Forgets a plugin's settings when it is disabled — this load only. */
    public static void release(@NotNull Plugin plugin) {
        STORES.computeIfPresent(plugin.getName(), (name, open) -> open.plugin == plugin ? null : open);
    }

    /** Forgets every plugin, on shutdown. */
    public static void releaseAll() {
        STORES.clear();
    }

    /** One plugin's settings, and its players' values. */
    public static final class PluginStore {

        /** One player's values on this server. */
        static final class Session {
            volatile boolean loaded;
            /** By {@code key@server}; holds what was set here even when it is the default. */
            final Map<String, String> values = new ConcurrentHashMap<>();
            /** The last write, so the next one waits for it. */
            CompletableFuture<?> writes = CompletableFuture.completedFuture(null);
        }

        private final Plugin plugin;
        private final String namespace;
        private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
        /** Copied on write: read by every broadcast, changed only at registration. */
        private volatile Map<String, Setting> settings = Map.of();
        private volatile Map<String, Category> categories = Map.of();
        private volatile Repository<StoredSetting> rows;
        private volatile Supplier<String> serverId;

        PluginStore(Plugin plugin) {
            this.plugin = plugin;
            this.namespace = plugin.getName().toLowerCase(Locale.ROOT);
            this.serverId = () -> CrossServer.serverId(plugin);
        }

        /** The plugin. */
        public @NotNull Plugin plugin() {
            return plugin;
        }

        // -------------------------------------------------------------- registry

        /** Adds a setting, or replaces the one with the same key. */
        public synchronized void register(@NotNull Setting setting) {
            Map<String, Setting> copy = new LinkedHashMap<>(settings);
            copy.put(setting.key(), setting);
            settings = copy;
        }

        /** Removes a setting; what players stored for it stays in the table. */
        public synchronized void unregister(@NotNull String key) {
            Map<String, Setting> copy = new LinkedHashMap<>(settings);
            copy.remove(key);
            settings = copy;
        }

        /** Describes a category. */
        public synchronized void category(@NotNull Category category) {
            Map<String, Category> copy = new LinkedHashMap<>(categories);
            copy.put(category.id(), category);
            categories = copy;
        }

        /** A setting by key. */
        public @Nullable Setting setting(@NotNull String key) {
            return settings.get(key);
        }

        /** Every setting, in registration order. */
        public @NotNull List<Setting> settings() {
            return List.copyOf(settings.values());
        }

        /** The settings of one category, in registration order. */
        public @NotNull List<Setting> settings(@NotNull String category) {
            return settings.values().stream().filter(setting -> setting.category().equals(category)).toList();
        }

        /** The categories that hold settings, announcements left out, in the order they were first used. */
        public @NotNull List<Category> categories() {
            Map<String, Category> used = new LinkedHashMap<>();
            for (Setting setting : settings.values()) {
                if (setting.category().equals(Setting.ANNOUNCEMENTS)) continue;
                used.computeIfAbsent(setting.category(), id -> categories.getOrDefault(id,
                        new Category(id, setting.icon(), capitalise(id), List.of())));
            }
            return List.copyOf(used.values());
        }

        /** Whether the plugin registered any broadcast channel. */
        public boolean hasAnnouncements() {
            return settings.values().stream().anyMatch(setting -> setting.category().equals(Setting.ANNOUNCEMENTS));
        }

        // -------------------------------------------------------------- values

        /** Whether this server has read a player's values. */
        public boolean isLoaded(@NotNull UUID player) {
            Session session = sessions.get(player);
            return session != null && session.loaded;
        }

        /**
         * A player's value as this server knows it: the default until their
         * values are read, and for a setting kept in the plugin's own store.
         */
        public @NotNull String value(@NotNull UUID player, @NotNull Setting setting) {
            Session session = sessions.get(player);
            if (session == null) return setting.defaultValue();
            String value = session.values.get(slot(setting));
            return value == null ? setting.defaultValue() : value;
        }

        /**
         * Stores a value: memory now, the database after the player's previous
         * write. A value equal to the default deletes the row.
         *
         * @param value already checked against the setting
         */
        public void write(@NotNull UUID player, @NotNull Setting setting, @NotNull String value) {
            String server = setting.perServer() ? serverId.get() : "";
            String id = StoredSetting.id(namespace, player, setting.key(), server);
            Repository<StoredSetting> repository = rows();
            Supplier<CompletableFuture<?>> store = value.equals(setting.defaultValue())
                    ? () -> repository.delete(id)
                    : () -> repository.save(new StoredSetting(id, player, namespace, setting.key(), server, value,
                    System.currentTimeMillis()));
            Session session = sessions.get(player);
            if (session == null) {
                store.get();
                return;
            }
            synchronized (session) {
                session.values.put(setting.key() + '@' + server, value);
                session.writes = session.writes.handle((ignored, failure) -> null).thenCompose(ignored -> store.get());
            }
        }

        // -------------------------------------------------------------- lifecycle

        void open(UUID player) {
            Session session = new Session();
            sessions.put(player, session);
            load(player, session, false);
        }

        private void load(UUID player, Session session, boolean replace) {
            rows().where("player", player).where("namespace", namespace).find().whenComplete((found, failure) -> {
                if (sessions.get(player) != session) return;
                if (failure != null) {
                    plugin.getLogger().warning("Settings: could not read those of " + player
                            + ", trying again: " + failure.getMessage());
                    try {
                        Tasks.of(plugin).runAsyncLater(RETRY_TICKS, () -> {
                            if (sessions.get(player) == session) load(player, session, replace);
                        });
                    } catch (RuntimeException stopped) {
                        // The plugin is going away; the player keeps the defaults.
                    }
                    return;
                }
                Map<String, String> read = new HashMap<>();
                for (StoredSetting row : found) {
                    read.put(row.name() + '@' + row.server(), row.value());
                }
                synchronized (session) {
                    if (replace) {
                        // Another server wrote: what it holds now is the truth.
                        session.values.keySet().retainAll(read.keySet());
                        session.values.putAll(read);
                    } else {
                        // A value set while the read was out is newer than the row and wins.
                        read.forEach(session.values::putIfAbsent);
                    }
                }
                session.loaded = true;
            });
        }

        private void remoteChange(RowChange change) {
            if (change.wholeTable()) {
                sessions.forEach((player, session) -> load(player, session, true));
                return;
            }
            UUID player = StoredSetting.playerOf(change.id(), namespace);
            Session session = player == null ? null : sessions.get(player);
            if (session != null) load(player, session, true);
        }

        private Repository<StoredSetting> rows() {
            Repository<StoredSetting> current = rows;
            if (current != null) return current;
            synchronized (this) {
                if (rows == null) {
                    PluginDatabase database = Databases.of(plugin);
                    rows = database.repository(StoredSetting.class);
                    database.onRemoteChange(StoredSetting.class, this::remoteChange);
                }
                return rows;
            }
        }

        private String slot(Setting setting) {
            return setting.key() + '@' + (setting.perServer() ? serverId.get() : "");
        }

        // -------------------------------------------------------------- test seams

        /** For tests: this server's network id. */
        void serverIdForTests(String id) {
            serverId = () -> id;
        }

        /** For tests: the last write queued for a player. */
        CompletableFuture<?> writesForTests(UUID player) {
            Session session = sessions.get(player);
            return session == null ? CompletableFuture.completedFuture(null) : session.writes;
        }

        /** For tests: reads a player as a join would. */
        void openForTests(UUID player) {
            open(player);
        }
    }

    private static String capitalise(String id) {
        String spaced = id.replace('-', ' ').replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
