package net.exylia.lib.web;

import net.exylia.lib.web.internal.WebEditorRuntime;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Editing a plugin's configuration from the browser, the way LuckPerms' web editor works.
 *
 * <p>A plugin hands over the config files it wants editable, once, on enable:
 *
 * <pre>{@code
 * WebEditor.of(this)
 *         .subject("holograms", hologramsFile, "displays/hologram")
 *         .permission("exyliadisplays.holograms")
 *         .sensitive("holograms.*.click")
 *         .onApplied(this::reloadFile);
 * }</pre>
 *
 * <p>From then on {@code /exylialib editor} opens a session with an index of what is
 * editable, answers with a link, and uploads each plugin's subjects one after another. The admin edits in the browser, and
 * {@code /exylialib editor apply <code>}, typed in game, brings the result back: it is read
 * through the same binder as the YAML file, compared with what the server has now, and
 * written through {@link net.exylia.lib.config.ConfigFile#update}. Nothing reaches the server
 * unless somebody with permission asks for it in game.
 *
 * <h2>What a subject is</h2>
 * One {@link net.exylia.lib.config.ConfigFile}, described to the browser by its schema (keys,
 * types, comments) and its current values. The browser renders any subject from the schema
 * alone; a {@code kind} names a richer screen the editor ships for that shape, such as the
 * hologram editor with its preview. A subject without a kind is edited as a form.
 *
 * <h2>Discovery</h2>
 * Since 1.307.0 every enabled Exylia plugin's config files and other YAML files are editable
 * without registering them, by whoever holds {@code exylialib.editor}: settings, the active
 * language's messages, menus (checked by the plugin's menu compiler) and any other file.
 * Applying one runs the plugin's declared {@link net.exylia.lib.reload.Reloads}. A registered
 * subject covering a file wins; {@link PluginWebEditor#exclude} and
 * {@link PluginWebEditor#discover} opt out.
 *
 * <h2>Security</h2>
 * The server never listens: every exchange is a request it makes. The link carries only a
 * public code; the secret that authorises reading a change back stays in memory on the server
 * and is sent as a header. The first browser to open a link owns the session; another browser,
 * or the same link from another address, is read-only until {@code /exylialib editor trust}
 * confirms its challenge in game. Changes that touch a {@link Subject#sensitive sensitive}
 * path, or a line that runs a console command, or a file that changed on the server since the
 * snapshot, are shown first and applied only with {@code confirm}.
 *
 * <h2>Threads</h2>
 * Registration is safe from any thread. {@link #open} checks permissions where it is called;
 * discovery, file reads and every request run asynchronously, and entries and placeholders are
 * read on the global thread. Applying runs the file update and the {@code onApplied} listener
 * on the global thread.
 *
 * @since 1.304.0
 */
public final class WebEditor {

    private WebEditor() {
        throw new AssertionError("No instances.");
    }

    /**
     * The editor registrations of one plugin.
     *
     * @param plugin the plugin whose files are being made editable
     * @return the plugin's handle, the same one on every call
     */
    public static @NotNull PluginWebEditor of(@NotNull Plugin plugin) {
        return new PluginWebEditor(plugin);
    }

    /**
     * Opens a session over every subject the sender may edit, and tells them the link.
     *
     * <p>What {@code /exylialib editor} does; a plugin may offer the same from its own command.
     *
     * @param sender who asked; the link and every outcome are sent to them
     */
    public static void open(@NotNull CommandSender sender) {
        WebEditorRuntime.get().open(sender, null);
    }

    /**
     * Opens a session over one plugin's subjects only.
     *
     * @param sender who asked
     * @param plugin the plugin whose subjects the session covers
     */
    public static void open(@NotNull CommandSender sender, @NotNull Plugin plugin) {
        WebEditorRuntime.get().open(sender, plugin.getName());
    }

    /**
     * Whether the editor is enabled in the library's config and at least one subject exists.
     *
     * @param plugin a plugin whose subjects are asked about, or {@code null} for any
     * @return whether {@link #open} could open something
     */
    public static boolean available(@Nullable Plugin plugin) {
        return WebEditorRuntime.get().available(plugin == null ? null : plugin.getName());
    }
}
