package net.exylia.lib.hologram.internal;

import net.exylia.lib.hologram.Hologram;
import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.interaction.Interaction;
import net.exylia.lib.interaction.InteractionToken;
import net.exylia.lib.interaction.Interactions;
import net.exylia.lib.item.internal.CustomItems;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Request;
import net.exylia.lib.placeholder.Template;
import net.exylia.lib.skull.SkullHandle;
import net.exylia.lib.skull.SkullSource;
import net.exylia.lib.skull.Skulls;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.internal.Conditions;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * One hologram: its displays, its viewers, and what each of them has been sent.
 *
 * <p>Three things keep this cheap. A player only receives packets when they
 * cross the view distance, not every tick. A line is only re-sent when its
 * rendered text actually changes. And what gets parsed is the line's raw
 * template text, which never changes, with the resolved values substituted into
 * that parsed component: parsing the resolved string instead costs six times
 * more, measured in the scoreboard module.
 *
 * <p>A hologram whose lines have no placeholders never schedules a refresh at
 * all, so a sign that says "Spawn" costs exactly one packet per viewer, once.
 *
 * <p>Line tags ({@link LineStyle}) add three costs, each paid only by the
 * holograms that use them. A line with a condition or a range makes every
 * viewer's set of visible lines checked on the visibility pass, and the stack
 * re-laid out for them alone when it changes. An animated line sends one
 * transformation every {@value #STEP_MS} ms, which the client glides through on
 * its own. A typewriter line sends its growing text to a viewer only while it
 * is being typed for them.
 */
final class HologramImpl implements Hologram {

    private static final long TICK_MS = 50L;

    /** How far apart animation keyframes are; the client interpolates between them. */
    static final long STEP_MS = 500L;

    /**
     * How long each keyframe is glided over: one tick longer than a step. Steps are counted on
     * the clock but sent on ticks, so they arrive 10 or 11 ticks apart; a glide of exactly one
     * step ran out before an 11-tick gap and the line stood still for a tick. Gliding a tick
     * longer, towards where the line will be by then, means the next keyframe always takes over
     * while it is still moving.
     */
    static final long GLIDE_MS = STEP_MS + TICK_MS;
    private static final int GLIDE_TICKS = (int) (GLIDE_MS / TICK_MS);

    /**
     * How far apart shining and flowing frames are. A flowing line is re-sent whole every frame to
     * every viewer, so it runs at 10 fps rather than the driver's 20: still smooth, half the traffic.
     */
    static final long SHIMMER_MS = 100L;

    /** Ticks a hover grows or shrinks over. */
    private static final int HOVER_TICKS = 3;
    /** Ticks a distance step glides over: one visibility pass, so the size never stops between steps. */
    private static final int SCALE_TICKS = 5;
    /** How finely the size by distance is quantised; a viewer is re-sent it only when it moves a step. */
    private static final double SCALE_STEP = 0.1;
    /** How small a button gets while pressed, and for how long. */
    private static final double PRESS_SCALE = 0.92;
    private static final long PRESS_MS = 120L;
    private static final int PRESS_TICKS = 1;

    /**
     * A text display draws one pixel as 1/40 block at scale 1. Its background
     * is {@code width + 1} pixels wide, from {@code -width/2} to
     * {@code width/2 + 1} around the entity, and {@code 10 * lines + 1} pixels
     * tall from the entity up: the vanilla renderer's own numbers, which is
     * what lets a panel be sized to the text it stands behind.
     */
    static final double PX = 0.025;
    /** The panel is one space: 4 pixels of advance, so 5 by 11 pixels of background. */
    private static final double PANEL_PX_W = 5;
    private static final double PANEL_PX_H = 11;
    /** Room around what the panel covers, in blocks. */
    private static final double PANEL_PAD = 0.07;
    /**
     * How far behind the lines the panel stands, past any item or block in the
     * stack. The lines of a panelled hologram turn upright, as the panel does,
     * so nothing leans through it and a hair of depth is enough: no parallax.
     */
    private static final double PANEL_DEPTH = 0.03;
    private static final Component PLATE_TEXT = Component.text(" ");
    /** Alpha of a colour written without one, for a button line: at rest, aimed at, pressed. */
    private static final int BUTTON_ALPHA = 0x59;
    private static final int BUTTON_HOVER_ALPHA = 0xCC;
    /** The same for the panel behind a whole hologram, which is larger and so quieter. */
    private static final int PANEL_ALPHA = 0x40;
    private static final int PANEL_HOVER_ALPHA = 0x73;
    private static final int PRESS_BOOST = 0x33;
    /** Spaces either side of a button's label, so its background is a button and not a highlight. */
    private static final int BUTTON_PAD_SPACES = 2;
    /** How a click target is lit. */
    private static final int IDLE = 0;
    private static final int HOVERED = 1;
    private static final int PRESSED = 2;

    /** No click target, the whole hologram, or else the index of a button line. */
    private static final int NONE = -2;
    private static final int WHOLE = -1;
    /** Eye height of a standing player; ponytail: sneaking reads a little high. */
    private static final double EYE_HEIGHT = 1.62;

    /** Shortest gap between two clicks, which also swallows a double-fired press. */
    private static final long MIN_CLICK_GAP_MS = 200L;

    /** Unreadable conditions already reported, so each is logged once. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private final String id;
    private final Plugin owner;
    private final String ownerName;
    private final HologramConfig config;

    /** One display per line for text, a single one for items and blocks. */
    private volatile DisplayState[] displays;
    private volatile Template[] templates;
    /** How each text line is drawn, from the tags at its start. */
    private volatile LineStyle[] styles;
    /** Whether any line moves, is typed in, or is shown to some viewers only. */
    private volatile boolean animated;
    private volatile boolean typed;
    private volatile boolean conditional;
    private volatile boolean shimmering;
    /** Per line, the frame of its moving text last sent, so a still frame is not re-sent. */
    private volatile long[] lastKey = new long[0];
    /** Per line, the opacity a fading line was last sent at. */
    private volatile int[] lastAlpha = new int[0];
    /** Whether any line or the hologram grows with the viewer's distance. */
    private volatile boolean scaling;
    /** Whether any line is a button with click lines of its own. */
    private volatile boolean hasButtons;
    /** Whether a press is waiting to spring back for some viewer. */
    private volatile boolean pressing;
    /** The scramble frame last drawn, so scrambling runs at the shimmer rate. */
    private volatile long lastScramble = -1;

    /** What each viewer is shown beyond the lines themselves: hover, press, size by distance. */
    private final Map<UUID, Viewer> states = new ConcurrentHashMap<>();

    /** One viewer's view of the hologram's controls and size. */
    private static final class Viewer {
        /** What they look at: {@link #NONE}, {@link #WHOLE} or a button line. */
        volatile int hover = NONE;
        /** What they pressed and when, until it springs back. */
        volatile int pressed = NONE;
        volatile long pressedAt;
        /** The hologram's size by distance, in {@link #SCALE_STEP}s. */
        volatile int step = (int) Math.round(1 / SCALE_STEP);
        /** Each {@code <autoscale>} line's size, in steps. */
        volatile int[] lineSteps = new int[0];
    }

    /** Which lines each viewer is shown, for holograms with conditional lines. */
    private final Map<UUID, boolean[]> masks = new ConcurrentHashMap<>();
    /** When each line started being typed for each viewer; 0 once it is whole. */
    private final Map<UUID, long[]> typing = new ConcurrentHashMap<>();
    private volatile long lastFrame = Long.MIN_VALUE;
    private volatile long lastShimmer = -SHIMMER_MS;

    /**
     * Pages: every page's lines are one stack, and a viewer is shown the lines
     * of their page only, through the same per-viewer mask that hides a
     * conditional line. Turning a page is a mask change: the old page's lines
     * are destroyed, the new ones spawned, and the click box re-sized.
     */
    private volatile List<List<String>> pageLines;
    /** Which page each line of the stack belongs to. */
    private volatile int[] linePage = new int[0];
    private volatile int pageCount = 1;
    /** The page each player is on; absent is the first. */
    private final Map<UUID, Integer> pageOf = new ConcurrentHashMap<>();
    /** When each viewer last turned a page, for the automatic turn. */
    private final Map<UUID, Long> turnedAt = new ConcurrentHashMap<>();
    /** Whether a viewer's page changed and the stack has not been re-laid for it. */
    private volatile boolean turned;
    private final long pageIntervalMs;

    /** The invisible box clicks land on; only spawned when it is clickable. */
    private final int hitboxId;
    /** What a click does, per page. */
    private final Interaction[] clickLines;
    /** What clicking a {@code <button:name>} line does, by name. */
    private final Map<String, Interaction> buttonLines;
    /** The panel behind the whole hologram, or {@code null} when it can never have one. */
    private final DisplayState plate;
    /** Whether the panel is drawn: the whole hologram is the button, and no line is a button of its own. */
    private volatile boolean paneled;
    /** Colours as ARGB: the panel at rest, aimed at and pressed, a button line aimed at and pressed. */
    private final int panelIdle;
    private final int panelHover;
    private final int panelPress;
    private final int buttonIdle;
    private final int buttonHover;
    private final int buttonPress;
    /** Per line, the spaces either side of a button's label; 0 for any other line. */
    private volatile int[] padSpaces = new int[0];
    private volatile BiConsumer<Player, Click> clickHandler;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    /** What each display currently shows, for the diff. */
    private volatile String[] lastText;

    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    /** The players the last visibility check found, for the frames drawn between checks. */
    private volatile List<Player> lastViewers = List.of();

    private volatile Location location;
    private volatile Entity mount;
    private volatile Map<String, Object> data = Map.of();
    private volatile Predicate<Player> filter;
    private volatile boolean removed;

    private final long intervalMs;
    /** Whether the text resolves to something new on its own. */
    private volatile boolean dynamic;
    /** Whether the text was changed and has not been drawn since. */
    private volatile boolean pending;
    private volatile long lastRender;

    HologramImpl(String id, Plugin owner, HologramConfig config, Location location) {
        this.id = id;
        this.owner = owner;
        this.ownerName = owner.getName();
        this.config = config;
        this.location = location.clone().add(config.offsetX(), config.offsetY(), config.offsetZ());
        this.intervalMs = Math.max(TICK_MS, config.config().updateInterval() * TICK_MS);
        this.pageIntervalMs = config.pageInterval() * TICK_MS;
        Map<String, InteractionToken> tokens =
                Map.of("PAGE", (player, click, argument, values) -> turn(player, argument));
        this.clickLines = compileClicks(owner, id, config, tokens);
        this.buttonLines = compileButtons(owner, id, config, tokens);
        List<String> lines = paginate(config.allPages());
        this.panelIdle = argb(config.click().color(), PANEL_ALPHA);
        this.panelHover = argb(config.click().hoverColor(), PANEL_HOVER_ALPHA);
        this.panelPress = pressed(panelHover);
        this.buttonIdle = argb(config.click().color(), BUTTON_ALPHA);
        this.buttonHover = argb(config.click().hoverColor(), BUTTON_HOVER_ALPHA);
        this.buttonPress = pressed(buttonHover);
        boolean wholeLines = Arrays.stream(clickLines).anyMatch(compiled -> !compiled.isEmpty());
        this.plate = wholeLines && buttonLook() ? newPlate() : null;
        restyle(styles(lines));
        this.templates = compile(lines);
        this.padSpaces = pads(styles, templates);
        this.displays = build(styles);
        resolveHeads(displays);
        this.hitboxId = HologramRuntime.newEntityId();
        this.lastText = new String[displays.length];
        this.dynamic = config.config().autoUpdate() && isDynamic(templates);
        this.lastRender = -intervalMs;
    }

    private static Interaction[] compileClicks(Plugin owner, String id, HologramConfig config,
                                               Map<String, InteractionToken> tokens) {
        List<HologramConfig.Page> pages = config.pages();
        Interaction[] compiled = new Interaction[pages.size() + 1];
        compiled[0] = Interactions.compile(owner, sounded(config, config.click().commands()),
                "hologram \"" + id + "\"", tokens);
        for (int i = 0; i < pages.size(); i++) {
            compiled[i + 1] = Interactions.compile(owner, sounded(config, pages.get(i).click()),
                    "hologram \"" + id + "\" page " + (i + 2), tokens);
        }
        return compiled;
    }

    private static Map<String, Interaction> compileButtons(Plugin owner, String id,
                                                           HologramConfig config,
                                                           Map<String, InteractionToken> tokens) {
        Map<String, Interaction> compiled = new java.util.HashMap<>();
        config.click().buttons().forEach((name, lines) -> {
            Interaction interaction = Interactions.compile(owner, sounded(config, lines),
                    "hologram \"" + id + "\" button " + name, tokens);
            if (!interaction.isEmpty()) {
                compiled.put(name, interaction);
            }
        });
        return Map.copyOf(compiled);
    }

    /** Click lines with the click sound in front, when there are lines to click and a sound. */
    private static List<String> sounded(HologramConfig config, List<String> lines) {
        String sound = config.click().sound();
        if (sound.isEmpty() || lines.isEmpty()) {
            return lines;
        }
        List<String> all = new ArrayList<>(lines.size() + 1);
        all.add("[SOUND] " + sound);
        all.addAll(lines);
        return all;
    }

    /** Whether controls are drawn as buttons: the style asks for it and there is text to put them behind. */
    private boolean buttonLook() {
        return config.click().buttonStyle() && config.type() == HologramConfig.Kind.TEXT;
    }

    /** A colour pressed: the same colour, more opaque. */
    private static int pressed(int argb) {
        return Math.min(0xFF, (argb >>> 24) + PRESS_BOOST) << 24 | (argb & 0xFFFFFF);
    }

    /** The panel: one space of text whose background is stretched over what it covers. */
    private DisplayState newPlate() {
        HologramConfig.Properties base = config.properties();
        // Upright, like the lines in front of it, so the two never part.
        String turn = "FIXED".equalsIgnoreCase(base.billboard()) ? "FIXED" : "VERTICAL";
        HologramConfig.Properties look = new HologramConfig.Properties(turn, "CENTER", 1, 1, 1,
                false, false, 200, base.lineSpacing(), 255, false,
                String.format("#%08X", panelIdle), 15, false);
        return new DisplayState(HologramRuntime.newEntityId(), HologramConfig.Kind.TEXT, look);
    }

    private boolean isButton(LineStyle style) {
        return style.button() != null && buttonLines.containsKey(style.button());
    }

    /** Whether a line is drawn as a button: its own background, lit when aimed at. */
    private boolean drawnAsButton(LineStyle style) {
        return buttonLook() && isButton(style);
    }

    /**
     * How a text line is drawn once the button look is applied.
     *
     * <p>A button line's background is its button: the client sizes it to the
     * text, so it lines up with its label at any size, hover and tilt. A line
     * in front of the panel loses its background, or there would be two, and
     * turns upright as the panel does. A line's own {@code <background>} or
     * {@code <billboard>} tag is kept either way.
     */
    private HologramConfig.Properties dressed(HologramConfig.Properties look, LineStyle style) {
        if (drawnAsButton(style)) {
            String background = style.background() != null ? look.backgroundColor()
                    : String.format("#%08X", buttonIdle);
            return restyled(look, look.billboard(), background);
        }
        if (paneled) {
            String turn = style.billboard() == null && "CENTER".equalsIgnoreCase(look.billboard())
                    ? "VERTICAL" : look.billboard();
            String background = style.background() != null ? look.backgroundColor() : "#00000000";
            return restyled(look, turn, background);
        }
        return look;
    }

    private static HologramConfig.Properties restyled(HologramConfig.Properties look, String billboard,
                                                      String background) {
        return new HologramConfig.Properties(billboard, look.alignment(), look.scaleX(),
                look.scaleY(), look.scaleZ(), look.shadow(), look.seeThrough(), look.lineWidth(),
                look.lineSpacing(), look.textOpacity(), false, background, look.brightness(),
                look.glowing(), look.distanceScale());
    }

    /**
     * The spaces either side of each button's label: a little room, plus what
     * brings it to the widest button's width, so a column of buttons is one
     * size. Measured from the written text, placeholders as written.
     */
    private int[] pads(LineStyle[] lineStyles, Template[] lines) {
        int count = Math.min(lineStyles.length, lines.length);
        int[] widths = new int[count];
        int widest = 0;
        for (int i = 0; i < count; i++) {
            if (drawnAsButton(lineStyles[i])) {
                widths[i] = net.exylia.lib.text.Centering.pixelWidth(Text.component(lines[i].raw()));
                widest = Math.max(widest, widths[i]);
            }
        }
        return buttonPads(lineStyles, widths, widest);
    }

    /** Pads from measured widths; a space is 4 pixels, half the difference goes either side. */
    private int[] buttonPads(LineStyle[] lineStyles, int[] widths, int widest) {
        int[] pads = new int[widths.length];
        for (int i = 0; i < widths.length; i++) {
            if (drawnAsButton(lineStyles[i])) {
                pads[i] = BUTTON_PAD_SPACES + (int) Math.round((widest - widths[i]) / 8.0);
            }
        }
        return pads;
    }

    /**
     * A panel colour as ARGB: a palette name or {@code #RRGGBB} takes
     * {@code alpha}, {@code #AARRGGBB} keeps its own.
     */
    static int argb(String value, int alpha) {
        String token = value.trim();
        if (token.startsWith("#") && token.length() == 9) {
            try {
                return (int) Long.parseLong(token.substring(1), 16);
            } catch (NumberFormatException ignored) {
                // Falls through to the colour reading, which falls back to white.
            }
        }
        return alpha << 24 | Shimmer.colour(token).value();
    }

    /**
     * Lays every page into one stack and remembers which page each line is on.
     *
     * @return the lines of every page, the first page first
     */
    private List<String> paginate(List<List<String>> pages) {
        List<List<String>> kept = pages.isEmpty() ? List.of(List.of()) : List.copyOf(pages);
        List<String> lines = new ArrayList<>();
        int[] owners = new int[kept.stream().mapToInt(List::size).sum()];
        for (int page = 0; page < kept.size(); page++) {
            for (String line : kept.get(page)) {
                owners[lines.size()] = page;
                lines.add(line);
            }
        }
        this.pageLines = kept;
        this.linePage = owners;
        this.pageCount = kept.size();
        return lines;
    }

    /**
     * Gives head lines their textured item.
     *
     * <p>A texture or URL is ready at once. A player name or id draws the
     * fallback head first and shows the real one when its lookup lands, which
     * happens once per head: the skull module remembers it.
     */
    private void resolveHeads(DisplayState[] built) {
        for (DisplayState display : built) {
            String head = display.style().head();
            if (head == null) {
                continue;
            }
            try {
                SkullHandle handle = Skulls.of(SkullSource.parse(head)).build();
                display.item(handle.item());
                if (!handle.isReady()) {
                    handle.onReady(item -> {
                        display.item(item);
                        if (!removed && displays == built) {
                            HologramRuntime.reshow(this);
                        }
                    });
                }
            } catch (RuntimeException | LinkageError failure) {
                if (REPORTED.add("head:" + head)) {
                    HologramRuntime.logger().warning("Hologram '" + id + "': the head '" + head
                            + "' could not be drawn (" + failure.getMessage() + "); showing a plain head.");
                }
            }
        }
    }

    private static Template[] compile(List<String> lines) {
        Template[] templates = new Template[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            templates[i] = Placeholders.compile(LineStyle.parse(lines.get(i)).body());
        }
        return templates;
    }

    private void restyle(LineStyle[] styles) {
        boolean text = config.type() == HologramConfig.Kind.TEXT;
        this.styles = styles;
        this.animated = text && Arrays.stream(styles).anyMatch(LineStyle::animated);
        this.typed = text && Arrays.stream(styles).anyMatch(LineStyle::reveals);
        this.conditional = text && (pageCount > 1 || Arrays.stream(styles).anyMatch(LineStyle::conditional));
        this.shimmering = text && Arrays.stream(styles)
                .anyMatch(style -> style.shimmers() || style.fades());
        this.scaling = config.properties().distanceScale().enabled()
                || text && Arrays.stream(styles).anyMatch(LineStyle::autoscale);
        this.hasButtons = text && Arrays.stream(styles).anyMatch(this::isButton);
        this.paneled = plate != null && !hasButtons;
        long[] keys = new long[styles.length];
        Arrays.fill(keys, Long.MAX_VALUE);
        this.lastKey = keys;
        int[] alphas = new int[styles.length];
        Arrays.fill(alphas, -1);
        this.lastAlpha = alphas;
    }

    private static LineStyle[] styles(List<String> lines) {
        LineStyle[] styles = new LineStyle[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            styles[i] = LineStyle.parse(lines.get(i)).style();
        }
        return styles;
    }

    private static boolean isDynamic(Template[] templates) {
        for (Template template : templates) {
            if (template.isDynamic()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the displays.
     *
     * <p>Text holograms get one display per line, which is what allows a line
     * to change without touching the others. Item and block holograms are a
     * single display. A text line tagged {@code <item:...>} or
     * {@code <block:...>} is an item or block display standing in the stack.
     */
    private DisplayState[] build(LineStyle[] styles) {
        if (config.type() != HologramConfig.Kind.TEXT) {
            Material material = material(config);
            DisplayState only = new DisplayState(HologramRuntime.newEntityId(), config.type(),
                    config.properties(), material);
            if (config.type() == HologramConfig.Kind.ITEM) {
                custom(only, config.item());
            }
            return new DisplayState[]{only};
        }
        DisplayState[] displays = new DisplayState[styles.length];
        for (int i = 0; i < styles.length; i++) {
            LineStyle style = styles[i];
            HologramConfig.Kind kind = !style.isModel() ? HologramConfig.Kind.TEXT
                    : style.block() ? HologramConfig.Kind.BLOCK : HologramConfig.Kind.ITEM;
            HologramConfig.Properties properties = style.apply(config.properties());
            if (kind == HologramConfig.Kind.TEXT) {
                properties = dressed(properties, style);
            }
            displays[i] = new DisplayState(HologramRuntime.newEntityId(), kind, properties,
                    style.isModel() ? material(style.model()) : Material.STONE, style);
            if (kind == HologramConfig.Kind.ITEM) {
                custom(displays[i], style.model());
            }
        }
        return displays;
    }

    /**
     * Shows a resource-pack plugin's item, such as {@code nexo:ruby_sword}, on
     * an item display. Built again whenever the hologram is: a provider that
     * loads late invalidates every hologram when it does.
     */
    private static void custom(DisplayState display, String name) {
        if (CustomItems.isCustom(name)) {
            ItemStack item = CustomItems.item(name);
            if (item != null) {
                display.item(item);
            }
        }
    }

    /**
     * Reads the item or block a hologram shows.
     *
     * <p>A misspelled material is the owner's typo, not a reason to refuse to
     * draw anything: it is reported once and stone is used instead.
     */
    private static Material material(HologramConfig config) {
        return material(config.type() == HologramConfig.Kind.ITEM ? config.item() : config.block());
    }

    private static Material material(String name) {
        if (CustomItems.isCustom(name)) {
            // Drawn from the provider's item instead; this is only what shows
            // while it cannot be had.
            return Material.PAPER;
        }
        Material material = Material.matchMaterial(name);
        if (material == null) {
            HologramRuntime.logger().warning("Unknown material '" + name
                    + "' in a hologram; showing stone instead.");
            return Material.STONE;
        }
        return material;
    }

    // ------------------------------------------------------------------
    // Hologram
    // ------------------------------------------------------------------

    @Override
    public @NotNull String id() {
        return id;
    }

    @Override
    public @NotNull Location location() {
        return location.clone();
    }

    @Override
    public void moveTo(@NotNull Location target) {
        this.location = target.clone().add(config.offsetX(), config.offsetY(), config.offsetZ());
        HologramRuntime.moved(this);
    }

    @Override
    public void attachTo(@Nullable Entity entity) {
        this.mount = entity;
        HologramRuntime.remounted(this);
    }

    @Override
    public void lines(@NotNull List<String> lines) {
        List<List<String>> pages = new ArrayList<>(pageLines);
        pages.set(0, lines == null ? List.of() : lines);
        pages(pages);
    }

    @Override
    public void pages(@NotNull List<List<String>> pages) {
        int[] before = linePage;
        List<String> lines = paginate(pages == null ? List.of() : pages);
        Template[] compiled = compile(lines);
        LineStyle[] restyled = styles(lines);
        // A different number of lines, or lines drawn differently, means
        // different displays, so viewers have to be given the new ones rather
        // than an update for entities that no longer look that way.
        boolean sameShape = java.util.Arrays.equals(restyled, styles)
                && java.util.Arrays.equals(before, linePage)
                && config.type() == HologramConfig.Kind.TEXT;
        restyle(restyled);
        this.templates = compiled;
        this.padSpaces = pads(restyled, compiled);
        // Lines are replaced, so what they resolve from is decided again: text
        // that had a placeholder may no longer have one, and the other way
        // round.
        this.dynamic = config.config().autoUpdate() && isDynamic(compiled);
        if (sameShape) {
            refresh();
            return;
        }
        HologramRuntime.rebuild(this);
    }

    @Override
    public int pageCount() {
        return pageCount;
    }

    @Override
    public int page(@NotNull Player player) {
        return Math.min(pageOf.getOrDefault(player.getUniqueId(), 0), pageCount - 1);
    }

    @Override
    public void page(@NotNull Player player, int page) {
        int target = Math.clamp(page, 0, pageCount - 1);
        UUID uuid = player.getUniqueId();
        turnedAt.put(uuid, HologramRuntime.now());
        Integer was = pageOf.put(uuid, target);
        if (was == null ? target != 0 : was != target) {
            turned = true;
        }
    }

    /** What a {@code [PAGE]} click line does: next, previous, first, last, or a page from 1. */
    private void turn(Player player, String argument) {
        String to = argument.trim().toLowerCase(Locale.ROOT);
        switch (to) {
            case "", "next" -> nextPage(player);
            case "previous", "prev", "back" -> previousPage(player);
            case "first" -> page(player, 0);
            case "last" -> page(player, pageCount - 1);
            default -> {
                try {
                    page(player, Integer.parseInt(to) - 1);
                } catch (NumberFormatException notANumber) {
                    if (REPORTED.add("page:" + id + ":" + to)) {
                        HologramRuntime.logger().warning("Hologram '" + id + "': [PAGE] " + argument
                                + " is not next, previous, first, last or a page number.");
                    }
                }
            }
        }
    }

    /**
     * Turns viewers whose page is due on their own, then re-lays the stack for
     * whoever's page changed. Runs on the driver, as every layout does.
     */
    private void turnPages(List<Player> currentViewers, long now) {
        if (pageIntervalMs > 0) {
            for (Player viewer : currentViewers) {
                Long at = turnedAt.putIfAbsent(viewer.getUniqueId(), now);
                if (at != null && now - at >= pageIntervalMs) {
                    nextPage(viewer);
                }
            }
        }
        if (!turned) {
            return;
        }
        turned = false;
        for (Player viewer : currentViewers) {
            revalidate(viewer);
        }
    }

    @Override
    public void refresh() {
        pending = true;
        lastRender = -intervalMs;
    }

    @Override
    public void onClick(@Nullable BiConsumer<Player, Click> handler) {
        boolean was = clickable();
        boolean will = handler != null || hasButtons
                || Arrays.stream(clickLines).anyMatch(lines -> !lines.isEmpty());
        if (was != will) {
            // The box is spawned with the hologram, so viewers are given it
            // anew; taken down first, while it still counts as there.
            HologramRuntime.reshow(this);
        }
        this.clickHandler = handler;
    }

    @Override
    public void updateData(@NotNull Map<String, Object> data) {
        this.data = data == null ? Map.of() : data;
        refresh();
    }

    /** The filter a hologram is born with, set before anything can draw it. */
    void initialFilter(@Nullable Predicate<Player> filter) {
        this.filter = filter;
    }

    @Override
    public void visibleIf(@Nullable Predicate<Player> filter) {
        this.filter = filter;
        HologramRuntime.visibilityChanged(this);
    }

    @Override
    public boolean isViewing(@NotNull Player player) {
        return viewers.contains(player.getUniqueId());
    }

    @Override
    public int viewerCount() {
        return viewers.size();
    }

    @Override
    public void remove() {
        HologramRuntime.remove(this);
    }

    @Override
    public boolean removed() {
        return removed;
    }

    // ------------------------------------------------------------------
    // Engine, driven by HologramRuntime
    // ------------------------------------------------------------------

    /** Returns whether a player should be able to see this hologram now. */
    boolean canSee(Player player) {
        if (removed || !player.isOnline()) {
            return false;
        }
        Location at = anchor();
        if (player.getWorld() != at.getWorld()) {
            return false;
        }
        double range = config.viewDistance();
        // Squared, so a player walking past does not pay for a square root.
        // Before the filter: it is the cheap check, and a caller's filter may not be.
        if (player.getLocation().distanceSquared(at) > range * range) {
            return false;
        }
        Predicate<Player> current = filter;
        return current == null || current.test(player);
    }

    /**
     * Whether this hologram has anything worth refreshing.
     *
     * <p>Text that resolves to something new on its own is redrawn on the
     * interval. Plain text is not, because it would render the same string
     * every time — but a caller who changes it, through {@link #lines} or
     * {@link #updateData}, has made it different exactly once, and that one
     * render still has to happen.
     */
    boolean refreshes() {
        return dynamic || pending;
    }

    boolean due(long now) {
        return now - lastRender >= intervalMs;
    }

    /**
     * Renders the lines and sends the ones that changed.
     *
     * <p>For a shared hologram this happens once and the result goes to every
     * viewer. A per-player hologram renders for each viewer instead, which is
     * what makes its placeholders personal and also what makes it cost more.
     */
    void render(List<Player> currentViewers) {
        if (removed) {
            return;
        }
        lastRender = HologramRuntime.now();
        pending = false;

        if (config.type() != HologramConfig.Kind.TEXT || templates.length == 0) {
            return;
        }
        if (config.perPlayer()) {
            for (Player viewer : currentViewers) {
                renderFor(viewer, true);
            }
            return;
        }
        renderShared(currentViewers);
    }

    /** One render, sent to everybody: the cheap path, and the default. */
    private void renderShared(List<Player> currentViewers) {
        DisplayState[] current = displays;
        Template[] lines = templates;
        String[] previous = lastText;
        boolean changed = false;

        for (int i = 0; i < current.length && i < lines.length; i++) {
            if (current[i].kind() != HologramConfig.Kind.TEXT) {
                continue;
            }
            String text = lines[i].render(null, data);
            if (text.equals(previous[i])) {
                continue;
            }
            previous[i] = text;
            changed = true;
            current[i].text(componentFor(i, lines[i], text, null));
            Component shown = Shimmer.dress(current[i].style(), current[i].text(), seconds());
            for (Player viewer : currentViewers) {
                if (receivesText(viewer.getUniqueId(), i)) {
                    HologramRuntime.sink().text(viewer, current[i], shown);
                }
            }
        }
        if (changed && paneled) {
            for (Player viewer : currentViewers) {
                reshapePanel(viewer);
            }
        }
    }

    /** Fits a viewer's panel to their lines again, for text that changed width. */
    private void reshapePanel(Player viewer) {
        if (wholePlated(viewer) && viewers.contains(viewer.getUniqueId())) {
            HologramRuntime.sink().transform(viewer, plate,
                    wholePlateView(viewer, layout(viewer.getUniqueId(), masks.get(viewer.getUniqueId()))),
                    HOVER_TICKS);
        }
    }

    /**
     * Renders for one player.
     *
     * <p>Used by per-player holograms, and when a viewer first comes into
     * range: what they are sent has to be resolved for them.
     */
    private void renderFor(Player viewer, boolean send) {
        DisplayState[] current = displays;
        Template[] lines = templates;
        for (int i = 0; i < current.length && i < lines.length; i++) {
            if (current[i].kind() != HologramConfig.Kind.TEXT) {
                continue;
            }
            String text = lines[i].render(viewer, data);
            current[i].text(componentFor(i, lines[i], text, viewer));
            if (send && receivesText(viewer.getUniqueId(), i)) {
                HologramRuntime.sink().text(viewer, current[i],
                        Shimmer.dress(current[i].style(), current[i].text(), seconds()));
            }
        }
        if (send && paneled) {
            reshapePanel(viewer);
        }
    }

    /**
     * Whether a viewer is sent a line's text as it changes: not when the line
     * is hidden from them, and not while it is still being typed for them,
     * which would show it whole too early.
     */
    private boolean receivesText(UUID viewer, int line) {
        long[] started = typing.get(viewer);
        return shown(masks.get(viewer), line)
                && (started == null || line >= started.length || started[line] == 0);
    }

    private static double seconds() {
        return HologramRuntime.now() / 1000.0;
    }

    /** The whole text a viewer sees on a line. */
    private Component textFor(Player viewer, int line) {
        if (!config.perPlayer()) {
            return displays[line].text();
        }
        Template template = templates[line];
        return componentFor(line, template, template.render(viewer, data), viewer);
    }

    /**
     * Builds the component for a line.
     *
     * <p>The raw template text is what gets parsed, because it never changes
     * and is therefore always a cache hit, and the resolved values are
     * substituted into that parsed component.
     */
    private Component componentFor(int line, Template template, String text, Player viewer) {
        Component parsed = parse(template, text, viewer);
        int[] pads = padSpaces;
        if (line >= pads.length || pads[line] == 0) {
            return parsed;
        }
        // Unstyled spaces either side, so a bold label does not widen its padding.
        Component pad = Component.text(" ".repeat(pads[line]));
        return Component.text().append(pad, parsed, pad).build();
    }

    private Component parse(Template template, String text, Player viewer) {
        if (!template.isDynamic()) {
            return Text.component(text);
        }
        List<String> pairs = Placeholders.resolvePairs(template,
                new Request(viewer, viewer, List.of(), data));
        if (pairs.isEmpty()) {
            return Text.component(text);
        }
        return Text.component(template.raw(), pairs);
    }

    /** Sends the whole hologram to a player who just came into range. */
    void spawnFor(Player viewer) {
        if (removed) {
            return;
        }
        DisplayState[] current = displays;
        if (config.type() == HologramConfig.Kind.TEXT) {
            // Resolve for this viewer before spawning: a shared hologram still
            // has to show them something, and a per-player one shows them
            // theirs.
            renderFor(viewer, false);
        }
        UUID uuid = viewer.getUniqueId();
        if (pageIntervalMs > 0) {
            turnedAt.put(uuid, HologramRuntime.now());
        }
        boolean[] mask = conditional ? maskFor(viewer) : null;
        if (mask != null) {
            masks.put(uuid, mask);
        }
        Viewer state = new Viewer();
        states.put(uuid, state);
        measure(viewer, state);
        long now = HologramRuntime.now();
        beginTyping(uuid, null, mask, now);
        placeLines(viewer, current, null, mask, now);
        spawnControls(viewer, mask);
        // Recorded before the mount, so a vehicle that reaches the client right
        // after is answered by vehicleShown instead of falling between the two.
        viewers.add(uuid);
        Entity vehicle = mount;
        if (vehicle != null) {
            HologramRuntime.sink().mount(viewer, vehicle.getEntityId(), entityIdsWithHitbox());
        }
    }

    /**
     * The client of a viewer was just sent an entity: if it is the one this
     * hologram rides, it arrived without passengers, so they are given back.
     *
     * <p>Covers a viewer who starts tracking the vehicle after the hologram was
     * sent to them, and a vehicle that respawns or comes back from another world.
     */
    void vehicleShown(Player viewer, int entityId) {
        Entity vehicle = mount;
        if (vehicle != null && vehicle.getEntityId() == entityId
                && viewers.contains(viewer.getUniqueId())) {
            HologramRuntime.sink().mount(viewer, entityId, entityIdsWithHitbox());
        }
    }

    /**
     * Re-checks which lines a viewer who stays in range is shown.
     *
     * <p>Called on every visibility pass, and free for holograms without a
     * conditional line. When the set changes, lines that went away are
     * destroyed, new ones are spawned, and the ones that stay are moved to
     * where the new stack puts them, so nothing flickers.
     */
    void revalidate(Player viewer) {
        if (!conditional || removed) {
            return;
        }
        UUID uuid = viewer.getUniqueId();
        boolean[] before = masks.get(uuid);
        if (before == null) {
            return;
        }
        boolean[] after = maskFor(viewer);
        if (Arrays.equals(before, after)) {
            return;
        }
        masks.put(uuid, after);
        DisplayState[] current = displays;
        List<Integer> hidden = new ArrayList<>();
        boolean revealed = false;
        for (int i = 0; i < current.length; i++) {
            if (shown(before, i) && !shown(after, i)) {
                hidden.add(current[i].entityId());
            }
            revealed |= !shown(before, i) && shown(after, i);
        }
        if (!hidden.isEmpty()) {
            HologramRuntime.sink().destroy(viewer,
                    hidden.stream().mapToInt(Integer::intValue).toArray());
        }
        if (revealed && config.type() == HologramConfig.Kind.TEXT) {
            renderFor(viewer, false);
        }
        long now = HologramRuntime.now();
        beginTyping(uuid, before, after, now);
        placeLines(viewer, current, before, after, now);
        if (clickable()) {
            // The box and the panels are as tall as what this viewer sees.
            HologramRuntime.sink().destroy(viewer, controlIds());
            spawnControls(viewer, after);
        }
    }

    /**
     * Which lines a viewer is shown, by range, permission and condition.
     *
     * <p>Runs off the main thread, like every placeholder in a hologram line.
     */
    private boolean[] maskFor(Player viewer) {
        LineStyle[] current = styles;
        int[] pages = linePage;
        int page = page(viewer);
        boolean[] mask = new boolean[current.length];
        double distance = viewer.getLocation().distanceSquared(anchor());
        for (int i = 0; i < current.length; i++) {
            LineStyle style = current[i];
            boolean shown = (i >= pages.length || pages[i] == page)
                    && (style.range() <= 0 || distance <= style.range() * style.range());
            String permission = style.permission();
            if (shown && permission != null) {
                boolean negated = permission.startsWith("!");
                boolean has = viewer.hasPermission(negated ? permission.substring(1) : permission);
                shown = negated != has;
            }
            if (shown && style.condition() != null) {
                shown = Conditions.holds(style.condition(), viewer, (subject, message) -> {
                    if (REPORTED.add(subject)) {
                        HologramRuntime.logger().warning("Hologram '" + id + "': " + message);
                    }
                });
            }
            mask[i] = shown;
        }
        return mask;
    }

    private static boolean shown(boolean[] mask, int line) {
        return mask == null || (line < mask.length && mask[line]);
    }

    /**
     * Spawns the lines a viewer gained and moves the ones they already had.
     *
     * @param before what they were shown, or {@code null} when they had nothing
     * @param after  what they are shown now, or {@code null} for every line
     */
    private void placeLines(Player viewer, DisplayState[] current, boolean[] before,
                            boolean[] after, long now) {
        Location at = anchor();
        UUID uuid = viewer.getUniqueId();
        Layout layout = layout(uuid, after);
        boolean moved = mount != null || layout.grown();
        for (int i = 0; i < current.length; i++) {
            if (!shown(after, i)) {
                continue;
            }
            Location where = mount != null ? at : at.clone().add(0, layout.baseY()[i], 0);
            if (before != null && shown(before, i)) {
                HologramRuntime.sink().teleport(viewer, current[i].entityId(), where);
                if (moved) {
                    // Riding, a line's place is its translation; grown, it is
                    // spread by it. Either way the new stack moves it.
                    HologramRuntime.sink().transform(viewer, current[i],
                            viewOf(current[i], i, layout), HOVER_TICKS);
                }
                continue;
            }
            current[i].transform(current[i].transformAt(now));
            Component text = receivesText(uuid, i)
                    ? Shimmer.dress(current[i].style(), current[i].text(), now / 1000.0)
                    : Component.empty();
            HologramRuntime.sink().spawn(viewer, current[i], where, text,
                    viewOf(current[i], i, layout));
        }
    }

    /** Starts the typewriter on the typed lines a viewer was just given. */
    private void beginTyping(UUID viewer, boolean[] before, boolean[] after, long now) {
        if (!typed) {
            return;
        }
        LineStyle[] current = styles;
        long[] started = typing.computeIfAbsent(viewer, key -> new long[current.length]);
        if (started.length != current.length) {
            started = new long[current.length];
            typing.put(viewer, started);
        }
        boolean any = false;
        for (int i = 0; i < current.length; i++) {
            if (current[i].reveals() && shown(after, i) && !(before != null && shown(before, i))) {
                started[i] = Math.max(1, now);
            }
            any |= started[i] != 0;
        }
        if (!any) {
            typing.remove(viewer);
        }
    }

    /**
     * Moves the animations and the typewriter on, for everybody in range.
     *
     * <p>A transformation is sent once a keyframe, aimed at the next one, and
     * the client glides there on its own: a spinning block costs two packets a
     * second per viewer, not twenty.
     */
    void animate(List<Player> currentViewers, long now) {
        if (removed || currentViewers.isEmpty()) {
            return;
        }
        if (pageCount > 1) {
            turnPages(currentViewers, now);
        }
        if (pressing) {
            release(currentViewers, now);
        }
        if (!typing.isEmpty()) {
            type(currentViewers, now);
        }
        if (shimmering && now - lastShimmer >= SHIMMER_MS) {
            lastShimmer = now;
            shimmer(currentViewers, now);
        }
        if (!animated) {
            return;
        }
        long frame = now / STEP_MS;
        if (frame == lastFrame) {
            return;
        }
        lastFrame = frame;
        long target = now + GLIDE_MS;
        DisplayState[] current = displays;
        for (DisplayState display : current) {
            if (display.style().animated()) {
                display.transform(display.transformAt(target));
            }
        }
        for (Player viewer : currentViewers) {
            UUID uuid = viewer.getUniqueId();
            boolean[] mask = masks.get(uuid);
            Layout layout = layout(uuid, mask);
            for (int i = 0; i < current.length; i++) {
                if (current[i].style().animated() && shown(mask, i)) {
                    HologramRuntime.sink().transform(viewer, current[i],
                            viewOf(current[i], i, layout), GLIDE_TICKS);
                }
            }
        }
    }

    /**
     * Moves text that changes with the clock on: shining, flowing and rainbow
     * colours, frames, a marquee, a blink, and a fading opacity.
     *
     * <p>A line is only re-sent when its frame differs from the last one: a
     * flowing line every pass, a shining one while its band crosses it plus
     * once to put the plain line back, frames and blinks when they turn, a
     * fade when its opacity moves a step. A shared hologram works the frame
     * out once for everybody.
     */
    private void shimmer(List<Player> currentViewers, long now) {
        DisplayState[] current = displays;
        LineStyle[] lineStyles = styles;
        long[] keys = lastKey;
        int[] alphas = lastAlpha;
        double seconds = now / 1000.0;
        int count = Math.min(Math.min(current.length, lineStyles.length), keys.length);
        for (int i = 0; i < count; i++) {
            LineStyle style = lineStyles[i];
            if (current[i].kind() != HologramConfig.Kind.TEXT) {
                continue;
            }
            if (style.fades()) {
                fade(currentViewers, current[i], i, style, alphas, seconds);
            }
            if (!style.shimmers()) {
                continue;
            }
            long key = Shimmer.key(style, current[i].text(), seconds);
            if (key != Shimmer.MOVING && key == keys[i]) {
                continue;
            }
            keys[i] = key;
            Component shared = config.perPlayer() ? null : Shimmer.dress(style, current[i].text(), seconds);
            for (Player viewer : currentViewers) {
                if (!receivesText(viewer.getUniqueId(), i)) {
                    continue;
                }
                Component frame = shared != null ? shared
                        : Shimmer.dress(style, textFor(viewer, i), seconds);
                HologramRuntime.sink().text(viewer, current[i], frame);
            }
        }
    }

    private void fade(List<Player> currentViewers, DisplayState display, int line, LineStyle style,
                      int[] alphas, double seconds) {
        int alpha = Shimmer.fadeOpacity(display.properties().textOpacity(), style.fade(), seconds);
        if (alpha == alphas[line]) {
            return;
        }
        alphas[line] = alpha;
        int background = display.backgroundArgb();
        for (Player viewer : currentViewers) {
            if (shown(masks.get(viewer.getUniqueId()), line)) {
                HologramRuntime.sink().look(viewer, display, alpha, background);
            }
        }
    }

    /**
     * Where each line stands for one viewer, and how big it is drawn.
     *
     * @param y        how high above the anchor each line stands as drawn
     * @param baseY    where it stands before anything grows it, which is
     *                 where it is spawned
     * @param grow     how much bigger than its own size each line is drawn
     * @param top      the top of the stack as drawn
     * @param baseTop  the top of the stack before anything grows it
     * @param whole    how much the hologram as a whole is grown, which is what
     *                 its panel follows
     */
    private record Layout(double[] y, double[] baseY, double[] grow, double top, double baseTop,
                          double whole) {

        /** Whether anything is drawn at other than its own size. */
        boolean grown() {
            for (double factor : grow) {
                if (factor != 1) {
                    return true;
                }
            }
            return false;
        }
    }

    /** How the stack stands for one viewer: their hover, press and distance included. */
    private Layout layout(UUID viewer, boolean[] mask) {
        DisplayState[] current = displays;
        int count = current.length;
        double[] rooms = heights(count);
        Viewer state = states.get(viewer);
        double whole = 1;
        double hoverScale = config.click().hoverScale();
        if (state != null) {
            if (state.hover == WHOLE) {
                whole *= hoverScale;
            }
            if (state.pressed == WHOLE) {
                whole *= PRESS_SCALE;
            }
        }
        double[] grow = new double[count];
        for (int i = 0; i < count; i++) {
            double factor = whole * distanceGrow(state, i);
            if (state != null && state.hover == i) {
                factor *= hoverScale;
            }
            if (state != null && state.pressed == i) {
                factor *= PRESS_SCALE;
            }
            grow[i] = factor;
        }
        double[] y = new double[count];
        double[] baseY = new double[count];
        double top = stack(current, rooms, mask, grow, y);
        double baseTop = stack(current, rooms, mask, null, baseY);
        double hologramGrow = config.properties().distanceScale().enabled() && state != null
                ? state.step * SCALE_STEP : 1;
        return new Layout(y, baseY, grow, top, baseTop, whole * hologramGrow);
    }

    /**
     * Stacks the lines a viewer is shown, the last at the bottom, each taking
     * its room times its growth plus its gap.
     *
     * <p>Lines are stacked downwards from the anchor, so the first line is at
     * the top and adding one grows the hologram down rather than moving what
     * was already there. Each line takes the room its size gives it, so a
     * bigger line pushes the ones above it up instead of overlapping them, and
     * a line a viewer is not shown takes no room for them. A text or block
     * display grows up from its position and an item one around it, so an item
     * stands half its height higher.
     *
     * @param grow how much each line is grown, {@code null} for none
     * @param y    filled with how high above the anchor each line stands
     * @return the top of the stack
     */
    private double stack(DisplayState[] current, double[] rooms, boolean[] mask, double[] grow,
                         double[] y) {
        LineStyle[] lineStyles = styles;
        boolean text = config.type() == HologramConfig.Kind.TEXT;
        double sum = 0;
        for (int i = rooms.length - 1; i >= 0; i--) {
            if (!shown(mask, i)) {
                y[i] = sum;
                continue;
            }
            double factor = grow == null ? 1 : grow[i];
            double room = rooms[i] * factor;
            y[i] = sum + (current[i].kind() == HologramConfig.Kind.ITEM ? room / 2 : 0);
            double gap = text && i < lineStyles.length ? lineStyles[i].gap() : 0;
            sum += room + gap * factor;
        }
        return sum;
    }

    /** How much a line is grown by the viewer's distance. */
    private double distanceGrow(Viewer state, int line) {
        if (state == null) {
            return 1;
        }
        int[] steps = state.lineSteps;
        LineStyle[] lineStyles = styles;
        if (config.type() == HologramConfig.Kind.TEXT && line < lineStyles.length
                && line < steps.length && lineStyles[line].autoscale()) {
            return steps[line] * SCALE_STEP;
        }
        return config.properties().distanceScale().enabled() ? state.step * SCALE_STEP : 1;
    }

    /**
     * Works out a viewer's size by distance, in steps.
     *
     * @return whether any step changed
     */
    private boolean measure(Player viewer, Viewer state) {
        if (!scaling) {
            return false;
        }
        double distance = Math.sqrt(viewer.getLocation().distanceSquared(anchor()));
        HologramConfig.DistanceScale whole = config.properties().distanceScale();
        boolean changed = false;
        if (whole.enabled()) {
            int step = step(whole.scaleAt(distance));
            changed = step != state.step;
            state.step = step;
        }
        LineStyle[] lineStyles = styles;
        int[] steps = state.lineSteps;
        if (steps.length != lineStyles.length) {
            steps = new int[lineStyles.length];
            state.lineSteps = steps;
            changed = true;
        }
        for (int i = 0; i < lineStyles.length; i++) {
            if (!lineStyles[i].autoscale()) {
                continue;
            }
            HologramConfig.DistanceScale curve = lineStyles[i].curve() != null
                    ? lineStyles[i].curve() : whole;
            int step = step(curve.scaleAt(distance));
            changed |= step != steps[i];
            steps[i] = step;
        }
        return changed;
    }

    static int step(double scale) {
        return (int) Math.round(scale / SCALE_STEP);
    }

    /**
     * Re-sends a viewer's size when their distance moved it a step. Runs on
     * the visibility pass, so it costs one square root per viewer per pass
     * and nothing at all for holograms that do not scale.
     */
    void rescale(Player viewer) {
        if (!scaling || removed) {
            return;
        }
        Viewer state = states.get(viewer.getUniqueId());
        if (state != null && measure(viewer, state)) {
            relayout(viewer, SCALE_TICKS);
        }
    }

    /** Sends a viewer every line's transformation, and the panels', for a new layout. */
    private void relayout(Player viewer, int ticks) {
        UUID uuid = viewer.getUniqueId();
        boolean[] mask = masks.get(uuid);
        DisplayState[] current = displays;
        Layout layout = layout(uuid, mask);
        for (int i = 0; i < current.length; i++) {
            if (shown(mask, i)) {
                HologramRuntime.sink().transform(viewer, current[i], viewOf(current[i], i, layout), ticks);
            }
        }
        if (mount != null) {
            return;
        }
        if (wholePlated(viewer)) {
            HologramRuntime.sink().transform(viewer, plate, wholePlateView(viewer, layout), ticks);
        }
    }

    /**
     * How one viewer is shown a display's transformation.
     *
     * <p>Riding an entity, every passenger is drawn at the same point of the
     * vehicle, so the stack and the configured offset can only be built from
     * the translation. Grown, by a hover or by distance, the lines are spread
     * by as much so they do not overlap.
     */
    private DisplayState.Transform viewOf(DisplayState display, int line, Layout layout) {
        double grow = layout.grow()[line];
        if (mount != null) {
            return display.transform().view(grow, config.offsetX(),
                    config.offsetY() + layout.y()[line], config.offsetZ());
        }
        return display.transform().view(grow, 0, layout.y()[line] - layout.baseY()[line], 0);
    }

    /**
     * The panel behind the whole stack, fitted to what this viewer is shown:
     * as wide as the widest line, from the lowest line to the top of the
     * highest, each as large as it is drawn now.
     */
    private DisplayState.Transform wholePlateView(Player viewer, Layout layout) {
        DisplayState[] current = displays;
        boolean[] mask = masks.get(viewer.getUniqueId());
        double half = 0;
        double bottom = Double.MAX_VALUE;
        double top = -Double.MAX_VALUE;
        double depth = 0;
        for (int i = 0; i < current.length; i++) {
            if (!shown(mask, i)) {
                continue;
            }
            DisplayState display = current[i];
            HologramConfig.Properties look = display.properties();
            double grow = layout.grow()[i];
            double[] box = extent(display.kind(),
                    display.kind() == HologramConfig.Kind.TEXT ? textFor(viewer, i) : null,
                    look.lineWidth(), look.scaleX() * grow, look.scaleY() * grow, layout.y()[i]);
            half = Math.max(half, box[0]);
            bottom = Math.min(bottom, box[1]);
            top = Math.max(top, box[2]);
            if (display.kind() != HologramConfig.Kind.TEXT) {
                depth = Math.max(depth, box[0]);
            }
        }
        if (bottom > top) {
            bottom = 0;
            top = 0;
        }
        return panelView(half, bottom, top, depth);
    }

    /**
     * What a display covers, standing at {@code y}: half its width, its
     * bottom and its top, in blocks.
     *
     * <p>Text by the client's own measure of its background: {@code width + 1}
     * pixels across, {@code 10 * lines + 1} up from the entity, a line that
     * runs past {@code lineWidth} wrapping onto more. An item is centred on
     * its position, a block stands on it; both one block across at scale 1.
     */
    static double[] extent(HologramConfig.Kind kind, Component text, int lineWidth, double scaleX,
                           double scaleY, double y) {
        if (kind == HologramConfig.Kind.ITEM) {
            return new double[]{scaleX / 2, y - scaleY / 2, y + scaleY / 2};
        }
        if (kind == HologramConfig.Kind.BLOCK) {
            return new double[]{scaleX / 2, y, y + scaleY};
        }
        int width = net.exylia.lib.text.Centering.pixelWidth(text);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(text);
        int lines = 1 + (int) plain.chars().filter(c -> c == '\n').count();
        int wrap = Math.max(1, lineWidth);
        if (width > wrap) {
            // ponytail: wrapping is word by word on the client; this reads it as
            // character by character, which is a line short at worst, and the
            // panel's padding takes most of that.
            lines += (width - 1) / wrap;
            width = wrap;
        }
        return new double[]{(width / 2.0 + 1) * PX * scaleX, y, y + (10 * lines + 1) * PX * scaleY};
    }

    /** The panel's transformation: its one space stretched over the box it has to cover. */
    static DisplayState.Transform panelView(double half, double bottom, double top, double depth) {
        double sx = (2 * half + 2 * PANEL_PAD) / (PANEL_PX_W * PX);
        double sy = (Math.max(0, top - bottom) + 2 * PANEL_PAD) / (PANEL_PX_H * PX);
        // The space's background sits half a pixel right of the entity.
        return new DisplayState.Transform((float) (-0.5 * PX * sx), (float) (bottom - PANEL_PAD),
                (float) -(depth + PANEL_DEPTH), (float) sx, (float) sy, 1f, 0f, 1f);
    }

    /** Whether a viewer is shown the panel behind the whole hologram: it has click lines on their page. */
    private boolean wholePlated(Player viewer) {
        return paneled && mount == null
                && !clickLines[Math.min(page(viewer), clickLines.length - 1)].isEmpty();
    }

    /** Whether a click target is drawn as a button for this viewer, which is what lights and presses. */
    private boolean drawn(Player viewer, int target) {
        if (mount != null) {
            return false;
        }
        if (target == WHOLE) {
            return wholePlated(viewer);
        }
        LineStyle[] lineStyles = styles;
        return target >= 0 && target < lineStyles.length && target < displays.length
                && drawnAsButton(lineStyles[target]) && shown(masks.get(viewer.getUniqueId()), target);
    }

    /** Lights a target for a viewer: at rest, aimed at, or pressed. */
    private void light(Player viewer, int target, int how) {
        if (!drawn(viewer, target)) {
            return;
        }
        if (target == WHOLE) {
            HologramRuntime.sink().look(viewer, plate, 255,
                    how == PRESSED ? panelPress : how == HOVERED ? panelHover : panelIdle);
            return;
        }
        DisplayState line = displays[target];
        HologramRuntime.sink().look(viewer, line, line.properties().textOpacity(),
                how == PRESSED ? buttonPress : how == HOVERED ? buttonHover : line.backgroundArgb());
    }

    /**
     * Grows or shrinks the hologram, or a button line, for a viewer who
     * started or stopped looking at it, and lights its panel, so whatever can
     * be clicked shows it.
     */
    void hover(Player viewer) {
        if (mount != null || !clickable()) {
            return;
        }
        boolean grows = config.click().hoverScale() != 1;
        if (!grows && !paneled && !hasButtons) {
            return;
        }
        UUID uuid = viewer.getUniqueId();
        Viewer state = states.get(uuid);
        if (state == null) {
            return;
        }
        int target = target(viewer, masks.get(uuid));
        int was = state.hover;
        if (target == was) {
            return;
        }
        state.hover = target;
        if (grows) {
            relayout(viewer, HOVER_TICKS);
        }
        light(viewer, was, IDLE);
        light(viewer, target, HOVERED);
    }

    /**
     * What a viewer is aiming at: a button line, the hologram as a whole, or
     * nothing.
     *
     * <p>The height their line of sight passes the hologram's axis at picks
     * the line, so a button is found without a box of its own: the one click
     * box catches every click and this decides what it was on.
     */
    private int target(Player viewer, boolean[] mask) {
        DisplayState[] current = displays;
        double[] rooms = heights(current.length);
        double[] y = new double[current.length];
        double top = stack(current, rooms, mask, null, y);
        if (!looksAt(viewer, top)) {
            return NONE;
        }
        if (hasButtons) {
            double at = aimHeight(viewer);
            LineStyle[] lineStyles = styles;
            for (int i = 0; i < current.length && i < lineStyles.length; i++) {
                if (!shown(mask, i) || !isButton(lineStyles[i])) {
                    continue;
                }
                double bottom = y[i] - (current[i].kind() == HologramConfig.Kind.ITEM ? rooms[i] / 2 : 0);
                if (at >= bottom && at <= bottom + rooms[i]) {
                    return i;
                }
            }
        }
        return wholeClickable() ? WHOLE : NONE;
    }

    /** How high above the hologram's base a viewer's line of sight passes its upright axis. */
    private double aimHeight(Player viewer) {
        Location eye = viewer.getLocation();
        org.bukkit.util.Vector direction = eye.getDirection();
        Location at = location;
        double flat = direction.getX() * direction.getX() + direction.getZ() * direction.getZ();
        double along = flat < 1e-9 ? 0
                : ((at.getX() - eye.getX()) * direction.getX()
                + (at.getZ() - eye.getZ()) * direction.getZ()) / flat;
        return eye.getY() + EYE_HEIGHT + direction.getY() * along - at.getY();
    }

    /** Whether the viewer's line of sight crosses the click box, within reach. */
    private boolean looksAt(Player viewer, double height) {
        return aim(viewer, height) >= 0;
    }

    /**
     * How far along the viewer's line of sight it enters the click box, or
     * {@code -1} when it misses it or meets it beyond the click range. The one
     * ray hover and every click use, so what lights up is what a click hits.
     */
    private double aim(Player viewer, double height) {
        Location eye = viewer.getLocation();
        org.bukkit.util.Vector direction = eye.getDirection();
        double[] origin = {eye.getX(), eye.getY() + EYE_HEIGHT, eye.getZ()};
        double[] dir = {direction.getX(), direction.getY(), direction.getZ()};
        double half = config.click().width() / 2;
        Location at = location;
        double[] min = {at.getX() - half, at.getY(), at.getZ() - half};
        double[] max = {at.getX() + half, at.getY() + height, at.getZ() + half};
        double near = 0;
        double far = config.click().range();
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1e-9) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return -1;
                }
                continue;
            }
            double a = (min[axis] - origin[axis]) / dir[axis];
            double b = (max[axis] - origin[axis]) / dir[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) {
                return -1;
            }
        }
        return near;
    }

    /**
     * How far away a viewer aims at it, for a click made without touching its
     * box, or {@code -1} when they do not: it rides nothing, takes clicks, is
     * on their screen and is in their line of sight within range.
     */
    double aimedFrom(Player viewer) {
        UUID uuid = viewer.getUniqueId();
        if (removed || mount != null || !clickable() || !viewers.contains(uuid)) {
            return -1;
        }
        DisplayState[] current = displays;
        double top = stack(current, heights(current.length), masks.get(uuid), null,
                new double[current.length]);
        return aim(viewer, top);
    }

    /**
     * The click target pressed: it squashes in and its panel flashes, then
     * both spring back on the driver after {@value #PRESS_MS} ms. Only a
     * target drawn as a button is pressed.
     */
    private void press(Player viewer, int target) {
        Viewer state = states.get(viewer.getUniqueId());
        if (state == null || !drawn(viewer, target)) {
            return;
        }
        state.pressed = target;
        state.pressedAt = Math.max(1, HologramRuntime.now());
        pressing = true;
        relayout(viewer, PRESS_TICKS);
        light(viewer, target, PRESSED);
    }

    /** Springs back the presses that are due. */
    private void release(List<Player> currentViewers, long now) {
        boolean waiting = false;
        for (Player viewer : currentViewers) {
            Viewer state = states.get(viewer.getUniqueId());
            if (state == null || state.pressedAt == 0) {
                continue;
            }
            if (now - state.pressedAt < PRESS_MS) {
                waiting = true;
                continue;
            }
            int target = state.pressed;
            state.pressed = NONE;
            state.pressedAt = 0;
            relayout(viewer, HOVER_TICKS);
            light(viewer, target, state.hover == target ? HOVERED : IDLE);
        }
        pressing = waiting;
    }

    private void type(List<Player> currentViewers, long now) {
        DisplayState[] current = displays;
        LineStyle[] lineStyles = styles;
        long scrambleFrame = now / SHIMMER_MS;
        boolean scrambles = scrambleFrame != lastScramble;
        lastScramble = scrambleFrame;
        double seconds = now / 1000.0;
        for (Player viewer : currentViewers) {
            long[] started = typing.get(viewer.getUniqueId());
            if (started == null) {
                continue;
            }
            boolean any = false;
            for (int i = 0; i < started.length && i < current.length && i < lineStyles.length; i++) {
                if (started[i] == 0) {
                    continue;
                }
                LineStyle style = lineStyles[i];
                Component whole = Shimmer.dress(style, textFor(viewer, i), seconds);
                if (style.scramble() > 0) {
                    double elapsed = (now - started[i]) / 1000.0;
                    if (elapsed * style.scramble() >= Shimmer.length(whole)) {
                        started[i] = 0;
                        HologramRuntime.sink().text(viewer, current[i], whole);
                    } else {
                        any = true;
                        if (scrambles) {
                            HologramRuntime.sink().text(viewer, current[i],
                                    Shimmer.scramble(whole, elapsed, style.scramble(), scrambleFrame));
                        }
                    }
                    continue;
                }
                int count = (int) ((now - started[i]) * style.typewriter() / 1000);
                if (count <= 0) {
                    any = true;
                } else if (count >= Typewriter.length(whole)) {
                    started[i] = 0;
                    HologramRuntime.sink().text(viewer, current[i], whole);
                } else {
                    any = true;
                    HologramRuntime.sink().text(viewer, current[i], Typewriter.cut(whole, count));
                }
            }
            if (!any) {
                typing.remove(viewer.getUniqueId());
            }
        }
    }

    // ------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------

    /** Whether it has a click box: something runs for the whole hologram, or for a button line. */
    boolean clickable() {
        return wholeClickable() || hasButtons;
    }

    /** Whether clicking the hologram as a whole does anything. */
    private boolean wholeClickable() {
        if (clickHandler != null) {
            return true;
        }
        for (Interaction lines : clickLines) {
            if (!lines.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    int hitboxId() {
        return hitboxId;
    }

    /** The click box and the panels, sized to what this viewer sees. */
    private void spawnControls(Player viewer, boolean[] mask) {
        if (!clickable()) {
            return;
        }
        Layout layout = layout(viewer.getUniqueId(), mask);
        double total = layout.baseTop();
        // Riding, the box sits where the vehicle carries its passengers, so it
        // also has to reach up over the offset to cover the lines.
        if (mount != null) {
            total += Math.max(0, config.offsetY());
        }
        Location at = anchor();
        HologramRuntime.sink().hitbox(viewer, hitboxId, at,
                (float) config.click().width(), (float) Math.max(total, 0.25));
        if (mount != null) {
            return;
        }
        if (wholePlated(viewer)) {
            HologramRuntime.sink().spawn(viewer, plate, at, PLATE_TEXT, wholePlateView(viewer, layout));
        }
        Viewer state = states.get(viewer.getUniqueId());
        if (state != null && state.hover != NONE) {
            light(viewer, state.hover, HOVERED);
        }
    }

    /** The click box and the panel, for taking them off a client. */
    private int[] controlIds() {
        return plate == null ? new int[]{hitboxId} : new int[]{hitboxId, plate.entityId()};
    }

    /**
     * A player clicked the box.
     *
     * <p>Arrives on the network thread. The lines find their own thread, and
     * the plugin's handler is handed to the player's. A click aimed at a
     * button line runs that button's lines instead of the hologram's.
     */
    void clicked(Player player, Click click) {
        UUID uuid = player.getUniqueId();
        if (removed || !clickable() || !viewers.contains(uuid)) {
            return;
        }
        long now = HologramRuntime.now();
        Long last = lastClick.get(uuid);
        if (last != null && now - last < Math.max(MIN_CLICK_GAP_MS, config.click().cooldown())) {
            return;
        }
        lastClick.put(uuid, now);
        int target = mount == null && hasButtons ? target(player, masks.get(uuid)) : WHOLE;
        LineStyle[] lineStyles = styles;
        if (target >= 0 && target < lineStyles.length) {
            Interaction button = buttonLines.get(lineStyles[target].button());
            if (button != null && button.handles(click)) {
                button.run(player, click, data);
            }
            press(player, target);
            return;
        }
        Interaction lines = clickLines[Math.min(page(player), clickLines.length - 1)];
        if (lines.handles(click)) {
            lines.run(player, click, data);
        }
        press(player, WHOLE);
        BiConsumer<Player, Click> handler = clickHandler;
        if (handler != null) {
            Tasks.of(owner).runAtEntity(player, () -> handler.accept(player, click));
        }
    }

    // ------------------------------------------------------------------
    // Layout and lifecycle
    // ------------------------------------------------------------------

    /** Takes the hologram off a player's screen. */
    void despawnFor(Player viewer) {
        UUID uuid = viewer.getUniqueId();
        masks.remove(uuid);
        typing.remove(uuid);
        lastClick.remove(uuid);
        states.remove(uuid);
        if (viewers.remove(uuid)) {
            HologramRuntime.sink().destroy(viewer, entityIdsWithHitbox());
        }
    }

    /** Forgets a player who left, without sending them anything. */
    void forget(UUID viewer) {
        pageOf.remove(viewer);
        turnedAt.remove(viewer);
        viewers.remove(viewer);
        masks.remove(viewer);
        typing.remove(viewer);
        lastClick.remove(viewer);
        states.remove(viewer);
    }

    /**
     * Forgets that a viewer has this hologram, without sending anything: their
     * client dropped it on its own (a respawn or a world change), so the next
     * visibility check sends it whole again.
     */
    void dropped(UUID viewer) {
        viewers.remove(viewer);
        masks.remove(viewer);
        typing.remove(viewer);
        states.remove(viewer);
    }

    /** Moves every display for everybody who can see it. */
    void teleportFor(List<Player> currentViewers) {
        DisplayState[] current = displays;
        Location at = anchor();
        double[] rooms = heights(current.length);
        double[] y = new double[current.length];
        for (Player viewer : currentViewers) {
            boolean[] mask = masks.get(viewer.getUniqueId());
            stack(current, rooms, mask, null, y);
            for (int i = 0; i < current.length; i++) {
                if (shown(mask, i)) {
                    HologramRuntime.sink().teleport(viewer, current[i].entityId(),
                            mount != null ? at : at.clone().add(0, y[i], 0));
                }
            }
            if (clickable()) {
                for (int control : controlIds()) {
                    HologramRuntime.sink().teleport(viewer, control, at);
                }
            }
        }
    }

    /**
     * Where the hologram is, for range checks and for spawning: the ridden
     * entity while it rides one, facing the way the placed location does,
     * and the placed location otherwise.
     */
    Location anchor() {
        Entity vehicle = mount;
        Location at = location;
        if (vehicle == null) {
            return at;
        }
        Location riding = vehicle.getLocation().clone();
        riding.setYaw(at.getYaw());
        riding.setPitch(at.getPitch());
        return riding;
    }

    /** Every entity a viewer may have been sent, click box included when there is one. */
    private int[] entityIdsWithHitbox() {
        int[] ids = entityIds();
        if (!clickable()) {
            return ids;
        }
        int[] controls = controlIds();
        int[] all = Arrays.copyOf(ids, ids.length + controls.length);
        System.arraycopy(controls, 0, all, ids.length, controls.length);
        return all;
    }

    /** The room each display takes, gaps aside; the bottom-up stacking reads this. */
    private double[] heights(int count) {
        double[] heights = new double[count];
        LineStyle[] current = styles;
        HologramConfig.Properties base = config.properties();
        for (int i = 0; i < count; i++) {
            LineStyle style = config.type() == HologramConfig.Kind.TEXT && i < current.length
                    ? current[i] : LineStyle.PLAIN;
            heights[i] = style.height(base);
        }
        return heights;
    }

    int[] entityIds() {
        DisplayState[] current = displays;
        int[] ids = new int[current.length];
        for (int i = 0; i < current.length; i++) {
            ids[i] = current[i].entityId();
        }
        return ids;
    }

    /** Replaces the displays, for a hologram whose lines changed shape. */
    void rebuildDisplays() {
        this.displays = build(styles);
        resolveHeads(displays);
        this.lastText = new String[displays.length];
        refresh();
    }

    void lastViewers(List<Player> found) {
        lastViewers = found;
    }

    /** The last visibility check's players still being shown it: one may have quit or been hidden since. */
    List<Player> lastViewers() {
        List<Player> found = lastViewers;
        return found.stream().filter(player -> viewers.contains(player.getUniqueId())).toList();
    }

    Set<UUID> viewerIds() {
        return viewers;
    }

    Entity mount() {
        return mount;
    }

    HologramConfig config() {
        return config;
    }

    boolean ownedBy(String plugin) {
        return ownerName.equals(plugin);
    }

    void markRemoved() {
        removed = true;
    }

    /** Drops what viewers were sent, so they are re-sent in full. */
    void invalidate() {
        this.lastText = new String[displays.length];
        refresh();
    }
}
