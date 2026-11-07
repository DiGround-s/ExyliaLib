package net.exylia.lib.screen;

import net.exylia.lib.screen.internal.Compile;
import net.exylia.lib.screen.internal.Css;
import net.exylia.lib.screen.internal.Markup;
import net.exylia.lib.screen.internal.MarkupRender;
import net.exylia.lib.ui.UiEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A {@link Screen} written as markup and CSS instead of Java: a server owner restyles it by editing two files.
 *
 * <pre>{@code
 * ScreenMarkup hub = ScreenMarkup.load(folder.resolve("practice_hub.html"), folder.resolve("theme.css"))
 *         .rows("kits", session -> kits.entries(session.viewer()))      // UiEntry rows: re-read every render
 *         .action("queue", (event, args) -> queues.join(event.viewer(), event.row(Kit.class).orElseThrow()));
 * hub.problems().forEach(plugin.getLogger()::warning);
 * Screens.of(plugin).open(player, hub.screen(Map.of("tab", "queue")));
 * }</pre>
 *
 * <pre>{@code
 * <screen size="400x225">
 *   <panel>
 *     <tabs state="tab" default="queue">
 *       <tab value="queue" t="tab.queue">Queue</tab>
 *       <tab value="duels" t="tab.duels">Duels</tab>
 *     </tabs>
 *     <grid if="tab == 'queue'" source="kits" columns="4" rows="2" track="%kit_id%">
 *       <card action="queue" selected="%kit_queued%">
 *         <icon>%kit_icon%</icon>
 *         <b>%kit_name%</b>
 *         <small>${kit_playing} playing</small>
 *       </card>
 *       <empty>No kits yet</empty>
 *     </grid>
 *     <pager for="kits"/>
 *   </panel>
 * </screen>
 * }</pre>
 *
 * <p>The library's base theme ({@code screens/base.css} in its jar) is read first, then each sheet in the
 * order given, so a plugin's theme overrides it. The supported tags, attributes, expressions and CSS subset are
 * listed in {@code docs/screens.md}; anything outside them is reported in {@link #problems()} with its file, line
 * and column, never thrown. Only markup that is not well-formed fails to load. For a plugin's own screens,
 * {@link Screens#files} reads them from its data folder with their words, updates and hot reload.
 *
 * <p>Sources, values and actions may be registered at any time and are safe to share between sessions;
 * they survive {@link #reload()}. Sources and values are called on the viewer's thread once per render and should
 * read memory.
 *
 * @since 1.485.0
 */
public final class ScreenMarkup {

    private static final String BASE = loadBase();
    /** Actions every screen knows without registering. */
    private static final Set<String> BUILT_IN = Set.of("close", "set", "clear", "toggle", "toast");

    /** One read of the files: what a render draws until the next read. */
    record Compiled(Markup.El root, Css css, List<String> problems, List<Compile.Reference> references,
                    @Nullable Path images, Function<String, String> words) {
    }

    /** How the markup is read again: the same files, the same words. */
    @FunctionalInterface
    interface Reader {
        Compiled read() throws IOException;
    }

    private final String name;
    private final MarkupRender.Bindings bindings = MarkupRender.Bindings.create();
    private final Set<String> problems = Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile Reader reader;
    private volatile Compiled compiled;
    private volatile Path images;

    private ScreenMarkup(String name, Reader reader, Compiled compiled) {
        this.name = name;
        this.reader = reader;
        this.compiled = compiled;
    }

    /** A markup that reads itself through {@code reader}, for {@link ScreenSet}. */
    static ScreenMarkup read(String name, Reader reader) throws IOException {
        return new ScreenMarkup(name, reader, reader.read());
    }

    /**
     * Reads markup and its sheets from strings.
     *
     * @param name what problems call the markup, such as its file name
     * @throws IllegalArgumentException when the markup is not well-formed XML, with the line
     */
    public static @NotNull ScreenMarkup parse(@NotNull String name, @NotNull String markup, @NotNull String... sheets) {
        Reader reader = () -> {
            List<String[]> list = new ArrayList<>();
            for (int i = 0; i < sheets.length; i++) list.add(new String[]{sheets[i], name + " sheet " + (i + 1)});
            return compile(name, markup, list, List.of(), relative -> null, key -> null, null);
        };
        try {
            return read(name, reader);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Reads a markup file and its sheets; a sheet that does not exist is skipped. {@code <include src>} and
     * {@code <img src>} read from the markup's folder, and the {@code <template>}s of every
     * {@code components/*.html} there are known to it (since 1.494.0).
     *
     * @throws IOException              when the markup cannot be read
     * @throws IllegalArgumentException when it is not well-formed, with the file and line
     */
    public static @NotNull ScreenMarkup load(@NotNull Path markup, @NotNull Path... sheets) throws IOException {
        Path folder = markup.toAbsolutePath().getParent();
        String file = markup.getFileName().toString();
        Reader reader = () -> {
            List<String[]> list = new ArrayList<>();
            for (Path sheet : sheets) {
                if (Files.isRegularFile(sheet)) {
                    list.add(new String[]{Files.readString(sheet, StandardCharsets.UTF_8), sheet.getFileName().toString()});
                }
            }
            return compile(file, Files.readString(markup, StandardCharsets.UTF_8), list, components(folder),
                    relative -> readInside(folder, relative), key -> null, folder);
        };
        return read(file, reader);
    }

    /** Every {@code components/*.html} of a folder, by name, as fragments' sources. */
    static List<String[]> components(Path folder) throws IOException {
        Path dir = folder.resolve("components");
        List<String[]> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path one : files.filter(p -> p.getFileName().toString().endsWith(".html")).sorted().toList()) {
                out.add(new String[]{Files.readString(one, StandardCharsets.UTF_8), "components/" + one.getFileName()});
            }
        }
        return out;
    }

    /** A file under {@code folder}, or {@code null} when there is none or the path leaves it. */
    static String readInside(Path folder, String relative) {
        Path file = folder.resolve(relative).normalize();
        if (!file.startsWith(folder.normalize()) || !Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * Compiles one screen.
     *
     * @param sheets     each sheet's text and name, in cascade order after the base theme
     * @param components each component file's text and name
     */
    static Compiled compile(String name, String markup, List<String[]> sheets, List<String[]> components,
                            Compile.Files files, Function<String, String> words, @Nullable Path images) {
        Markup.El root;
        try {
            root = Markup.parse(markup, name);
        } catch (Markup.Malformed failure) {
            throw new IllegalArgumentException(name + ":" + failure.line + ": " + failure.getMessage(), failure);
        }
        List<String> problems = new ArrayList<>();
        List<Markup.El> fragments = new ArrayList<>();
        for (String[] component : components) {
            try {
                fragments.add(Markup.fragment(component[0], component[1]));
            } catch (Markup.Malformed failure) {
                problems.add(component[1] + ":" + failure.line + ": " + failure.getMessage());
            }
        }
        Compile.Result result = Compile.run(root, fragments, files, words);
        problems.addAll(result.problems());
        Css css = new Css();
        css.add(BASE, "base.css", problems::add);
        for (String[] sheet : sheets) css.add(sheet[0], sheet[1], problems::add);
        return new Compiled(result.root(), css, List.copyOf(problems), result.references(), images, words);
    }

    /**
     * Reads the files again, keeping every source, value and action registered, so a server owner's edit shows
     * at the next render of every open session. When the markup no longer reads, the last good one stays and
     * {@link #problems()} says why.
     *
     * @return whether the files read
     * @since 1.494.0
     */
    public boolean reload() {
        try {
            Compiled fresh = reader.read();
            compiled = fresh;
            problems.clear();
            return true;
        } catch (IOException | IllegalArgumentException broken) {
            problems.add(String.valueOf(broken.getMessage()));
            return false;
        }
    }

    /** Rows a {@code source="name"} repeats over, each a map of the fields its template reads as {@code %field%}. */
    public @NotNull ScreenMarkup source(@NotNull String name,
                                       @NotNull Function<ScreenSession, List<? extends Map<String, ?>>> rows) {
        bindings.sources().put(name, rows);
        return this;
    }

    /**
     * Rows a {@code source="name"} (or {@code <for each="x in name">}) repeats over, as the chest menus' rows are
     * built: a value is drawn as plain text unless it was given with {@link UiEntry.Builder#withFormatted}, so a
     * player's name can never restyle the screen, and the row's {@link UiEntry#value()} reaches a click as
     * {@link ScreenEvent#row()}.
     *
     * <pre>{@code
     * hub.rows("kits", session -> kits.stream().map(kit -> UiEntry.of(kit)
     *         .with("kit_id", kit.id()).withFormatted("kit_name", kit.displayName()).build()).toList())
     *    .action("queue", (event, args) -> event.row(Kit.class).ifPresent(kit -> queues.join(event.viewer(), kit)));
     * }</pre>
     *
     * @since 1.494.0
     */
    public @NotNull ScreenMarkup rows(@NotNull String name, @NotNull Function<ScreenSession, List<UiEntry>> rows) {
        bindings.entries().put(name, rows);
        return this;
    }

    /**
     * A {@code %name%} readable anywhere in the markup, re-read every render. Its text is inserted as written, so
     * it may carry colours: for values the server owner wrote. A value a player could have typed goes through
     * {@link #text} instead.
     */
    public @NotNull ScreenMarkup value(@NotNull String name, @NotNull Function<ScreenSession, ?> value) {
        bindings.values().put(name, value);
        return this;
    }

    /**
     * A {@code %name%} readable anywhere in the markup, re-read every render, drawn as plain text: tags, colour
     * codes and palette tokens in it show as the characters they are. For anything a player typed (a name, a
     * search, a layout's title).
     *
     * @since 1.494.0
     */
    public @NotNull ScreenMarkup text(@NotNull String name, @NotNull Function<ScreenSession, ?> value) {
        bindings.texts().put(name, value);
        return this;
    }

    /**
     * What {@code action="name arg1 arg2"} does, on the viewer's thread; the screen redraws after it. The
     * built-ins {@code close}, {@code set <key> <value>}, {@code toggle <key>}, {@code clear <key>...} and
     * {@code toast <text>} need no registration, and an id with a namespace ({@code practice:join_queue}) runs
     * that ExyliaLib action. Each word of the action is one argument, however many spaces a value put in it.
     */
    public @NotNull ScreenMarkup action(@NotNull String name, @NotNull BiConsumer<ScreenEvent, List<String>> handler) {
        bindings.actions().put(name, handler);
        return this;
    }

    /**
     * What is wrong with the markup or its sheets, each said once with {@code file:line:col}: unknown tags,
     * attributes and properties, expressions that do not read, includes and templates that do not exist, and
     * (since 1.494.0) sources and actions the markup names that nothing registered. Then what went missing
     * while rendering.
     */
    public @NotNull List<String> problems() {
        Compiled now = compiled;
        Set<String> out = new LinkedHashSet<>(now.problems());
        for (Compile.Reference reference : now.references()) {
            if (reference.kind().equals("source") && !bindings.hasSource(reference.name())
                    && !bindings.values().containsKey(reference.name())) {
                out.add(reference.pos() + ": no source \"" + reference.name() + "\" is registered");
            } else if (reference.kind().equals("action") && !known(reference.name())) {
                out.add(reference.pos() + ": no action \"" + reference.name() + "\" is registered");
            }
        }
        synchronized (problems) {
            out.addAll(problems);
        }
        return List.copyOf(out);
    }

    private boolean known(String action) {
        if (BUILT_IN.contains(action) || bindings.actions().containsKey(action) || action.contains("%")
                || action.contains("${")) {
            return true;
        }
        if (!action.contains(":")) return false;
        try {
            net.exylia.lib.action.Actions.compile(action);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        } catch (RuntimeException | LinkageError noServer) {
            return true;
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
        Screen screen = Screen.of(session -> {
            // Read at every render, so a reload reaches screens already open.
            Compiled now = compiled;
            Path folder = images != null ? images : now.images();
            MarkupRender.Parts parts = new MarkupRender.Parts(now.root(), now.css(), bindings,
                    problem -> problems.add(name + ": " + problem), folder, now.words());
            return MarkupRender.render(parts, session);
        }).state(state);
        Markup.El root = compiled.root();
        String size = root.attr("size");
        if (size != null && size.matches("\\d+x\\d+")) {
            String[] wh = size.split("x");
            screen.size(Double.parseDouble(wh[0]), Double.parseDouble(wh[1]));
        }
        if (root.attr("backdrop") != null) screen.backdrop(root.attr("backdrop"));
        if ("true".equalsIgnoreCase(root.attr("wheel")) || "".equals(root.attr("wheel"))) screen.wheel(true);
        return screen;
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
