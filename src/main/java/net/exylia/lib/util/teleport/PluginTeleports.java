package net.exylia.lib.util.teleport;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.internal.BackHistory;
import net.exylia.lib.util.teleport.internal.CrossServer;
import net.exylia.lib.util.teleport.internal.TeleportRuntime;
import net.exylia.lib.util.teleport.internal.Teleporter;
import net.exylia.lib.util.teleport.internal.TpaBook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One plugin's view of the teleport module.
 *
 * <pre>{@code
 * private PluginTeleports teleports;
 *
 * public void onEnable() {
 *     teleports = Teleports.of(this).using(config.get().teleport());
 * }
 *
 * // From a warp command:
 * teleports.to(player, warp.location())
 *         .warmup(3.0)
 *         .cooldown("warp", 30.0)
 *         .start();
 * }</pre>
 *
 * @since 1.34.0
 */
public final class PluginTeleports {

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;
    private volatile TeleportSettings settings = new TeleportSettings();

    PluginTeleports(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
    }

    /** The plugin these belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Uses this plugin's own configured defaults.
     *
     * <p>Without it the library defaults apply: no countdown, and one that is
     * cancelled by moving or being hit if a request asks for one.
     *
     * <h2>One of these settings is not this plugin's alone</h2>
     * {@link TeleportSettings#crossServerSettleSeconds()} is read when a player
     * <em>arrives</em> from another server, and an arrival belongs to no
     * plugin: the proxy moved them, the join listener is the library's, and
     * there is no request to read a setting from. So it is published here for
     * the whole server, and the last plugin to call this wins.
     *
     * <p>That is honest rather than tidy, and it is what the value describes:
     * how long <em>this server's clients</em> take to finish loading a world.
     * Two plugins disagreeing about that are two plugins describing the same
     * machine, and the alternative — ignoring the configured value entirely and
     * always using the default — is an owner raising a number that does
     * nothing.
     *
     * @param settings the owner's defaults
     * @return this
     */
    public @NotNull PluginTeleports using(@NotNull TeleportSettings settings) {
        this.settings = settings;
        TeleportRuntime.arrivalSettings(settings);
        return this;
    }

    /** The defaults in force. */
    public @NotNull TeleportSettings settings() {
        return settings;
    }

    /**
     * Describes a teleport to a live location.
     *
     * <p>Nothing happens until {@link TeleportRequest#start()}.
     *
     * @param player      who to move
     * @param destination where to
     * @return the request to describe and start
     */
    public @NotNull TeleportRequest to(@NotNull Player player, @NotNull Location destination) {
        return new TeleportRequest(plugin, tasks, debug, player, destination, null, settings);
    }

    /**
     * Describes a teleport to a stored place.
     *
     * <p>A place whose world is not loaded here, or that names another server,
     * yields a request that completes {@link TeleportResult#WORLD_NOT_FOUND} or
     * {@link TeleportResult#CROSS_SERVER_UNAVAILABLE} rather than throwing:
     * a warp pointing at a world an owner removed is a message to one player,
     * not a stack trace in the console every time somebody types the command.
     *
     * @param player      who to move
     * @param destination where to
     * @return the request to describe and start
     */
    public @NotNull TeleportRequest to(@NotNull Player player, @NotNull ExyliaLocation destination) {
        if (!destination.isSameServer(CrossServer.serverId(plugin))) {
            // A place naming this server is a local teleport, not a handover to
            // itself: the stored string says which server wrote it, and on the
            // server that wrote it that name means "here".
            if (!CrossServer.isAvailable(plugin)) {
                debug.warn("A teleport was aimed at server '" + destination.server()
                        + "', which this server cannot reach: no Redis is configured."
                        + " Turn on database.redis in database.yml on every server"
                        + " of the network.");
                return new TeleportRequest(plugin, tasks, debug, player, null,
                        TeleportResult.CROSS_SERVER_UNAVAILABLE, settings);
            }
            return new TeleportRequest(plugin, tasks, debug, player, null, null, settings)
                    .cause(TeleportCause.CROSS_SERVER)
                    .handingOverTo(destination);
        }
        Location live = destination.toBukkitLocation();
        if (live == null) {
            debug.warn("A teleport was aimed at world '" + destination.world()
                    + "', which is not loaded on this server.");
            return new TeleportRequest(plugin, tasks, debug, player, null,
                    TeleportResult.WORLD_NOT_FOUND, settings);
        }
        return new TeleportRequest(plugin, tasks, debug, player, live, null, settings);
    }

    /**
     * Describes a teleport to wherever a player is, on any server of the network.
     *
     * <pre>{@code
     * teleports.toPlayer(staff, report.target())
     *         .then(result -> {
     *             if (result == TeleportResult.TARGET_NOT_FOUND) {
     *                 Msg.send(staff, messages.offline());
     *             }
     *         })
     *         .start();
     * }</pre>
     *
     * <p>A target on this server is a plain local teleport to where they are
     * standing. One elsewhere becomes a handover carrying
     * {@link TeleportCause#CROSS_SERVER}: which server is read from the
     * network's presence map when the request starts, and the arriving server
     * finds the target itself, so nothing here holds a location that would be
     * stale by the time anybody got there. A target on no server completes
     * {@link TeleportResult#TARGET_NOT_FOUND} — and on a server with no Redis
     * that is the answer for anybody not here.
     *
     * @param player who to move
     * @param target who to reach
     * @return the request to describe and start
     * @since 1.98.0
     */
    public @NotNull TeleportRequest toPlayer(@NotNull Player player, @NotNull UUID target) {
        Player here = Bukkit.getPlayer(target);
        if (here != null) {
            return to(player, here.getLocation());
        }
        if (!CrossServer.isAvailable(plugin)) {
            return new TeleportRequest(plugin, tasks, debug, player, null,
                    TeleportResult.TARGET_NOT_FOUND, settings);
        }
        return new TeleportRequest(plugin, tasks, debug, player, null, null, settings)
                .cause(TeleportCause.CROSS_SERVER)
                .following(target);
    }

    /**
     * Pulls a player to somebody, from any server of the network.
     *
     * <p>The reverse of {@link #toPlayer}: {@code /tphere} across a network.
     * A target on this server is moved straight away; one elsewhere has the
     * destination queued under their id and the proxy told to move them,
     * through the player they are pulled to. There is no countdown and no
     * cooldown — the person being moved never asked for it.
     *
     * <p><b>Threading:</b> call from the puller's own thread; their location is
     * read here.
     *
     * @param to         who the target is pulled to
     * @param target     who is pulled
     * @param targetName their name, which is what the proxy knows them by
     * @return how it ended; {@link TeleportResult#SUCCESS} for a handover means
     *         the proxy was told, and the destination server answers for the
     *         arrival
     * @since 1.98.0
     */
    public @NotNull CompletableFuture<TeleportResult> bring(@NotNull Player to, @NotNull UUID target,
                                                            @NotNull String targetName) {
        Player here = Bukkit.getPlayer(target);
        if (here != null) {
            return Teleporter.teleport(plugin, here, to.getLocation(), TeleportCause.PLUGIN,
                    tasks, debug, settings.backHistorySize());
        }
        if (!CrossServer.isAvailable(plugin)) {
            return CompletableFuture.completedFuture(TeleportResult.TARGET_NOT_FOUND);
        }
        return CrossServer.bring(plugin, to, target, targetName, settings);
    }

    /**
     * Where a player is, as a place that knows its server.
     *
     * <p>The one way to write down "here" for later: a home, a warp, where
     * somebody stood before a session. A {@code Location} means nothing on
     * another server of the network; this can be handed straight back to
     * {@link #to(Player, ExyliaLocation)} from anywhere.
     *
     * @param player who
     * @return their place, named after this server
     * @since 1.108.0
     */
    public @NotNull ExyliaLocation here(@NotNull Player player) {
        return ExyliaLocation.of(serverId(), player.getLocation());
    }

    /**
     * A live location as a place that knows its server.
     *
     * @param location where, on this server
     * @return the place, named after this server
     * @since 1.108.0
     */
    public @NotNull ExyliaLocation here(@NotNull Location location) {
        return ExyliaLocation.of(serverId(), location);
    }

    /**
     * This server's name on the network: what the proxy calls it when the
     * bridge is up, the {@code server-id} of this plugin's Redis block otherwise.
     *
     * @return the name a place on this server carries
     * @since 1.108.0
     */
    public @NotNull String serverId() {
        return CrossServer.serverId(plugin);
    }

    /**
     * Which server of the network a player is on.
     *
     * <p>This server's own name when they are here, answered without asking
     * Redis; empty for a player the network does not know, and always empty
     * without Redis. Worth asking before drawing a "teleport" button, and for
     * a menu row that says where somebody is.
     *
     * @param player who to find
     * @return the server's {@code server-id}, or empty
     * @since 1.98.0
     */
    public @NotNull CompletableFuture<Optional<String>> serverOf(@NotNull UUID player) {
        return CrossServer.serverOf(plugin, player);
    }

    /**
     * Describes a teleport to a stored string.
     *
     * <p>Accepts both formats ExyliaCommons wrote. A string that is neither is
     * reported to the console and yields a request that completes
     * {@link TeleportResult#FAILED}.
     *
     * @param player     who to move
     * @param serialized the stored place
     * @return the request to describe and start
     */
    public @NotNull TeleportRequest to(@NotNull Player player, @NotNull String serialized) {
        try {
            return to(player, ExyliaLocation.fromString(serialized));
        } catch (IllegalArgumentException unreadable) {
            debug.error("Could not read the stored location '" + serialized + "'", unreadable);
            return new TeleportRequest(plugin, tasks, debug, player, null,
                    TeleportResult.FAILED, settings);
        }
    }

    /**
     * Moves several players to one place, at once and with no countdown.
     *
     * <p>For the end of a game, where everybody goes back to the lobby. A
     * countdown makes no sense here: nobody asked, so there is nothing for them
     * to change their mind about.
     *
     * @param players     who to move
     * @param destination where to
     * @return a future that completes when every one of them has been dealt
     *         with, however each of them ended
     */
    public @NotNull CompletableFuture<Void> toAll(@NotNull Collection<? extends Player> players,
                                                  @NotNull Location destination) {
        List<CompletableFuture<TeleportResult>> each = new ArrayList<>(players.size());
        for (Player player : players) {
            each.add(to(player, destination).start().future());
        }
        return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new));
    }

    // ------------------------------------------------------------------- back

    /**
     * Where this player would go back to, without spending it.
     *
     * <p>For a message or a menu that names the place before the player commits
     * to it. Reading does not take the entry, so asking twice is free and
     * neither answer costs them their undo.
     *
     * @param player the player
     * @return where they came from, or empty when there is nothing recorded or
     *         what was recorded is older than the configured limit
     */
    public @NotNull Optional<ExyliaLocation> lastLocationOf(@NotNull Player player) {
        return Optional.ofNullable(BackHistory.peek(
                player.getUniqueId(), settings.backHistoryMinutes()));
    }

    /**
     * Describes a teleport back to where this player last came from.
     *
     * <pre>{@code
     * teleports.back(player)
     *         .warmup(config.backWarmup())
     *         .then(result -> {
     *             if (result == TeleportResult.NOTHING_TO_GO_BACK_TO) {
     *                 Text.of("{warning}There is nowhere to go back to.").send(player);
     *             }
     *         })
     *         .start();
     * }</pre>
     *
     * <p>Nothing recorded yields a request that completes
     * {@link TeleportResult#NOTHING_TO_GO_BACK_TO} rather than {@code null}: a
     * first {@code /back} of a session is normal, and a caller who has to
     * null-check before describing a teleport writes the check once per
     * command instead of never.
     *
     * <h2>The entry is spent, and given back if it was not used</h2>
     * The place is taken here rather than read, which is what stops a player
     * bouncing between two points from growing the stack forever. But a
     * teleport that does not succeed — a cooldown, a countdown they walked out
     * of, a world that unloaded — puts it straight back. An undo the player
     * never received is not one they should have spent, which is the same rule
     * the cooldown refund follows.
     *
     * @param player who to send back
     * @return the request to describe and start
     */
    public @NotNull TeleportRequest back(@NotNull Player player) {
        BackHistory.Recorded taken = BackHistory.pop(
                player.getUniqueId(), settings.backHistoryMinutes());
        if (taken == null) {
            return new TeleportRequest(plugin, tasks, debug, player, null,
                    TeleportResult.NOTHING_TO_GO_BACK_TO, settings)
                    .cause(TeleportCause.BACK);
        }
        Location live = taken.where().toBukkitLocation();
        if (live == null) {
            // The world went away while the entry sat in memory. The entry is
            // not restored: it will not work on the next attempt either, and
            // handing it back would make every /back from now on fail on the
            // same dead world.
            debug.warn("A /back was aimed at world '" + taken.where().world()
                    + "', which is not loaded on this server.");
            return new TeleportRequest(plugin, tasks, debug, player, null,
                    TeleportResult.WORLD_NOT_FOUND, settings)
                    .cause(TeleportCause.BACK);
        }
        return new TeleportRequest(plugin, tasks, debug, player, live, null, settings)
                .cause(TeleportCause.BACK)
                .bookkeeping(result -> {
                    if (!result.isSuccess()) {
                        BackHistory.restore(player.getUniqueId(), taken,
                                settings.backHistorySize());
                    }
                });
    }

    /**
     * Forgets everywhere this player has been.
     *
     * <p>For a plugin that moves somebody somewhere they must not be able to
     * walk back out of: an arena, a jail, a staff-only world.
     *
     * @param player the player
     */
    public void forgetHistory(@NotNull Player player) {
        BackHistory.forget(player.getUniqueId());
    }

    // -------------------------------------------------------------------- tpa

    /**
     * Files a request from one player to another.
     *
     * <p>Nobody moves. The target answers it with {@link #accept} or
     * {@link #deny}, and until then it sits in memory costing nothing: no timer
     * watches it, and the expiry is read when somebody looks.
     *
     * @param from      who is asking
     * @param to        who is being asked
     * @param direction which of them moves if it is accepted
     * @return what happened, which is a different message to the sender for
     *         every value
     */
    public @NotNull TpaOutcome request(@NotNull Player from, @NotNull Player to,
                                       @NotNull TeleportDirection direction) {
        Objects.requireNonNull(direction, "direction");
        if (from.getUniqueId().equals(to.getUniqueId())) {
            return TpaOutcome.SELF;
        }
        if (TpaBook.get(to.getUniqueId(), from.getUniqueId()) != null) {
            return TpaOutcome.ALREADY_PENDING;
        }
        if (TpaBook.countFor(to.getUniqueId()) >= settings.tpaMaxPending()) {
            // The anti-spam limit. Without it one player buries another's list
            // until the request they actually wanted is unfindable.
            return TpaOutcome.TARGET_BUSY;
        }
        TeleportRequestTicket ticket = new TeleportRequestTicket(
                from.getUniqueId(), to.getUniqueId(), direction,
                Instant.now().plusSeconds(settings.tpaExpirySeconds()), plugin);
        return TpaBook.put(ticket) ? TpaOutcome.SENT : TpaOutcome.ALREADY_PENDING;
    }

    /**
     * One live request between two players.
     *
     * @param target who was asked
     * @param from   who asked
     * @return the request, or empty when there is none or it ran out
     */
    public @NotNull Optional<TeleportRequestTicket> pendingFor(@NotNull Player target,
                                                               @NotNull Player from) {
        return Optional.ofNullable(TpaBook.get(target.getUniqueId(), from.getUniqueId()));
    }

    /**
     * Every live request waiting for a player, soonest to run out first.
     *
     * @param target who was asked
     * @return the requests, which may be empty
     */
    public @NotNull List<TeleportRequestTicket> pendingFor(@NotNull Player target) {
        return TpaBook.liveFor(target.getUniqueId());
    }

    /**
     * Accepts a request, and hands back the teleport it turned into.
     *
     * <pre>{@code
     * TpaAcceptance accepted = teleports.accept(player, sender);
     *
     * accepted.teleport().ifPresent(request -> request
     *         .warmup(config.tpaWarmup())
     *         .onArrive(config.tpaArrived())
     *         .start());
     * }</pre>
     *
     * <p>The teleport is <em>not</em> started. The module knows who moves and
     * where; it has no idea whether this server makes people stand still first,
     * charges a cooldown or plays anything, and answering all three on the
     * caller's behalf would be wrong on most servers.
     *
     * <p>The ticket is removed either way. A request that was accepted and then
     * cancelled by a countdown is answered: leaving it on file would let the
     * target accept the same one twice.
     *
     * @param target who was asked, and who is answering
     * @param from   who asked
     * @return what happened, and the teleport when something was accepted
     */
    public @NotNull TpaAcceptance accept(@NotNull Player target, @NotNull Player from) {
        // Asked before the read, because reading is what drops an expired one:
        // asking afterwards would find nothing on file and report "there is no
        // request from that person" to somebody whose request simply ran out.
        boolean onFile = TpaBook.hasAny(target.getUniqueId(), from.getUniqueId());
        TeleportRequestTicket ticket = TpaBook.get(target.getUniqueId(), from.getUniqueId());
        if (ticket == null) {
            // "There was one and it ran out" and "there never was one" send the
            // player to check two different things.
            TpaBook.remove(target.getUniqueId(), from.getUniqueId());
            return TpaAcceptance.of(onFile ? TpaOutcome.EXPIRED : TpaOutcome.NO_REQUEST);
        }
        TpaBook.remove(target.getUniqueId(), from.getUniqueId());

        Player traveller = ticket.traveller().equals(from.getUniqueId()) ? from : target;
        Player anchor = ticket.anchor().equals(from.getUniqueId()) ? from : target;
        Location destination = anchor.getLocation();
        if (!traveller.isOnline() || !anchor.isOnline() || destination == null) {
            // One of them left between the request and the answer. Not an
            // expiry: the request was live, the person was not.
            return TpaAcceptance.of(TpaOutcome.NO_REQUEST);
        }
        return TpaAcceptance.accepted(
                new TeleportRequest(plugin, tasks, debug, traveller, destination, null, settings)
                        .cause(TeleportCause.TPA));
    }

    /**
     * Refuses a request.
     *
     * @param target who was asked
     * @param from   who asked
     * @return what happened
     */
    public @NotNull TpaOutcome deny(@NotNull Player target, @NotNull Player from) {
        return remove(target.getUniqueId(), from.getUniqueId(), TpaOutcome.DENIED);
    }

    /**
     * Withdraws a request the sender no longer wants answered.
     *
     * @param from who asked
     * @param to   who was asked
     * @return what happened
     */
    public @NotNull TpaOutcome cancel(@NotNull Player from, @NotNull Player to) {
        return remove(to.getUniqueId(), from.getUniqueId(), TpaOutcome.CANCELLED);
    }

    /**
     * Drops every request on the server that has run out.
     *
     * <p>Not needed for correctness — every read already drops what it finds —
     * but a command that reports the server's own state wants a number.
     *
     * @return how many were dropped
     */
    public int expireStale() {
        return TpaBook.expireStale();
    }

    /** Removes a ticket and says which of the three answers applies. */
    private TpaOutcome remove(java.util.UUID target, java.util.UUID from, TpaOutcome removed) {
        TeleportRequestTicket ticket = TpaBook.remove(target, from);
        if (ticket == null) {
            return TpaOutcome.NO_REQUEST;
        }
        return ticket.isExpired() ? TpaOutcome.EXPIRED : removed;
    }

    // ----------------------------------------------------------------- random

    /**
     * Describes a teleport to somewhere in an area, chosen at random.
     *
     * <pre>{@code
     * teleports.random(player, RandomArea.around(spawn, 500, 5_000))
     *         .warmup(5.0)
     *         .cooldown("rtp", 300.0)
     *         .start();
     * }</pre>
     *
     * <p>The search runs <em>after</em> the countdown, never before it: every
     * attempt loads a chunk, and doing that for a player who is about to walk
     * out of the countdown is chunk generation nobody asked for. Running out of
     * attempts completes {@link TeleportResult#NO_SAFE_LOCATION} and moves
     * nobody.
     *
     * @param player who to move
     * @param area   where they may end up
     * @return the request to describe and start
     */
    public @NotNull TeleportRequest random(@NotNull Player player, @NotNull RandomArea area) {
        Objects.requireNonNull(area, "area");
        return new TeleportRequest(plugin, tasks, debug, player, null, null, settings)
                .cause(TeleportCause.RANDOM)
                .searching(area);
    }

    // ----------------------------------------------------------- cross-server

    /**
     * Whether this server can hand a player to another one.
     *
     * <p>Worth asking before offering a button that would not work. False on a
     * server with no Redis configured, which is a normal arrangement rather
     * than a fault: a single server has nowhere to hand anybody to.
     *
     * @return whether a handover would be attempted
     */
    public boolean isCrossServerAvailable() {
        return CrossServer.isAvailable(plugin);
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Whether this player is waiting out a countdown.
     *
     * <p>Worth asking before anything that would fight it, in the same way as
     * a preview: a second teleport, a combat check, a menu that moves them.
     *
     * @param player the player
     * @return whether they are counting down
     */
    public boolean isWarmingUp(@NotNull Player player) {
        return TeleportRuntime.isWarmingUp(player.getUniqueId());
    }

    /**
     * Ends this player's countdown, if they are in one.
     *
     * @param player the player
     */
    public void cancelWarmup(@NotNull Player player) {
        TeleportRuntime.cancel(player.getUniqueId());
    }

    /**
     * Ends every countdown this plugin started.
     *
     * @return how many were ended
     */
    public int endAll() {
        return TeleportRuntime.endAllOf(plugin.getName());
    }

    @Override
    public String toString() {
        return "PluginTeleports[" + plugin.getName() + ']';
    }
}
