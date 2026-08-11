package net.exylia.lib.placeholder;

import net.exylia.lib.placeholder.internal.CompiledTemplate;
import net.exylia.lib.placeholder.internal.PapiBridge;
import net.exylia.lib.placeholder.internal.Loggers;
import net.exylia.lib.placeholder.internal.Registry;
import net.exylia.lib.placeholder.internal.TemplateCache;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Entry point of the placeholder module.
 *
 * <h2>Registering</h2>
 * Placeholders are registered in groups that share a prefix, so the prefix is
 * written once and the whole group disappears together when the plugin unloads:
 *
 * <pre>{@code
 * Placeholders.group(this, "clan")
 *         .add("name", r -> clans.of(r.requireViewer()).name())
 *         .add("members", r -> clans.of(r.requireViewer()).size())
 *         .add("top", r -> clans.leaderboard().at(r.arg(0, 1)))
 *         .register();
 * }</pre>
 *
 * That declares {@code %clan_name%}, {@code %clan_members%} and
 * {@code %clan_top_3%}. There is one resolver type and one way to register, so
 * nothing has to be declared twice or classified up front.
 *
 * <h2>Using</h2>
 * <pre>{@code
 * String text = Placeholders.apply("Welcome %player_name%", player);
 * }</pre>
 *
 * For text that is rendered repeatedly, such as a scoreboard line, compile it
 * once and keep it:
 *
 * <pre>{@code
 * Template line = Placeholders.compile("Coins: %eco_balance:comma%");
 * String rendered = line.render(player);   // every tick
 * }</pre>
 *
 * <h2>Syntax</h2>
 * <table border="1">
 *   <caption>Placeholder syntax</caption>
 *   <tr><th>Form</th><th>Meaning</th></tr>
 *   <tr><td>{@code %eco_balance%}</td><td>plain</td></tr>
 *   <tr><td>{@code %clan_top_3%}</td><td>argument {@code 3}</td></tr>
 *   <tr><td>{@code %eco_balance:comma%}</td><td>formatted as {@code 1,250}</td></tr>
 *   <tr><td>{@code %clan_name|No clan%}</td><td>fallback when there is no value</td></tr>
 *   <tr><td>{@code %%}</td><td>a literal percent sign</td></tr>
 * </table>
 *
 * <p>Formats: {@code comma}, {@code compact}, {@code percent}, {@code upper},
 * {@code lower}, {@code yesno}, {@code time}, {@code fixed1}, {@code fixed2}, or
 * any {@link java.text.DecimalFormat} pattern such as {@code #,##0.00}.
 *
 * <h2>PlaceholderAPI</h2>
 * Everything registered here is exposed to PlaceholderAPI automatically, so
 * other plugins can read Exylia values without anyone writing an expansion. In
 * the other direction, {@code %papi_...%} placeholders inside Exylia text are
 * resolved through PlaceholderAPI when it is installed. If it is not installed,
 * nothing breaks and only Exylia placeholders resolve.
 *
 * <h2>Threading</h2>
 * Registering and rendering are safe from any thread. Whether a specific
 * resolver is safe to run off the main thread is up to that resolver, which is
 * what {@link Group#async()} declares.
 *
 * @since 1.3.0
 */
public final class Placeholders {

    private Placeholders() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------------
    // Registering
    // ------------------------------------------------------------------

    /**
     * Starts a group of placeholders sharing a prefix.
     *
     * @param plugin the plugin that owns them
     * @param prefix the shared prefix, such as {@code clan}; every placeholder
     *               in the group is named {@code prefix_something}
     * @return a builder; call {@link Group#register()} when done
     */
    public static @NotNull Group group(@NotNull Plugin plugin, @NotNull String prefix) {
        return new Group(plugin, prefix);
    }

    /**
     * Registers a single placeholder without a group.
     *
     * <p>Use a {@link #group} when there is more than one.
     *
     * @param plugin   the plugin that owns it
     * @param name     the full name, as it appears between percent signs
     * @param resolver produces the value
     */
    public static void register(@NotNull Plugin plugin, @NotNull String name, @NotNull Resolver resolver) {
        Registry.register(name.toLowerCase(Locale.ROOT),
                new Registry.Entry(resolver, plugin.getName(), false, ""));
        PapiBridge.refresh(plugin);
    }

    /**
     * Removes everything a plugin registered.
     *
     * <p>Called automatically when the plugin is disabled.
     *
     * @param pluginName the plugin's name
     * @return how many placeholders were removed
     */
    public static int unregisterAll(@NotNull String pluginName) {
        PapiBridge.release(pluginName);
        return Registry.unregisterAll(pluginName);
    }

    // ------------------------------------------------------------------
    // Using
    // ------------------------------------------------------------------

    /**
     * Fills in the placeholders in a piece of text.
     *
     * <p>The compiled form is cached, so sending the same message repeatedly
     * does not re-analyse it.
     *
     * @param text   the text to fill in
     * @param viewer who reads it, may be {@code null}
     * @return the finished text
     */
    public static @NotNull String apply(@NotNull String text, @Nullable Player viewer) {
        return TemplateCache.get(text, Loggers.get()).render(viewer);
    }

    /**
     * Fills in placeholders, with extra values attached.
     *
     * @param text   the text to fill in
     * @param viewer who reads it, may be {@code null}
     * @param data   values resolvers can read with {@link Request#get}
     * @return the finished text
     */
    public static @NotNull String apply(@NotNull String text, @Nullable Player viewer,
                                        @NotNull Map<String, Object> data) {
        return TemplateCache.get(text, Loggers.get()).render(viewer, data);
    }

    /**
     * Fills in placeholders for text about somebody other than the reader.
     *
     * <p>Named apart from {@link #apply} so a call with a {@code null} player is
     * never ambiguous.
     *
     * @param text   the text to fill in
     * @param viewer who reads it
     * @param target who the text is about
     * @return the finished text
     */
    public static @NotNull String applyRelational(@NotNull String text, @Nullable Player viewer,
                                                  @Nullable OfflinePlayer target) {
        return TemplateCache.get(text, Loggers.get()).render(viewer, target);
    }

    /**
     * Fills in placeholders for nobody in particular, such as a console message.
     *
     * @param text the text to fill in
     * @return the finished text
     */
    public static @NotNull String apply(@NotNull String text) {
        return TemplateCache.get(text, Loggers.get()).render();
    }

    /**
     * Compiles text into a reusable template.
     *
     * <p>For lines that will be rendered many times. The result is not shared
     * through the internal cache, so holding one does not evict anything.
     *
     * @param text the text to compile
     * @return the template
     */
    public static @NotNull Template compile(@NotNull String text) {
        return TemplateCache.compile(text, Loggers.get());
    }

    // ------------------------------------------------------------------
    // Inspecting
    // ------------------------------------------------------------------

    /**
     * Returns whether text contains anything that needs filling in.
     *
     * <p>Cheap: a template with no placeholders knows it, so this never scans
     * the same string twice.
     *
     * @param text the text to check
     * @return {@code true} when rendering could change the text
     */
    public static boolean isDynamic(@NotNull String text) {
        return TemplateCache.get(text, Loggers.get()).isDynamic();
    }

    /**
     * Returns whether a placeholder name is registered.
     *
     * @param name the name, without percent signs
     * @return {@code true} when something will resolve it
     */
    public static boolean has(@NotNull String name) {
        return Registry.has(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns every registered placeholder name.
     *
     * @return an immutable set
     */
    public static @NotNull Set<String> names() {
        return Registry.names();
    }

    /**
     * Returns the placeholders in a piece of text that nothing can resolve.
     *
     * <p>For a diagnostics command: it tells a server owner which placeholder in
     * their config is misspelled.
     *
     * @param text the text to check
     * @return the unresolved names, empty when everything resolves
     */
    public static @NotNull List<String> unresolved(@NotNull String text) {
        return ((CompiledTemplate) TemplateCache.get(text, Loggers.get())).unresolved();
    }

    /**
     * Resolves the placeholders in text and returns them paired with the text
     * they were written as.
     *
     * <p>For callers that substitute into something other than a string. The
     * text module uses this to insert values into an already parsed component,
     * which is what lets one parsed component be shared by every player while
     * the values still differ per player.
     *
     * @param text   the text to inspect
     * @param viewer who to resolve for
     * @return alternating original placeholder and resolved value
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static @NotNull List<String> resolveInto(@NotNull String text, @Nullable Player viewer) {
        CompiledTemplate template = TemplateCache.get(text, Loggers.get());
        if (!template.isDynamic()) {
            return List.of();
        }
        return template.resolvePairs(new Request(viewer, viewer, List.of(), Map.of()));
    }

    /**
     * Resolves a compiled template's placeholders and returns them paired with
     * the text they were written as.
     *
     * <p>The {@link #resolveInto} of a template a caller already holds, and
     * with a full request rather than a bare viewer, so extra data reaches the
     * resolvers. The scoreboard module uses this to substitute values into a
     * component it parsed once, instead of parsing the resolved string again on
     * every change.
     *
     * @param template a template from {@link #compile}
     * @param request  who is asking, about whom, and with what data
     * @return alternating original placeholder and resolved value
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static @NotNull List<String> resolvePairs(@NotNull Template template,
                                                     @NotNull Request request) {
        if (!(template instanceof CompiledTemplate compiled)) {
            return List.of();
        }
        return compiled.resolvePairs(request);
    }

    /** Drops every registration. Called by ExyliaLib on shutdown. */
    public static void releaseAll() {
        PapiBridge.releaseAll();
        Registry.clear();
    }

    // ------------------------------------------------------------------
    // Group builder
    // ------------------------------------------------------------------

    /**
     * Collects placeholders that share a prefix and registers them together.
     *
     * @since 1.3.0
     */
    public static final class Group {

        private final Plugin plugin;
        private final String prefix;
        private final Map<String, Registry.Entry> pending = new java.util.LinkedHashMap<>();
        private boolean async;
        private String description = "";

        Group(Plugin plugin, String prefix) {
            this.plugin = plugin;
            this.prefix = prefix.toLowerCase(Locale.ROOT);
        }

        /**
         * Marks the placeholders added after this call as safe off the main
         * thread.
         *
         * <p>Only true when the resolver reads nothing but its own thread-safe
         * state. A resolver that touches the Bukkit API is not async safe, and
         * saying otherwise will eventually crash a server.
         *
         * @return this builder
         */
        public @NotNull Group async() {
            this.async = true;
            return this;
        }

        /**
         * Describes the placeholders added after this call, for diagnostics.
         *
         * @param description what they mean
         * @return this builder
         */
        public @NotNull Group describe(@NotNull String description) {
            this.description = description;
            return this;
        }

        /**
         * Adds a placeholder to the group.
         *
         * <p>The final name is the group prefix, an underscore, and this name.
         * Use {@code ""} to register the bare prefix itself.
         *
         * @param name     the part after the prefix
         * @param resolver produces the value
         * @return this builder
         */
        public @NotNull Group add(@NotNull String name, @NotNull Resolver resolver) {
            String full = name.isEmpty() ? prefix : prefix + "_" + name.toLowerCase(Locale.ROOT);
            pending.put(full, new Registry.Entry(resolver, plugin.getName(), async, description));
            return this;
        }

        /**
         * Registers everything added so far.
         *
         * <p>Registering the same name twice replaces the previous resolver, so
         * a plugin reload does not duplicate anything.
         */
        public void register() {
            pending.forEach(Registry::register);
            PapiBridge.refresh(plugin);
        }
    }

    /**
     * Sets where resolver failures are reported.
     *
     * <p>Called by ExyliaLib at startup. Consumers do not need this.
     *
     * @param logger the logger to use
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void logger(@NotNull java.util.logging.Logger logger) {
        Loggers.set(logger);
    }
}
