package net.exylia.lib.panel;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.ConfigIssue;
import net.exylia.lib.config.Configs;
import net.exylia.lib.config.Schema;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A config file that lives in memory and counts its writes.
 *
 * <p>{@link ConfigFile#update} is the panel's only write path, so a double here
 * is enough to answer the two questions the settings panel must answer: was
 * {@code update} called, and what record did it receive. Nothing is on disk, so
 * "cancel wrote nothing" is a count rather than a file comparison — which is
 * both stricter and possible without a server.
 *
 * <p>The schema is projected by the real config module from the same record, so
 * what a panel draws from is the production projection and not a stand-in.
 */
final class FakeConfigFile<T extends Record> implements ConfigFile<T> {

    private final String name;
    private final Schema schema;
    private final AtomicInteger updates = new AtomicInteger();
    private final List<Consumer<T>> listeners = new ArrayList<>();

    private volatile T value;

    private FakeConfigFile(String name, Schema schema, T value) {
        this.name = name;
        this.schema = schema;
        this.value = value;
    }

    /**
     * A file holding a record, with the real projection of its type.
     *
     * @param plugin  the owner, used only to project the schema
     * @param name    what the file would have been called
     * @param initial the record it holds
     */
    static <T extends Record> FakeConfigFile<T> of(Plugin plugin, String name, T initial) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) initial.getClass();
        Schema schema = Configs.define(plugin, name, type).load().schema();
        return new FakeConfigFile<>(name, schema, initial);
    }

    @Override
    public @NotNull T get() {
        return value;
    }

    @Override
    public @NotNull List<ConfigIssue> reload() {
        return List.of();
    }

    @Override
    public void onReload(@NotNull Consumer<T> listener) {
        listeners.add(listener);
    }

    @Override
    public void save() {
        throw new AssertionError("The panel must persist through update, never through save.");
    }

    @Override
    public void update(@NotNull UnaryOperator<T> change) {
        value = change.apply(value);
        updates.incrementAndGet();
        for (Consumer<T> listener : listeners) {
            listener.accept(value);
        }
    }

    @Override
    public @NotNull List<ConfigIssue> issues() {
        return List.of();
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull Schema schema() {
        return schema;
    }

    /** How many times the panel wrote. Zero is what cancel must leave behind. */
    int updates() {
        return updates.get();
    }
}
