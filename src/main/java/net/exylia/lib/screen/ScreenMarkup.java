package net.exylia.lib.screen;

import net.exylia.lib.screen.internal.Css;
import net.exylia.lib.screen.internal.Markup;
import net.exylia.lib.screen.internal.MarkupRender;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A {@link Screen} written as markup and CSS instead of Java: a server owner restyles it by editing two files.
 *
 * <pre>{@code
 * ScreenMarkup hub = ScreenMarkup.load(folder.resolve("practice_hub.html"), folder.resolve("theme.css"))
 *         .source("kits", session -> kits.rows(session.viewer()))     // re-read every render: stays live
 *         .action("queue", (event, args) -> queues.join(event.viewer(), args.get(0)));
 * hub.problems().forEach(plugin.getLogger()::warning);
 * Screens.of(plugin).open(player, hub.screen(Map.of("tab", "queue")));
 * }</pre>
 *
 * <pre>{@code
 * <screen size="400x225">
 *   <panel>
 *     <tabs state="tab" default="queue">
 *       <tab value="queue">Queue</tab>
 *       <tab value="duels">Duels</tab>
 *     </tabs>
 *     <grid if="tab == 'queue'" source="kits" columns="4" rows="2">
 *       <card action="queue %kit_id%" selected="%kit_queued%">
 *         <icon>%kit_icon%</icon>
 *         <b>%kit_name%</b>
 *         <small>%kit_playing% playing</small>
 *       </card>
 *       <empty>No kits yet</empty>
 *     </grid>
 *     <pager for="kits"/>
 *   </panel>
 * </screen>
 * }</pre>
 *
 * <p>The library's base theme ({@code screens/base.css} in its jar) is read first, then each sheet in the
 * order given, so a plugin's theme overrides it. The supported tags, attributes and CSS subset are listed in
 * {@code docs/screens.md}; anything outside them is reported in {@link #problems()} with its file and line,
 * never thrown. Only markup that is not well-formed fails to load.
 *
 * <p>Sources, values and actions may be registered at any time and are safe to share between sessions;
 * sources and values are called on the viewer's thread once per render and should read memory.
 *
 * @since 1.485.0
 */
public final class ScreenMarkup {

    private static final String BASE = loadBase();

    private final Markup.El root;
    private final Css css = new Css();
    private final Map<String, Function<ScreenSession, List<? extends Map<String, ?>>>> sources = new ConcurrentHashMap<>();
    private final Map<String, Function<ScreenSession, ?>> values = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<ScreenEvent, List<String>>> actions = new ConcurrentHashMap<>();
    private final Set<String> problems = Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile Path images;
    private final String name;

    private ScreenMarkup(String name, Markup.El root) {
        this.name = name;
        this.root = root;
    }

    /**
     * Reads markup and its sheets from strings.
     *
     * @param name what problems call the markup, such as its file name
     * @throws IllegalArgumentException when the markup is not well-formed XML, with the line
     */
    public static @NotNull ScreenMarkup parse(@NotNull String name, @NotNull String markup, @NotNull String... sheets) {
        Markup.El root;
        try {
            root = Markup.parse(markup);
        } catch (Markup.Malformed failure) {
            throw new IllegalArgumentException(name + ":" + failure.line + ": " + failure.getMessage(), failure);
        }
        ScreenMarkup screen = new ScreenMarkup(name, root);
        screen.css.add(BASE, "base.css", screen.problems::add);
        for (int i = 0; i < sheets.length; i++) screen.css.add(sheets[i], name + " sheet " + (i + 1), screen.problems::add);
        screen.check(root);
        return screen;
    }

    /**
     * Reads a markup file and its sheets; a sheet that does not exist is skipped.
     *
     * @throws IOException              when the markup cannot be read
     * @throws IllegalArgumentException when it is not well-formed, with the file and line
     */
    public static @NotNull ScreenMarkup load(@NotNull Path markup, @NotNull Path... sheets) throws IOException {
        Markup.El root;
        String file = markup.getFileName().toString();
        try {
            root = Markup.parse(Files.readString(markup, StandardCharsets.UTF_8));
        } catch (Markup.Malformed failure) {
            throw new IllegalArgumentException(file + ":" + failure.line + ": " + failure.getMessage(), failure);
        }
        ScreenMarkup screen = new ScreenMarkup(file, root);
        screen.images = markup.toAbsolutePath().getParent();
        screen.css.add(BASE, "base.css", screen.problems::add);
        for (Path sheet : sheets) {
            if (Files.isRegularFile(sheet)) {
                screen.css.add(Files.readString(sheet, StandardCharsets.UTF_8), sheet.getFileName().toString(),
                        screen.problems::add);
            }
        }
        screen.check(root);
        return screen;
    }

    /** Rows a {@code source="name"} repeats over, each a map of the fields its template reads as {@code %field%}. */
    public @NotNull ScreenMarkup source(@NotNull String name,
                                       @NotNull Function<ScreenSession, List<? extends Map<String, ?>>> rows) {
        sources.put(name, rows);
        return this;
    }

    /** A {@code %name%} readable anywhere in the markup, re-read every render. */
    public @NotNull ScreenMarkup value(@NotNull String name, @NotNull Function<ScreenSession, ?> value) {
        values.put(name, value);
        return this;
    }

    /**
     * What {@code action="name arg1 arg2"} does, on the viewer's thread; the screen redraws after it. The
     * built-ins {@code close}, {@code set <key> <value>} and {@code toggle <key>} need no registration, and an
     * id with a namespace ({@code practice:join_queue}) runs that ExyliaLib action.
     */
    public @NotNull ScreenMarkup action(@NotNull String name, @NotNull BiConsumer<ScreenEvent, List<String>> handler) {
        actions.put(name, handler);
        return this;
    }

    /** What is wrong with the markup or its sheets, and what went missing while rendering; each said once. */
    public @NotNull List<String> problems() {
        synchronized (problems) {
            return List.copyOf(problems);
        }
    }

    /**
     * The folder {@code <img src>} reads pictures from. {@link #load} sets it to the markup's own folder.
     *
     * @since 1.489.0
     */
    public @NotNull ScreenMarkup images(@NotNull Path dir) {
        this.images = dir;
        return this;
    }

    /** A screen drawn from this markup; open it with {@link Screens#open}. */
    public @NotNull Screen screen() {
        return screen(Map.of());
    }

    /** A screen drawn from this markup, starting with {@code state} (the open tab, a preselected value). */
    public @NotNull Screen screen(@NotNull Map<String, ?> state) {
        MarkupRender.Parts parts = new MarkupRender.Parts(root, css, sources, values, actions,
                problem -> problems.add(name + ": " + problem), images);
        Screen screen = Screen.of(session -> MarkupRender.render(parts, session)).state(state);
        String size = root.attr("size");
        if (size != null && size.matches("\\d+x\\d+")) {
            String[] wh = size.split("x");
            screen.size(Double.parseDouble(wh[0]), Double.parseDouble(wh[1]));
        }
        if (root.attr("backdrop") != null) screen.backdrop(root.attr("backdrop"));
        if ("true".equalsIgnoreCase(root.attr("wheel"))) screen.wheel(true);
        return screen;
    }

    private void check(Markup.El el) {
        if (!MarkupRender.TAGS.contains(el.tag())) problems.add(name + ":" + el.line() + ": unknown tag <" + el.tag() + ">");
        if (el.tag().equals("pager") && el.attr("for") == null) problems.add(name + ":" + el.line() + ": <pager> needs for=\"<source>\"");
        for (Markup.El child : el.elements()) check(child);
    }

    private static String loadBase() {
        try (InputStream in = ScreenMarkup.class.getResourceAsStream("/screens/base.css")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return "";
        }
    }

    /** Names the screen. */
    @Override
    public String toString() {
        return "ScreenMarkup[" + name + "]";
    }
}
