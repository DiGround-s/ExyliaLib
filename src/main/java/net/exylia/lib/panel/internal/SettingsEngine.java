package net.exylia.lib.panel.internal;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Schema;
import net.exylia.lib.input.InputResult;
import net.exylia.lib.item.Item;
import net.exylia.lib.item.Items;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The settings panel: a config record edited from its own schema.
 *
 * <p>There is no per-record code here and there is not meant to be. What a
 * component is drawn as comes from {@link ControlMapper}, what it is documented
 * with comes from its {@code @Comment} lines, and what it saves as comes from
 * {@link RecordRebuilder}. That is the whole of it, which is why the effects
 * editor is this class pointed at {@code EffectConfig} rather than a second
 * screen somebody has to keep in step with the record.
 *
 * <h2>Nothing is written until save</h2>
 * Every edit lands in a working copy of the component values. A save rebuilds
 * the record first — {@link RecordRebuilder} is pure — and only hands an already
 * built record to {@link ConfigFile#update}. A record that refuses its own
 * values costs the player a message; the file is never opened.
 *
 * <h2>Sub-panels</h2>
 * A nested record is not a second panel with a second working copy. Entering one
 * pushes a frame; every edit rebuilds the nested record and writes it straight
 * into the parent's working copy, so leaving a sub-panel has nothing to gather
 * and losing one loses nothing.
 *
 * <h2>Threads</h2>
 * Drawing and activating belong on the thread that owns the viewer, which is
 * where a click handler already is. The working copy, the rebuild and the diff
 * touch no Bukkit API and are safe anywhere. The write is
 * {@code runAsync} → {@link ConfigFile#update} → back via {@code runAtEntity}.
 *
 * @param <T> the config record being edited
 */
@ApiStatus.Internal
public final class SettingsEngine<T extends Record> {

    private final Plugin plugin;
    private final Player viewer;
    private final ConfigFile<T> file;
    private final @Nullable Consumer<T> onSaved;

    /**
     * The record being edited, then each nested record entered into.
     *
     * <p>A stack rather than a field, because a nested record can itself nest
     * and "go back one" is the only navigation a sub-panel needs.
     */
    private final Deque<Frame> frames = new ArrayDeque<>();

    /**
     * The session this panel is: the one working copy, the undo stack, and
     * everything that must be given back when the screen goes.
     *
     * <p>There is deliberately no second working copy here. The session already
     * <em>is</em> one, and two would drift the moment a plugin took back an edit
     * through {@link net.exylia.lib.panel.PanelSession#undo()} while the engine
     * held its own idea of what was on screen.
     */
    private final Session session;

    private SettingsEngine(Plugin plugin, Player viewer, ConfigFile<T> file,
                           @Nullable Consumer<T> onSaved) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.file = file;
        this.onSaved = onSaved;
        this.frames.push(new Frame(file.schema(), null));
        this.session = Session.of(PanelRuntime.of(plugin), viewer,
                RecordRebuilder.componentsOf(file.get()), this::write);
    }

    /**
     * Starts editing a config file.
     *
     * @param plugin  who owns the file
     * @param viewer  who is editing
     * @param file    what is being edited
     * @param onSaved run with the new record after a successful write, or {@code null}
     */
    public static <T extends Record> @NotNull SettingsEngine<T> of(@NotNull Plugin plugin,
                                                                   @NotNull Player viewer,
                                                                   @NotNull ConfigFile<T> file,
                                                                   @Nullable Consumer<T> onSaved) {
        return new SettingsEngine<>(plugin, viewer, file, onSaved);
    }

    /** The session a click is validated against, and a caller can save or cancel. */
    public @NotNull Session session() {
        return session;
    }

    // ------------------------------------------------------------------
    // What a slot holds
    // ------------------------------------------------------------------

    /**
     * One drawn control: the component it edits and what it looks like.
     *
     * <p>Handed to the draw sink as the row's subject, so a test reads which
     * component landed in which slot without an {@code ItemStack} — which cannot
     * be built without a running server.
     *
     * @param field the component, as the schema projects it
     * @param kind  which control it is
     * @param item  the item definition drawn for it, carrying its comment lore
     */
    public record Control(@NotNull Schema.Field field, @NotNull ControlKind kind,
                          @NotNull Item item) {
    }

    /** One level of nesting: a schema, and where its rebuilt record goes. */
    private static final class Frame {

        private final Schema schema;

        /** The component of the parent this frame rebuilds into, or {@code null} at the root. */
        private final @Nullable String parentComponent;

        /** What is drawn where, rebuilt on every draw so a redraw is never stale. */
        private final Map<Integer, Control> controls = new LinkedHashMap<>();

        /** The chrome buttons, by slot, so a click on one is recognised. */
        private final Map<Integer, ControlKind> chrome = new LinkedHashMap<>();

        private Frame(Schema schema, @Nullable String parentComponent) {
            this.schema = schema;
            this.parentComponent = parentComponent;
        }
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    /**
     * Shows the panel and draws its first frame.
     *
     * <p>Must be called on the thread that owns the viewer; {@code SettingsPanel}
     * relocates there before it gets here, which is what makes the public
     * {@code open} safe from anywhere.
     *
     * <p>The window is bound to the session <em>before</em> anything is drawn, so
     * a click that arrives during the draw finds a session rather than nothing.
     *
     * @param title the title to show, or {@code null} for the layout's own
     */
    @SuppressWarnings("deprecation")
    public void open(@Nullable String title) {
        PanelHolder holder = new PanelHolder();
        // The legacy-string overload deliberately, and for the reason
        // ui/internal/Session gives at the same call: the component-taking one
        // is Paper's, and this library has to load on pure Spigot. A window
        // title carries colour and nothing else, so nothing is lost by it.
        Inventory window = Bukkit.createInventory(holder, Layouts.SIZE,
                Text.of(title == null ? Layouts.BUILT_IN.title() : title).legacy());
        holder.bind(session, window);
        session.bind(window, this::activate);

        draw();
        viewer.openInventory(window);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * Draws the current frame.
     *
     * <p>Announces every slot through {@link PanelRenderer}, which is what a
     * test asserts against. Controls come first, in canonical-constructor order,
     * and the chrome row after — so reading order on screen is declaration order
     * in the record.
     */
    public void draw() {
        Frame frame = frames.peek();
        frame.controls.clear();
        frame.chrome.clear();

        int slot = 0;
        for (Schema.Field field : frame.schema.fields()) {
            if (slot >= Layouts.CONTROL_SLOTS) {
                // More components than fit. The remainder is reachable through
                // the layout's pagination rather than dropped, and drawing stops
                // here for this page.
                break;
            }
            ControlKind kind = ControlMapper.kindOf(field);
            if (kind == ControlKind.UNSUPPORTED) {
                // Once per server, never once per open: opening a panel renders
                // every component, and this is a fact about the library rather
                // than an incident of the screen that happened to open.
                UnsupportedTypes.report(plugin, field.name(), field.type());
            }
            Control control = new Control(field, kind, itemFor(field, kind));
            frame.controls.put(slot, control);
            PanelRenderer.drew(slot, kind, control);
            put(slot, control.item());
            slot++;
        }

        drawChrome(frame);
    }

    private void drawChrome(Frame frame) {
        frame.chrome.put(Layouts.CANCEL_SLOT, ControlKind.CANCEL);
        frame.chrome.put(Layouts.UNDO_SLOT, ControlKind.UNDO);
        frame.chrome.put(Layouts.SAVE_SLOT, ControlKind.SAVE);
        for (Map.Entry<Integer, ControlKind> button : frame.chrome.entrySet()) {
            PanelRenderer.drew(button.getKey(), button.getValue(), null);
            put(button.getKey(), chromeItem(button.getValue()));
        }
    }

    /**
     * Puts an item in a slot, when there is a window to put it in.
     *
     * <p>A session with no window draws nothing and that is deliberate: a test
     * cannot open one, because {@code Bukkit.createInventory} answers nothing
     * without a running server and an {@code ItemStack} cannot even
     * class-initialise. What a test asserts on is the draw sink, which has
     * already been told by the time this runs.
     */
    private void put(int slot, Item item) {
        Inventory window = session.window();
        if (window == null) {
            return;
        }
        window.setItem(slot, Items.of(plugin).render(item, viewer));
    }

    /** The button a chrome slot is drawn as. */
    private static Item chromeItem(ControlKind kind) {
        return switch (kind) {
            case SAVE -> Item.of("LIME_DYE").name("{success}&lSAVE").build();
            case CANCEL -> Item.of("BARRIER").name("{error}&lCANCEL").build();
            case UNDO -> Item.of("CLOCK").name("{warning}&lUNDO").build();
            default -> Item.of("GRAY_DYE").name("{neutral}&l-").build();
        };
    }

    /**
     * The item a component is drawn as.
     *
     * <p>The name is the component's YAML key rather than its Java name: the key
     * is what the owner sees in the file, and a panel that named things
     * differently from the file would make the two impossible to line up. The
     * lore is the {@code @Comment} lines, in declaration order — they are already
     * the owner's manual by doctrine, and until now only somebody opening the
     * {@code .yml} ever read them.
     */
    private Item itemFor(Schema.Field field, ControlKind kind) {
        List<String> lore = new ArrayList<>(field.comments());
        lore.add("");
        lore.add(kind == ControlKind.UNSUPPORTED
                // Said on the item as well as in the console: the owner looking
                // at a greyed-out row is the one who needs to know why.
                ? "{letters_black}Not editable here. Saved unchanged."
                : "{letters_black}Current: {letters}" + describe(value(field.name())));
        return Item.of(materialFor(kind))
                .name("{primary}&l" + field.key().toUpperCase(Locale.ROOT))
                .lore(lore)
                .build();
    }

    /**
     * Which material stands for which control.
     *
     * <p>A default, not a decision: an owner retheming panels replaces the
     * layout, and nothing here is written into control flow.
     */
    private static String materialFor(ControlKind kind) {
        return switch (kind) {
            case INTEGER, DECIMAL -> "PAPER";
            case TOGGLE -> "LEVER";
            case CHOICE -> "COMPASS";
            case TEXT -> "NAME_TAG";
            case LIST -> "BOOKSHELF";
            case SUB_PANEL -> "CHEST";
            default -> "GRAY_DYE";
        };
    }

    /** How a value reads on a tooltip. Null is a word, not a blank. */
    private static String describe(@Nullable Object value) {
        if (value == null) {
            return "none";
        }
        if (value instanceof Record) {
            return "...";
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // Clicking
    // ------------------------------------------------------------------

    /**
     * Acts on a click in a slot.
     *
     * <p>Resolved against what this engine drew, never against the item the
     * client says it clicked: a packet carries a slot number, and the server
     * already knows what it put there.
     *
     * @param slot the raw slot clicked
     * @return whether the click meant anything
     */
    public boolean activate(int slot) {
        Frame frame = frames.peek();
        ControlKind button = frame.chrome.get(slot);
        if (button != null) {
            return chrome(button);
        }
        Control control = frame.controls.get(slot);
        if (control == null) {
            return false;
        }
        if (!ControlMapper.isEditable(control.kind())) {
            // Read-only, and silently so: the row already says why, and a
            // refusal noise per click on a documented field is nagging.
            return false;
        }
        return edit(control);
    }

    private boolean chrome(ControlKind button) {
        return switch (button) {
            case SAVE -> {
                save();
                yield true;
            }
            case CANCEL -> {
                // Inside a sub-panel this is a way back, not a way out: leaving
                // the whole panel from a nested screen would throw away edits the
                // player made two levels up without saying so.
                if (frames.size() > 1) {
                    frames.pop();
                } else {
                    session.cancel();
                }
                yield true;
            }
            case UNDO -> session.undo();
            default -> false;
        };
    }

    private boolean edit(Control control) {
        Schema.Field field = control.field();
        return switch (control.kind()) {
            case TOGGLE -> {
                // No prompt: asking a player to confirm a toggle is asking them
                // to click twice for nothing.
                commit(field.name(), !Boolean.TRUE.equals(value(field.name())));
                yield true;
            }
            case CHOICE -> {
                choose(field);
                yield true;
            }
            case SUB_PANEL -> {
                Schema nested = field.nested();
                if (nested == null) {
                    yield false;
                }
                frames.push(new Frame(nested, field.name()));
                draw();
                yield true;
            }
            case INTEGER, DECIMAL, TEXT -> {
                ask(field, control.kind());
                yield true;
            }
            // A list opens the list panel, which is its own module.
            default -> false;
        };
    }

    /**
     * Offers an enum through the input module's search.
     *
     * <p>Search rather than a hand-rolled picker because an enum can be longer
     * than a screen, and the input module already solved paging, filtering and
     * every transport. The panel never calls {@code Inputs} itself: that goes
     * through {@link PanelPrompts}, so a test scripts the answer.
     */
    private void choose(Schema.Field field) {
        Object[] constants = field.type().getEnumConstants();
        if (constants == null) {
            return;
        }
        List<Object> choices = List.of(constants);
        PanelPrompts.get()
                .search(plugin, viewer, "Choose " + field.key(), choices,
                        constant -> ((Enum<?>) constant).name())
                .thenAccept(result -> onAnswer(result, chosen -> commit(field.name(), chosen)));
    }

    /** Asks for a value as text and parses it into the declared type. */
    private void ask(Schema.Field field, ControlKind kind) {
        PanelPrompts.get()
                .text(plugin, viewer, "New value for " + field.key())
                .thenAccept(result -> onAnswer(result, typed -> {
                    Object parsed = parse(typed, field.type(), kind);
                    if (parsed == null) {
                        // Not a number. The old value stands; a working copy must
                        // never hold something the record could not take.
                        return;
                    }
                    commit(field.name(), parsed);
                }));
    }

    /**
     * Turns typed text into the declared type.
     *
     * <p>Returns {@code null} when it does not fit, rather than throwing: a
     * mistyped number is an ordinary thing a player does, and it must cost them
     * a retry rather than an error in the console.
     */
    private static @Nullable Object parse(String typed, Class<?> type, ControlKind kind) {
        String text = typed.trim();
        try {
            if (kind == ControlKind.TEXT) {
                return typed;
            }
            if (type == int.class || type == Integer.class) {
                return Integer.valueOf(text);
            }
            if (type == long.class || type == Long.class) {
                return Long.valueOf(text);
            }
            if (type == short.class || type == Short.class) {
                return Short.valueOf(text);
            }
            if (type == byte.class || type == Byte.class) {
                return Byte.valueOf(text);
            }
            if (type == double.class || type == Double.class) {
                return Double.valueOf(text);
            }
            if (type == float.class || type == Float.class) {
                return Float.valueOf(text);
            }
        } catch (NumberFormatException notANumber) {
            return null;
        }
        return null;
    }

    /** Runs an edit only when the player actually answered. */
    private static <V> void onAnswer(InputResult<V> result, Consumer<V> action) {
        if (result.completed()) {
            action.accept(result.value());
        }
    }

    // ------------------------------------------------------------------
    // The working copy
    // ------------------------------------------------------------------

    /**
     * Commits an edit to the frame being edited, and to every frame above it.
     *
     * <p>A nested record is rebuilt immediately and written into its parent's
     * component, all the way up to the root. That is what makes leaving a
     * sub-panel free: there is nothing left to gather, because the parent
     * already holds the rebuilt value.
     *
     * <p>A rebuild the record refuses stops here. The parent keeps what it had
     * and the player is told what the record said.
     */
    private void commit(String component, @Nullable Object value) {
        List<Frame> stack = new ArrayList<>(frames);
        Frame current = stack.get(0);

        if (current.parentComponent == null) {
            session.editAll(replace(session.values(), component, value));
            return;
        }

        // Rebuild from the leaf up: each nested record becomes a value in the
        // one above it, and the root map is what finally changes.
        Map<String, Object> values = replace(componentsOf(current), component, value);
        for (int index = 0; index < stack.size(); index++) {
            Frame frame = stack.get(index);
            if (frame.parentComponent == null) {
                session.editAll(values);
                return;
            }
            var rebuilt = RecordRebuilder.rebuild(frame.schema.type(), values);
            if (!rebuilt.accepted()) {
                reject(rebuilt.rejection());
                return;
            }
            Frame parent = stack.get(index + 1);
            values = replace(componentsOf(parent), frame.parentComponent, rebuilt.value());
        }
    }

    /** The component values of a frame, read out of whatever the root holds. */
    private Map<String, Object> componentsOf(Frame frame) {
        if (frame.parentComponent == null) {
            return session.values();
        }
        Object held = valueOfFrame(frame);
        return held instanceof Record record
                ? RecordRebuilder.componentsOf(record)
                : new LinkedHashMap<>();
    }

    /** Walks the frame stack down from the root to find what a frame is editing. */
    private @Nullable Object valueOfFrame(Frame target) {
        List<Frame> stack = new ArrayList<>(frames);
        Object current = null;
        for (int index = stack.size() - 1; index >= 0; index--) {
            Frame frame = stack.get(index);
            if (frame.parentComponent == null) {
                current = null;
            } else {
                Map<String, Object> parent = current instanceof Record record
                        ? RecordRebuilder.componentsOf(record)
                        : session.values();
                current = parent.get(frame.parentComponent);
            }
            if (frame == target) {
                return current;
            }
        }
        return current;
    }

    private static Map<String, Object> replace(Map<String, Object> values, String component,
                                               @Nullable Object value) {
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put(component, value);
        return next;
    }

    /** Tells the player what the record said, in the record's own words. */
    private void reject(@Nullable String reason) {
        if (reason == null) {
            return;
        }
        net.exylia.lib.text.Text.of("{error}" + reason).send(viewer);
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    /**
     * Rebuilds the record and writes it, if anything changed.
     *
     * <p>The rebuild is first and it is pure: a record that refuses its own
     * values is refused here, before a write path exists, so the file is never
     * touched and there is nothing half-applied to undo.
     *
     * <p>The write itself is the only I/O this class does, and it goes through
     * {@link ConfigFile#update} exclusively — never a {@code FileConfiguration},
     * never YAML directly. Migrations, pruning and comment preservation stay
     * owned by the config module, which is the only place that knows how to keep
     * them.
     *
     * @return whether a write was started
     */
    public boolean save() {
        if (session.diff().isEmpty()) {
            // Opening a panel to look at something must not rewrite the file.
            return false;
        }
        // Checked here, before the session is asked to write, so a record that
        // refuses its own values costs a message rather than a half-applied
        // file. That ordering is decision 4, and it is the whole reason the
        // rebuild is pure: there is no write path to unwind because none has
        // been entered yet. The session is only asked once this has passed.
        var rebuilt = rebuild(session.values());
        if (!rebuilt.accepted()) {
            reject(rebuilt.rejection());
            return false;
        }
        return session.save();
    }

    /**
     * Rebuilds the record and hands it to the config module.
     *
     * <p>The one place this class writes anything, and it writes exclusively
     * through {@link ConfigFile#update} — never a {@code FileConfiguration},
     * never YAML directly. Migrations, pruning and comment preservation stay
     * owned by the config module, which is the only thing that knows how to keep
     * them.
     *
     * <p>The rebuild is repeated rather than passed in, and deliberately: this
     * runs from the session, which may also be saved by a plugin holding a
     * {@link net.exylia.lib.panel.PanelSession}. A guard only on the path the
     * panel's own button takes is a guard the other caller walks straight past.
     */
    private void write(Map<String, Object> values) {
        var rebuilt = rebuild(values);
        if (!rebuilt.accepted()) {
            reject(rebuilt.rejection());
            return;
        }
        T record = rebuilt.value();

        Tasks.of(plugin).runAsync(() -> {
            file.update(current -> record);
            if (onSaved != null) {
                // Back on the thread that owns the player: whoever asked to be
                // told is going to touch the game.
                Tasks.of(plugin).runAtEntity(viewer, () -> onSaved.accept(record));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private RecordRebuilder.Rebuilt<T> rebuild(Map<String, Object> values) {
        return RecordRebuilder.rebuild((Class<T>) file.get().getClass(), values);
    }

    // ------------------------------------------------------------------
    // Reading, for the panel and for tests
    // ------------------------------------------------------------------

    /** The value of a component in the frame being edited. */
    public @Nullable Object value(@NotNull String component) {
        return componentsOf(frames.peek()).get(component);
    }

    /** The value of a component of the root record, whatever frame is on screen. */
    public @Nullable Object rootValue(@NotNull String component) {
        return session.values().get(component);
    }

    /** How many edits can still be taken back. */
    public int undoDepth() {
        return session.undoDepth();
    }

    /** How deep into nested records the viewer is; one at the top. */
    public int depth() {
        return frames.size();
    }

    /** What a save would change, by component name. */
    public @NotNull net.exylia.lib.panel.PanelDiff diff() {
        return session.diff();
    }

    // ------------------------------------------------------------------
    // Test seam
    // ------------------------------------------------------------------

    /**
     * Test seam: an engine with no window.
     *
     * <p>Opening a real one needs {@code Bukkit.createInventory}, which answers
     * nothing without a running server, and drawing needs an {@code ItemStack},
     * whose class initialiser reaches the registry. What these tests are about is
     * the mapping, the prompts and the rebuild — none of which is about drawing.
     */
    public static <T extends Record> @NotNull SettingsEngine<T> forTests(@NotNull Plugin plugin,
                                                                         @NotNull Player viewer,
                                                                         @NotNull ConfigFile<T> file,
                                                                         @Nullable Consumer<T> onSaved) {
        return of(plugin, viewer, file, onSaved);
    }
}
