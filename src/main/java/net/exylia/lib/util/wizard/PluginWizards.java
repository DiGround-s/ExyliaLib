package net.exylia.lib.util.wizard;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.PluginEffects;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.region.PluginRegions;
import net.exylia.lib.region.Regions;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.region.SelectionResult;
import net.exylia.lib.util.wizard.internal.WizardRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * One plugin's view of the wizard module.
 *
 * <pre>{@code
 * private PluginWizards wizards;
 * private Wizard arena;
 *
 * public void onEnable() {
 *     wizards = Wizards.of(this).using(config.get().wizard());
 *     arena = wizards.define("arena")
 *             .title("{primary}&lNEW ARENA")
 *             .ask(ID, step -> step.id("Enter the arena id"))
 *             .summary()
 *             .onFinish(this::createArena)
 *             .build();
 * }
 *
 * // From a menu button:
 * wizards.start(player, arena, () -> openMenu(player));
 * }</pre>
 *
 * <h2>Per plugin, not static</h2>
 * The scheduler a run hops onto, the console that hears about a step that threw,
 * the questions it asks and the boss bar it draws all belong to the plugin that
 * declared the flow. A static entry point would have to guess which of them, and
 * would guess wrong the moment a second Exylia plugin defined a wizard &mdash;
 * which is exactly how the old static-map approach behaved on a live server.
 *
 * @since 1.34.0
 */
public final class PluginWizards {

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final PluginInputs inputs;
    private final PluginEffects effects;

    private volatile WizardSettings settings = new WizardSettings();

    /**
     * Resolved on first use rather than in the constructor: the region module
     * validates the namespace it derives from the plugin's name, and a plugin
     * that never declares a region step should not be able to fail here for a
     * name it is not using.
     */
    private volatile PluginRegions regions;

    PluginWizards(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
        this.inputs = Inputs.of(plugin);
        this.effects = Effects.of(plugin);
    }

    /**
     * The plugin these belong to.
     *
     * @return the owning plugin
     */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Uses this plugin's own configured limits.
     *
     * <p>Without it, the defaults apply, which suit most flows. A flow whose
     * steps genuinely take a while &mdash; picking blocks across an arena
     * &mdash; wants a longer run timeout than the default five minutes.
     *
     * <p>Applies to runs started after this call. A live run keeps the settings
     * it began with, so a reload cannot move a deadline a player is already
     * racing.
     *
     * @param settings the limits
     * @return this
     */
    public @NotNull PluginWizards using(@NotNull WizardSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        return this;
    }

    /**
     * The limits in force.
     *
     * @return the settings
     */
    public @NotNull WizardSettings settings() {
        return settings;
    }

    /**
     * Starts declaring a flow.
     *
     * <p>Do this once, when the plugin reads its configuration, and keep the
     * {@link Wizard} it produces in a field. Compiling per command would re-run
     * every validation and allocate every lambda for a flow that has not
     * changed since the server started.
     *
     * @param id what to call it, in logs and diagnostics
     * @return the builder
     * @throws WizardException when the id is blank
     */
    public @NotNull WizardBuilder define(@NotNull String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new WizardException("A wizard needs an id, so a log line can name it.");
        }
        return new WizardBuilder(id, settings.progress());
    }

    /**
     * Walks a player through a flow.
     *
     * <p>Anything that player already had is ended first, as
     * {@link WizardOutcome#REPLACED}.
     *
     * @param player who to walk through it
     * @param wizard what to walk them through
     * @return the running flow
     */
    public @NotNull WizardRun start(@NotNull Player player, @NotNull Wizard wizard) {
        return start(player, wizard, null);
    }

    /**
     * Walks a player through a flow and does something afterwards.
     *
     * <p>The callback runs however the flow ends &mdash; finished, cancelled,
     * timed out or interrupted &mdash; so a menu that opened one is reopened
     * either way. It never runs for a player who is no longer online.
     *
     * <p>Runs on the thread that owns the player, a tick after the flow ends, so
     * whatever window the last step was asked in has already closed.
     *
     * @param player     who to walk through it
     * @param wizard     what to walk them through
     * @param afterwards what to do when it ends, such as reopening a menu
     * @return the running flow
     */
    public @NotNull WizardRun start(@NotNull Player player, @NotNull Wizard wizard,
                                     @Nullable Runnable afterwards) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(wizard, "wizard");
        return WizardRuntime.start(plugin, player, wizard, tasks, debug, inputs, effects,
                this::regions, settings, afterwards);
    }

    // ------------------------------------------------------------------
    // One question, asked the same way everywhere
    // ------------------------------------------------------------------

    /**
     * Asks a player to stand somewhere and hands back where they stood.
     *
     * <p>For anything a player is later put at &mdash; a spawn, a lobby, a warp.
     * The answer carries their facing, which a clicked block cannot.
     *
     * <pre>{@code
     * wizards.askStand(player, "{primary}&lLOBBY SPAWN " + arena.name(),
     *         messages.admin().pointPrompt(),
     *         where -> save(arena.withLobby(where)),
     *         () -> openArenaMenu(player));
     * }</pre>
     *
     * <p>Every plugin in the ecosystem was writing this same one-step flow, and
     * each one wired the cancel path slightly differently. Here it is wired
     * once: {@code abandoned} runs <b>only when the player backed out</b> &mdash;
     * a cancel, a timeout, a disconnect &mdash; and never after a successful
     * finish, so a caller that reopens a menu in both places does not open it
     * twice over the screen its own {@code accepted} just opened.
     *
     * @param title     what the progress bar names, such as the thing being set
     * @param prompt    what the player is told to do; the plugin's own text
     * @param accepted  told where they stood, on the player's thread
     * @param abandoned run when they backed out instead, or {@code null}
     * @return the running flow
     * @since 1.59.0
     */
    public @NotNull WizardRun askStand(@NotNull Player player, @NotNull String title,
                                       @NotNull String prompt,
                                       @NotNull Consumer<Location> accepted,
                                       @Nullable Runnable abandoned) {
        return one(player, title, abandoned,
                builder -> builder.stand(STAND, prompt),
                values -> accepted.accept(values.getLocation(STAND)));
    }

    /**
     * Asks a player to click a block and hands back the block they clicked.
     *
     * <p>For a place that <em>is</em> a block: a chest to fill, a sign to read,
     * a pedestal. Use {@link #askStand} for anywhere a player later stands.
     *
     * @param title     what the progress bar names
     * @param prompt    what the player is told to click
     * @param accepted  told which block, on the player's thread
     * @param abandoned run when they backed out instead, or {@code null}
     * @return the running flow
     * @since 1.59.0
     */
    public @NotNull WizardRun askPoint(@NotNull Player player, @NotNull String title,
                                       @NotNull String prompt,
                                       @NotNull Consumer<Location> accepted,
                                       @Nullable Runnable abandoned) {
        return one(player, title, abandoned,
                builder -> builder.pick(POINT, prompt),
                values -> accepted.accept(values.getLocation(POINT)));
    }

    /**
     * Asks a player to select a volume and hands back what they selected.
     *
     * <p>The shared block selector: the same wand, the same preview and the same
     * two corners every Exylia plugin selects with.
     *
     * @param title     what the progress bar names
     * @param prompt    what the player is told to select
     * @param accepted  told what they selected, on the player's thread
     * @param abandoned run when they backed out instead, or {@code null}
     * @return the running flow
     * @since 1.59.0
     */
    public @NotNull WizardRun askRegion(@NotNull Player player, @NotNull String title,
                                        @NotNull String prompt,
                                        @NotNull Consumer<SelectionResult> accepted,
                                        @Nullable Runnable abandoned) {
        return one(player, title, abandoned,
                builder -> builder.region(REGION, prompt),
                values -> accepted.accept(values.getRegion(REGION)));
    }

    /**
     * Asks a player to hold an item and confirm, and hands back a copy of it.
     *
     * @param title     what the progress bar names
     * @param prompt    what the player is told to hold
     * @param accepted  told which item, on the player's thread
     * @param abandoned run when they backed out instead, or {@code null}
     * @return the running flow
     * @since 1.59.0
     */
    public @NotNull WizardRun askItem(@NotNull Player player, @NotNull String title,
                                      @NotNull String prompt,
                                      @NotNull Consumer<ItemStack> accepted,
                                      @Nullable Runnable abandoned) {
        return one(player, title, abandoned,
                builder -> builder.hand(ITEM, prompt),
                values -> accepted.accept(values.getItem(ITEM)));
    }

    /**
     * Builds and starts a one-step flow.
     *
     * <p>Compiled per call rather than kept: the title names what is being set,
     * so it differs on every click, and a one-step flow is three validations to
     * re-run. A flow a plugin asks more than once with the same wording should
     * be {@link #define}d and kept, which is what that is for.
     *
     * <p>The id is derived from the title so a console line about a step that
     * threw names the thing the admin was setting rather than {@code step-1}.
     */
    private WizardRun one(Player player, String title, Runnable abandoned,
                          UnaryOperator<WizardBuilder> step,
                          Consumer<WizardValues> finished) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(finished, "finished");
        WizardBuilder builder = step.apply(define(idOf(title)).title(title))
                .onFinish(finished);
        if (abandoned != null) {
            builder.onCancel(outcome -> {
                if (outcome.byPlayer()) {
                    tasks.runAtEntity(player, abandoned);
                }
            });
        }
        return start(player, builder.build());
    }

    /** A title as an id: lower case, words joined, nothing a log cannot print. */
    private static String idOf(String title) {
        String stripped = ID_NOISE.matcher(title).replaceAll("").trim();
        String id = stripped.isBlank() ? "ask" : stripped.toLowerCase(Locale.ROOT).replace(' ', '-');
        return id.length() <= 48 ? id : id.substring(0, 48);
    }

    /** Colour tokens, legacy codes and anything else that is not a word. */
    private static final Pattern ID_NOISE =
            Pattern.compile("\\{[^}]*}|<[^>]*>|[&§].|[^A-Za-z0-9 ]");

    private static final WizardKey<Location> STAND = WizardKey.location("stand");
    private static final WizardKey<Location> POINT = WizardKey.location("point");
    private static final WizardKey<SelectionResult> REGION = WizardKey.region("region");
    private static final WizardKey<ItemStack> ITEM = WizardKey.item("item");

    /**
     * Ends every flow this plugin started.
     *
     * @return how many were ended
     */
    public int endAll() {
        return WizardRuntime.endAllOf(plugin.getName());
    }

    /**
     * This plugin's region facade, resolved once.
     *
     * @return the facade a region step selects with
     */
    @ApiStatus.Internal
    public @NotNull PluginRegions regions() {
        PluginRegions current = regions;
        if (current == null) {
            // Racing callers may each build one; they are equivalent and the
            // module's own registry hands back the same underlying runtime, so
            // the extra allocation is cheaper than a lock on a hot path that
            // is not hot at all.
            current = Regions.of(plugin);
            regions = current;
        }
        return current;
    }

    @Override
    public String toString() {
        return "PluginWizards[" + plugin.getName() + ']';
    }
}
