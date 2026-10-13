package net.exylia.lib.tuner;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.PluginEffects;
import net.exylia.lib.text.Text;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * A player tuning something with the mouse wheel. Started by {@link Tuner#start}.
 *
 * <p>The gesture methods ({@link #nudge}, {@link #shiftStep}, {@link #cycle},
 * {@link #snap}) are what the library's listener calls for the wheel, F and a
 * click; a consumer may call them too, for example from a command. Every
 * method is a no-op once the session has ended.
 *
 * @since 1.298.0
 */
public final class TunerSession {

    private final String owner;
    private final java.util.logging.Logger logger;
    private final Player player;
    private final String subject;
    private final String key;
    private final List<TunerAxis> axes;
    private final String hudTemplate;
    private final String hintsTemplate;
    private final Consumer<TunerSession> onChange;
    private final BiConsumer<TunerSession, TunerEnd> onEnd;
    private final PluginEffects effects;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final int heldSlot;
    private int axis;
    private Display hud;
    private Display hints;

    TunerSession(Tuner tuner, Player player) {
        this.owner = tuner.plugin.getName();
        this.logger = tuner.plugin.getLogger();
        this.effects = Effects.of(tuner.plugin);
        this.player = player;
        org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        this.heldSlot = inventory == null ? -1 : inventory.getHeldItemSlot();
        this.subject = tuner.subject;
        this.key = tuner.key;
        this.axes = List.copyOf(tuner.axes);
        this.hudTemplate = tuner.hud;
        this.hintsTemplate = tuner.hints;
        this.onChange = tuner.onChange;
        this.onEnd = tuner.onEnd;
    }

    public @NotNull Player player() {
        return player;
    }

    public @NotNull String subject() {
        return subject;
    }

    public @Nullable String key() {
        return key;
    }

    /** The plugin that started it. */
    public @NotNull String owner() {
        return owner;
    }

    /** The axis the wheel moves now. */
    public @NotNull TunerAxis axis() {
        return axes.get(axis);
    }

    public boolean isActive() {
        return !ended.get();
    }

    /**
     * Moves the current value. Positive is up the wheel.
     *
     * @param notches how many steps, signed
     */
    public void nudge(int notches) {
        if (!isActive() || notches == 0) return;
        if (!axis().nudge(notches)) {
            sound("BLOCK_NOTE_BLOCK_BASS", 0.5);
            return;
        }
        changed();
        sound("BLOCK_NOTE_BLOCK_HAT", notches > 0 ? 1.3 : 0.9);
    }

    /**
     * A bigger (positive) or smaller (negative) step on the current axis.
     *
     * @param by how many steps along the list
     */
    public void shiftStep(int by) {
        if (!isActive() || !axis().shiftStep(by)) return;
        redraw();
        sound("UI_BUTTON_CLICK", by > 0 ? 1.4 : 1.0);
    }

    /**
     * The next (positive) or previous (negative) axis, wrapping round.
     *
     * @param by how many axes along
     */
    public void cycle(int by) {
        if (!isActive() || axes.size() < 2) return;
        axis = Math.floorMod(axis + by, axes.size());
        redraw();
        sound("ITEM_BOOK_PAGE_TURN", 1.2);
    }

    /** Snaps the current value, when its axis snaps. */
    public void snap() {
        if (!isActive()) return;
        if (axis().snapNow()) {
            changed();
            sound("BLOCK_AMETHYST_BLOCK_CHIME", 1.2);
        } else {
            sound("BLOCK_NOTE_BLOCK_BASS", 0.5);
        }
    }

    /** Draws the bars again, after the consumer changed something they show. */
    public void redraw() {
        if (!isActive()) return;
        if (hud != null) {
            hud.text(fill(hudTemplate)).progress(axis().fill());
        }
        if (hints != null) {
            hints.text(fill(hintsTemplate));
        }
    }

    /** Ends it keeping the values: {@link TunerEnd#SAVED}. */
    public void save() {
        end(TunerEnd.SAVED);
    }

    /** Ends it putting the values back: {@link TunerEnd#CANCELLED}. */
    public void cancel() {
        end(TunerEnd.CANCELLED);
    }

    /** Ends it because the subject is gone: {@link TunerEnd#ABORTED}. */
    public void abort() {
        end(TunerEnd.ABORTED);
    }

    /**
     * Ends it for a reason. Runs {@code onEnd} once, whoever calls first.
     *
     * @param reason why
     */
    public void end(@NotNull TunerEnd reason) {
        if (!ended.compareAndSet(false, true)) return;
        Tuner.ACTIVE.remove(player.getUniqueId(), this);
        if (hud != null) hud.stop();
        if (hints != null) hints.stop();
        giveSlotBack(reason);
        try {
            onEnd.accept(this, reason);
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "A tuner's end handler failed", failure);
        }
    }

    void show() {
        // The builder takes a string; the values go in through text(Text) so the
        // template parses once however often the numbers change.
        hud = effects.bossBar(hudTemplate).progress(axis().fill()).show(player);
        if (!hintsTemplate.isBlank()) {
            hints = effects.bossBar(hintsTemplate).progress(0f).colour("WHITE").show(player);
        }
        redraw();
    }

    /**
     * The wheel walked the hotbar; the hand goes back to where it was.
     *
     * <p>Cancelling each slot change would keep the hand still, but the client
     * learns of the cancel a round trip later, and every notch sent in between
     * reads as a jump the listener cannot tell from a number key. Following the
     * wheel and putting the slot back at the end counts every notch at any ping.
     */
    private void giveSlotBack(TunerEnd reason) {
        Plugin lib = Tuner.library;
        if (reason == TunerEnd.QUIT || heldSlot < 0 || lib == null || !lib.isEnabled() || !player.isOnline()) return;
        Tasks.of(lib).runAtEntity(player, () -> player.getInventory().setHeldItemSlot(heldSlot));
    }

    private void changed() {
        try {
            onChange.accept(this);
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "A tuner's change handler failed", failure);
        }
        redraw();
    }

    private Text fill(String template) {
        TunerAxis current = axis();
        return Text.of(template)
                .with("%subject%", subject)
                .withFormatted("%axis%", current.label())
                .with("%value%", TunerAxis.format(current.value()) + current.unit())
                .with("%step%", TunerAxis.format(current.currentStep()) + current.unit())
                .with("%index%", axis + 1)
                .with("%count%", axes.size());
    }

    private void sound(String name, double pitch) {
        Effects.sound(name).volume(0.4).pitch(pitch).show(player);
    }
}
