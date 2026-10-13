package net.exylia.lib.tuner;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fine adjustment with the mouse wheel, while the player looks at the result.
 *
 * <pre>{@code
 * Tuner.of(plugin, "spawn")
 *         .key("hologram:spawn")
 *         .axis(TunerAxis.blocks("X", () -> work.x(), x -> work = work.withX(x)))
 *         .axis(TunerAxis.blocks("Y", () -> work.y(), y -> work = work.withY(y)))
 *         .axis(TunerAxis.degrees("Yaw", () -> work.yaw(), yaw -> work = work.withYaw(yaw)))
 *         .onChange(session -> preview(work))
 *         .onEnd((session, end) -> {
 *             if (end.kept()) save(work); else preview(original);
 *         })
 *         .start(player);
 * }</pre>
 *
 * <h2>What the player does</h2>
 * <ul>
 *   <li><b>Wheel</b>: up raises the current value by one step, down lowers it.
 *       The hotbar wraps, so the wheel never runs out, and the slot the player
 *       held is given back when the session ends.</li>
 *   <li><b>Sneak + wheel</b>: a bigger or smaller step.</li>
 *   <li><b>F</b> (swap hands): the next axis; <b>sneak + F</b> the previous one.</li>
 *   <li><b>Click</b>: save and finish. <b>Sneak + click</b>: snap the value
 *       (block centre, nearest 15 degrees...). A right-click, where the client
 *       sends one, snaps too.</li>
 * </ul>
 *
 * <p>Two boss bars show the subject, the axis, its value and step, and the keys.
 * Both texts are the consumer's to translate: {@link #hud(String)} and
 * {@link #hints(String)}. Cancelling has no key: an empty hand sends no drop or
 * right-click to the server, so the consumer offers it as a command or a chat
 * button calling {@link TunerSession#cancel()}.
 *
 * <h2>While it runs</h2>
 * Placing, breaking, using items, hitting and dropping are refused, so a gesture
 * meant for the tuner never does something else. The session ends by itself,
 * keeping the values, when the player leaves, dies, changes world or teleports
 * far, and when the plugin that started it is disabled; {@link TunerEnd} says
 * which. One session per player: starting another ends the first as
 * {@link TunerEnd#REPLACED}.
 *
 * <h2>Threads</h2>
 * Gestures arrive on the player's own thread (their region on Folia), and the
 * axes, {@code onChange} and {@code onEnd} run there. A session ended from
 * elsewhere ({@link TunerSession#save()} from a console command, the plugin
 * disabling) calls back on that thread instead.
 *
 * @since 1.298.0
 */
public final class Tuner {

    /** The default HUD: subject, axis, value, step. */
    public static final String DEFAULT_HUD =
            "{primary}&l%subject% {letters_black}» {secondary}%axis% {info}%value% "
                    + "{letters_black}▎ {letters}Step {info}%step% {letters_black}(%index%/%count%)";
    /** The default key hints. */
    public static final String DEFAULT_HINTS =
            "{letters}Scroll {letters_black}» {info}adjust {letters_black}▎ {letters}Sneak+scroll {letters_black}» {info}step "
                    + "{letters_black}▎ {letters}F {letters_black}» {info}axis {letters_black}▎ {letters}Sneak+click "
                    + "{letters_black}» {info}snap {letters_black}▎ {letters}Click {letters_black}» {success}save";

    static final Map<UUID, TunerSession> ACTIVE = new ConcurrentHashMap<>();
    /** The library, whose scheduler puts the held slot back after a session. */
    static volatile Plugin library;

    final Plugin plugin;
    final String subject;
    final List<TunerAxis> axes = new ArrayList<>();
    String key;
    String hud = DEFAULT_HUD;
    String hints = DEFAULT_HINTS;
    Consumer<TunerSession> onChange = session -> { };
    BiConsumer<TunerSession, TunerEnd> onEnd = (session, end) -> { };

    private Tuner(Plugin plugin, String subject) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.subject = Objects.requireNonNull(subject, "subject");
    }

    /**
     * Binds the library. Called once by ExyliaLib; consumers never call this.
     *
     * @param plugin the library plugin
     */
    public static void init(@NotNull Plugin plugin) {
        library = plugin;
    }

    /**
     * Starts describing a session.
     *
     * @param plugin  the owner; its sessions end when it is disabled
     * @param subject what is being tuned, as the HUD names it ({@code %subject%})
     * @return the builder
     */
    public static @NotNull Tuner of(@NotNull Plugin plugin, @NotNull String subject) {
        return new Tuner(plugin, subject);
    }

    /**
     * Makes the subject exclusive: a second player cannot tune the same key of
     * the same plugin at the same time.
     *
     * @param key such as {@code "hologram:spawn"}
     * @return this builder
     */
    public @NotNull Tuner key(@NotNull String key) {
        this.key = Objects.requireNonNull(key, "key");
        return this;
    }

    /**
     * Adds an axis. The first one added is the one the session starts on, and F
     * walks them in the order added.
     *
     * @param axis the axis
     * @return this builder
     */
    public @NotNull Tuner axis(@NotNull TunerAxis axis) {
        axes.add(Objects.requireNonNull(axis, "axis"));
        return this;
    }

    /**
     * The main bar. Placeholders: {@code %subject%}, {@code %axis%},
     * {@code %value%}, {@code %step%}, {@code %index%}, {@code %count%}.
     *
     * @param template palette tokens and colours allowed
     * @return this builder
     */
    public @NotNull Tuner hud(@NotNull String template) {
        this.hud = Objects.requireNonNull(template, "template");
        return this;
    }

    /**
     * The second bar, with the keys. Same placeholders as {@link #hud(String)};
     * blank to show no second bar.
     *
     * @param template palette tokens and colours allowed
     * @return this builder
     */
    public @NotNull Tuner hints(@NotNull String template) {
        this.hints = Objects.requireNonNull(template, "template");
        return this;
    }

    /**
     * Runs after every change of a value, which is where the live preview is
     * redrawn. Not called for a step or axis change, which move nothing.
     *
     * @param action what to run
     * @return this builder
     */
    public @NotNull Tuner onChange(@NotNull Consumer<TunerSession> action) {
        this.onChange = Objects.requireNonNull(action, "action");
        return this;
    }

    /**
     * Runs once when the session ends, however it ends. Persist when
     * {@link TunerEnd#kept()}, put the original back otherwise.
     *
     * @param action what to run
     * @return this builder
     */
    public @NotNull Tuner onEnd(@NotNull BiConsumer<TunerSession, TunerEnd> action) {
        this.onEnd = Objects.requireNonNull(action, "action");
        return this;
    }

    /**
     * Starts the session.
     *
     * <p>A session the player already had ends first, as
     * {@link TunerEnd#REPLACED}.
     *
     * @param player who tunes
     * @return the session, or empty when another player holds the same
     *         {@linkplain #key key}
     * @throws IllegalStateException when no axis was added
     */
    public @NotNull Optional<TunerSession> start(@NotNull Player player) {
        if (axes.isEmpty()) {
            throw new IllegalStateException("A tuner needs at least one axis");
        }
        if (key != null) {
            Optional<TunerSession> holder = byKey(plugin, key);
            if (holder.isPresent() && !holder.get().player().getUniqueId().equals(player.getUniqueId())) {
                return Optional.empty();
            }
        }
        TunerSession previous = ACTIVE.get(player.getUniqueId());
        if (previous != null) {
            previous.end(TunerEnd.REPLACED);
        }
        TunerSession session = new TunerSession(this, player);
        ACTIVE.put(player.getUniqueId(), session);
        session.show();
        return Optional.of(session);
    }

    /**
     * The session a player is in.
     *
     * @param player the player
     * @return it, or empty
     */
    public static @NotNull Optional<TunerSession> active(@NotNull Player player) {
        return active(player.getUniqueId());
    }

    /**
     * The session a player is in.
     *
     * @param player their id
     * @return it, or empty
     */
    public static @NotNull Optional<TunerSession> active(@NotNull UUID player) {
        return Optional.ofNullable(ACTIVE.get(player));
    }

    /**
     * Who is tuning a subject.
     *
     * @param plugin the owner
     * @param key    the {@linkplain #key key} it was started with
     * @return the session, or empty
     */
    public static @NotNull Optional<TunerSession> byKey(@NotNull Plugin plugin, @Nullable String key) {
        if (key == null) {
            return Optional.empty();
        }
        return ACTIVE.values().stream()
                .filter(session -> key.equals(session.key()) && session.owner().equals(plugin.getName()))
                .findFirst();
    }

    /**
     * Ends a plugin's sessions as {@link TunerEnd#DISABLED}. Called by the
     * library when the plugin is disabled; consumers never need to.
     *
     * @param pluginName the plugin
     * @return how many ended
     */
    public static int release(@NotNull String pluginName) {
        int ended = 0;
        for (TunerSession session : List.copyOf(ACTIVE.values())) {
            if (session.owner().equals(pluginName)) {
                session.end(TunerEnd.DISABLED);
                ended++;
            }
        }
        return ended;
    }
}
