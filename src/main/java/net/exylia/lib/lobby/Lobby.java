package net.exylia.lib.lobby;

import net.exylia.lib.effect.Ticks;
import net.exylia.lib.session.Claim;
import net.exylia.lib.session.PluginSessions;
import net.exylia.lib.session.Sessions;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.PluginMessages;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.TimeFormats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Tables, seats and invitations: who plays with whom, before anything is played.
 *
 * <pre>{@code
 * private Lobby<Match> lobby;
 *
 * @Override
 * public void onEnable() {
 *     lobby = Lobby.of(this, hooks, () -> messages.get().tables());
 * }
 *
 * // /checkers create          an open table anybody may take
 * lobby.create(host, match, rules, null);
 * // /checkers duel <player>   an invitation, answered with clickable buttons
 * lobby.create(host, match, rules, target);
 * // /checkers accept [host], /checkers deny [host], /checkers start, /checkers cancel, /checkers leave
 * lobby.accept(target, hostName);
 * lobby.decline(target, hostName);
 * lobby.start(host);
 * lobby.cancel(host);
 * lobby.leave(player);
 *
 * // the plugin plays: LobbyHooks.started seats them, then
 * lobby.play(table);
 * ...
 * lobby.finish(table);         // or lobby.cancel(table)
 * }</pre>
 *
 * <h2>Two ways to fill a table</h2>
 * An <b>open table</b> is listed ({@link #open}) and its seats go to whoever takes them first:
 * taking one is a reservation under the table's lock, so two players taking the last seat cannot
 * both get it. An <b>invitation</b> is sent to one player, who accepts or declines; at a table of
 * more than two it opens to everyone once they sit down. A table starts by itself when full, or when
 * its host starts it with at least {@link TableRules#minSeats} seated. One that waits past its expiry
 * ends as {@link TableEnd#EXPIRED}.
 *
 * <h2>One table at a time, server-wide</h2>
 * Taking a seat claims the player ({@link Sessions}), so every other plugin sees them as busy, and
 * a player another plugin has is refused. The claim lasts until they leave or the table ends;
 * another plugin asking for them back makes them {@link #leave}.
 *
 * <h2>No stakes in here</h2>
 * The lobby holds nothing but seats. {@link LobbyHooks#admit} is where a plugin holds a stake (an
 * {@code Escrow} hold) or refuses a seat, {@link LobbyHooks#ended} where it pays out or refunds.
 *
 * <h2>Threads</h2>
 * Every method is safe from any thread. Messages are sent on each player's own thread; hooks run as
 * {@link LobbyHooks} says. Expiry timers are the plugin's tasks, cancelled when it is disabled, and
 * its claims are dropped by the session module then; {@link #cancelAll} ends every table first, for a
 * shutdown or a reload that must give stakes back.
 *
 * @param <T> what the plugin plays at a table
 * @since 1.356.0
 */
public final class Lobby<T> {

    private final PluginMessages messages;
    private final PluginSessions sessions;
    private final TaskScheduler tasks;
    private final Plugin plugin;
    private final LobbyHooks<T> hooks;
    private final Supplier<LobbyMessages> text;
    private final Map<String, Table<T>> tables = new ConcurrentHashMap<>();
    /** Every player a live table holds a seat for, the host of an open table included. */
    private final Map<UUID, Table<T>> byPlayer = new ConcurrentHashMap<>();

    private Lobby(Plugin plugin, LobbyHooks<T> hooks, Supplier<LobbyMessages> text) {
        this.plugin = plugin;
        this.hooks = hooks;
        this.text = text;
        this.messages = PluginMessages.of(plugin);
        this.sessions = Sessions.of(plugin);
        this.tasks = Tasks.of(plugin);
    }

    /**
     * A lobby for a plugin.
     *
     * @param plugin   whose claims, tasks and {@code %prefix%} it uses
     * @param hooks    what the plugin does at each step
     * @param messages its lines, read each time one is sent, so a reload applies at once
     * @return a new lobby; hold one per kind of table list
     */
    public static <T> @NotNull Lobby<T> of(@NotNull Plugin plugin, @NotNull LobbyHooks<T> hooks,
                                           @NotNull Supplier<LobbyMessages> messages) {
        return new Lobby<>(Objects.requireNonNull(plugin, "plugin"), Objects.requireNonNull(hooks, "hooks"),
                Objects.requireNonNull(messages, "messages"));
    }

    // ── Reading ───────────────────────────────────────────────────────────

    /** The table a player holds a seat at, waiting or playing. */
    public @NotNull Optional<Table<T>> of(@NotNull UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public @NotNull Optional<Table<T>> get(@NotNull String id) {
        return Optional.ofNullable(tables.get(id));
    }

    public @NotNull List<Table<T>> all() {
        return List.copyOf(tables.values());
    }

    /** The open tables anybody may take whose subject passes {@code filter}, oldest first. */
    public @NotNull List<Table<T>> open(@NotNull Predicate<T> filter) {
        return tables.values().stream()
                .filter(table -> table.state() == TableState.OPEN && table.isPublic() && filter.test(table.subject()))
                .sorted(Comparator.comparingLong(Table::createdAt))
                .toList();
    }

    /** Whether the player sits at a table that has left the lobby. */
    public boolean isPlaying(@NotNull UUID player) {
        Table<T> table = byPlayer.get(player);
        return table != null && (table.state() == TableState.STARTING || table.state() == TableState.PLAYING);
    }

    /** The names of the players with an invitation waiting for this one, newest first: for suggestions. */
    public @NotNull List<String> invitesTo(@NotNull UUID target) {
        return invitations(target).stream().map(table -> table.host().name()).toList();
    }

    private List<Table<T>> invitations(UUID target) {
        return tables.values().stream()
                .filter(table -> target.equals(table.invitee()) && !table.isPublic() && table.state() == TableState.OPEN)
                .sorted(Comparator.comparingLong(Table<T>::createdAt).reversed())
                .toList();
    }

    // ── Opening ───────────────────────────────────────────────────────────

    /**
     * Opens a table: listed for anybody when {@code invitee} is {@code null}, an invitation otherwise.
     * The host's seat is admitted ({@link LobbyHooks#admit}) before the table is listed or the
     * invitation sent.
     *
     * @return the table, still {@link TableState#CREATING}; empty when the host or invitee was refused
     */
    public @NotNull Optional<Table<T>> create(@NotNull Player host, @NotNull T subject, @NotNull TableRules rules,
                                              @Nullable Player invitee) {
        LobbyMessages lines = text.get();
        if (invitee != null) {
            if (invitee.getUniqueId().equals(host.getUniqueId())) {
                send(host, lines.self(), null);
                return Optional.empty();
            }
            if (byPlayer.containsKey(invitee.getUniqueId()) || !Sessions.isFree(invitee)) {
                send(host, lines.targetBusy(), Values.of().putText("player", invitee.getName()));
                return Optional.empty();
            }
        }
        Table<T> table = new Table<>(subject, rules, new Seat(host.getUniqueId(), host.getName()),
                invitee == null ? null : invitee.getUniqueId(), System.currentTimeMillis());
        if (!take(host, table, null)) return Optional.empty();
        tables.put(table.id(), table);
        admit(table, host, () -> {
            if (!table.advance(TableState.CREATING, TableState.OPEN)) {
                // Called off while the admission ran: nothing will end this table again, so the seat goes back now.
                hooks.released(table, table.host());
                return;
            }
            hooks.joined(table, table.host());
            Duration expiry = invitee == null ? rules.openExpiry() : rules.inviteExpiry();
            expireIn(table, expiry);
            if (invitee == null) {
                send(host, lines.created(), values(table));
            } else {
                send(host, lines.inviteSent(), values(table).putText("player", invitee.getName()));
                invite(table, host, invitee, expiry);
            }
        }, () -> end(table, null));
        return Optional.of(table);
    }

    private void invite(Table<T> table, Player host, Player invitee, Duration expiry) {
        LobbyMessages lines = text.get();
        String command = table.rules().command();
        Values values = values(table)
                .putText("player", host.getName())
                .put("time", TimeFormats.render(expiry, TimeFormats.Style.AUTO))
                .put("accept", button(command + " accept " + host.getName(), lines.acceptHover(), lines.acceptButton()))
                .put("decline", button(command + " deny " + host.getName(), lines.declineHover(), lines.declineButton()));
        onPlayer(invitee, () -> messages.send(invitee, lines.invite(), values));
    }

    private static String button(String command, String hover, String label) {
        return "<click:run_command:'" + command + "'><hover:show_text:'" + hover + "'>" + label + "</hover></click>";
    }

    // ── Joining ───────────────────────────────────────────────────────────

    /** Takes a seat at an open table, or at an invitation sent to this player. The host taking their own seat closes it. */
    public void join(@NotNull Player player, @NotNull Table<T> table) {
        LobbyMessages lines = text.get();
        if (table.seatOf(player.getUniqueId()) == 0) {
            cancel(player);
            return;
        }
        if (table.state() != TableState.OPEN) {
            send(player, lines.gone(), null);
            return;
        }
        Seat seat = new Seat(player.getUniqueId(), player.getName());
        if (!take(player, table, () -> table.reserve(seat))) return;
        admit(table, player, () -> {
            hooks.joined(table, seat);
            if (table.state() == TableState.STARTING) return;
            if (player.getUniqueId().equals(table.invitee()) && !table.isFull()) {
                // The invitation is now an open table: it waits as long as one does.
                expireIn(table, table.rules().openExpiry());
            }
            waiting(table, seat);
        }, () -> {
            Table.Released released = table.release(player.getUniqueId());
            if (released != null && released.admitted()) hooks.released(table, released.seat());
            // Its timer may have run while the table was full; reopened, it still expires.
            if (table.state() == TableState.OPEN && table.expiresAt() <= System.currentTimeMillis()) expire(table);
        });
    }

    /**
     * Accepts an invitation.
     *
     * @param host the name of the player who sent it, as typed; {@code null} for the newest one
     */
    public void accept(@NotNull Player target, @Nullable String host) {
        Optional<Table<T>> invitation = invitationFrom(target, host);
        if (invitation.isEmpty()) {
            send(target, text.get().noInvite(), Values.of().putText("player", host == null ? "" : host));
            return;
        }
        join(target, invitation.get());
    }

    /**
     * Declines an invitation, ending it as {@link TableEnd#DECLINED}.
     *
     * @param host the name of the player who sent it, as typed; {@code null} for the newest one
     */
    public void decline(@NotNull Player target, @Nullable String host) {
        LobbyMessages lines = text.get();
        Optional<Table<T>> invitation = invitationFrom(target, host);
        if (invitation.isEmpty() || !invitation.get().advance(TableState.OPEN, TableState.ENDED)) {
            send(target, lines.noInvite(), Values.of().putText("player", host == null ? "" : host));
            return;
        }
        Table<T> table = invitation.get();
        close(table, TableEnd.DECLINED);
        send(target, lines.youDeclined(), values(table).putText("player", table.host().name()));
        send(Bukkit.getPlayer(table.host().player()), lines.declined(), values(table).putText("player", target.getName()));
    }

    private Optional<Table<T>> invitationFrom(Player target, @Nullable String host) {
        return invitations(target.getUniqueId()).stream()
                .filter(table -> host == null || table.host().name().equalsIgnoreCase(host))
                .findFirst();
    }

    /** The host starts their table before it is full, with at least {@link TableRules#minSeats} seated. */
    public void start(@NotNull Player host) {
        LobbyMessages lines = text.get();
        Table<T> table = byPlayer.get(host.getUniqueId());
        if (table == null || table.seatOf(host.getUniqueId()) != 0 || table.state() != TableState.OPEN) {
            send(host, lines.noTable(), null);
            return;
        }
        if (!table.startEarly()) {
            send(host, lines.cannotStart(), values(table));
            return;
        }
        begin(table);
    }

    /** The host closes their own table while it waits, ending it as {@link TableEnd#CANCELLED}. */
    public void cancel(@NotNull Player host) {
        Table<T> table = byPlayer.get(host.getUniqueId());
        if (table == null || table.seatOf(host.getUniqueId()) != 0 || !closeByHost(table)) {
            send(host, text.get().noTable(), null);
        }
    }

    /** Ends a waiting table on its host's word, telling everyone at it. */
    private boolean closeByHost(Table<T> table) {
        if (!table.advance(TableState.OPEN, TableState.ENDED)) return false;
        close(table, TableEnd.CANCELLED);
        LobbyMessages lines = text.get();
        Values values = values(table).putText("player", table.host().name());
        send(Bukkit.getPlayer(table.host().player()), lines.cancelled(), values);
        for (Seat seat : table.seats().subList(1, table.seats().size())) {
            send(Bukkit.getPlayer(seat.player()), lines.closed(), values);
        }
        return true;
    }

    /**
     * The player walks away from whatever table they are at: a waiting seat is given back (the host's
     * closes the table), a table about to start is called off, and a table being played goes on
     * without them ({@link LobbyHooks#dropped}). What quitting, dying, the leave command and another
     * plugin asking for the player all come to.
     *
     * @return whether they were at a table
     */
    public boolean leave(@NotNull Player player) {
        return leave(player.getUniqueId());
    }

    /** As {@link #leave(Player)}, for a player who may be offline already. */
    public boolean leave(@NotNull UUID player) {
        Table<T> table = byPlayer.get(player);
        if (table == null) return false;
        LobbyMessages lines = text.get();
        int index = table.seatOf(player);
        switch (table.state()) {
            case CREATING, OPEN -> {
                if (index == 0) {
                    // A table still being created never opened: its host's admission, when it answers, gives the seat back.
                    if (!closeByHost(table) && table.state() == TableState.CREATING) end(table, null);
                    return true;
                }
                Table.Released released = table.release(player);
                if (released == null) return true;
                unseat(table, player);
                if (released.admitted()) hooks.released(table, released.seat());
                Values values = values(table).putText("player", released.seat().name());
                for (Seat seat : table.seats()) send(Bukkit.getPlayer(seat.player()), lines.left(), values);
            }
            case STARTING -> {
                if (table.end() == null) return true;
                close(table, TableEnd.CANCELLED);
                Values values = values(table).putText("player", index < 0 ? "" : table.seats().get(index).name());
                for (Seat seat : table.seats()) {
                    if (!seat.player().equals(player)) send(Bukkit.getPlayer(seat.player()), lines.abandoned(), values);
                }
            }
            case PLAYING -> {
                if (table.drop(player) < 0) return true;
                unseat(table, player);
                hooks.dropped(table, table.seats().get(index));
            }
            case ENDED -> {
            }
        }
        return true;
    }

    // ── Playing ───────────────────────────────────────────────────────────

    /**
     * The plugin begins the round of a table it was handed by {@link LobbyHooks#started}.
     *
     * @return whether this call moved it to {@link TableState#PLAYING}; {@code false} when it was
     *         called off in the meantime
     */
    public boolean play(@NotNull Table<T> table) {
        return table.advance(TableState.STARTING, TableState.PLAYING);
    }

    /**
     * The table was played out: it ends as {@link TableEnd#FINISHED} and every seat is freed.
     *
     * @return whether this call ended it
     */
    public boolean finish(@NotNull Table<T> table) {
        return end(table, TableEnd.FINISHED);
    }

    /**
     * Calls a table off from whatever state it is in, ending it as {@link TableEnd#CANCELLED}: an
     * admin's call, a round that cannot start, a reload. Sends nothing; the plugin says why.
     *
     * @return whether this call ended it
     */
    public boolean cancel(@NotNull Table<T> table) {
        return end(table, TableEnd.CANCELLED);
    }

    /** Calls off every table, for a shutdown or a reload. */
    public void cancelAll() {
        List.copyOf(tables.values()).forEach(this::cancel);
    }

    // ── Steps ─────────────────────────────────────────────────────────────

    /**
     * Seats a player: refused when they are at a table already or another plugin has them, then
     * claimed and recorded, then {@code reserve} (when given) takes their seat. Undone on any refusal.
     */
    private boolean take(Player player, Table<T> table, @Nullable Supplier<Boolean> reserve) {
        LobbyMessages lines = text.get();
        UUID id = player.getUniqueId();
        if (byPlayer.containsKey(id) || sessions.holds(id)) {
            send(player, lines.busy(), null);
            return false;
        }
        Optional<Claim> other = Sessions.holder(id);
        if (other.isPresent()) {
            send(player, lines.busyElsewhere(), Values.of().putText("plugin", other.get().plugin()));
            return false;
        }
        Optional<String> advice = Sessions.blocker(id, plugin.getName(), table.rules().kind());
        if (advice.isPresent()) {
            send(player, lines.busyAdvice(), Values.of().putText("reason", advice.get()));
            return false;
        }
        if (byPlayer.putIfAbsent(id, table) != null) {
            send(player, lines.busy(), null);
            return false;
        }
        Optional<Claim> claim = sessions.claim(id, table.rules().kind(), () -> {
            leave(id);
            return true;
        });
        if (claim.isEmpty()) {
            byPlayer.remove(id, table);
            send(player, lines.busyElsewhere(), Values.of().putText("plugin",
                    Sessions.holder(id).map(Claim::plugin).orElse("")));
            return false;
        }
        table.claim(id, claim.get());
        if (reserve != null && !reserve.get()) {
            unseat(table, id);
            send(player, table.state() == TableState.OPEN ? lines.gone() : lines.full(), null);
            return false;
        }
        return true;
    }

    /**
     * Asks the plugin to admit a seat just taken. On a yes, {@code then} runs, and the table starts if
     * that seat was the last one awaited; on a refusal {@code undo} gives the seat back and the
     * player is told why. A yes for a seat that went while it was asked is given back.
     */
    private void admit(Table<T> table, Player player, Runnable then, Runnable undo) {
        CompletableFuture<String> answer;
        try {
            answer = Objects.requireNonNull(hooks.admit(table, player), "admit");
        } catch (RuntimeException failure) {
            answer = CompletableFuture.failedFuture(failure);
        }
        answer.whenComplete((refusal, failure) -> {
            UUID id = player.getUniqueId();
            if (failure != null || refusal != null) {
                if (failure != null) {
                    plugin.getLogger().warning("Admitting " + player.getName() + " to table " + table.id() + " failed: " + failure);
                }
                undo.run();
                unseat(table, id);
                send(player, failure != null ? text.get().failed() : refusal, values(table));
                return;
            }
            if (!table.admitted(id)) {
                // Gone while it was asked: nothing will end this seat again, so what it took goes back now.
                unseat(table, id);
                int index = table.seatOf(id);
                hooks.released(table, index >= 0 ? table.seats().get(index) : new Seat(id, player.getName()));
                return;
            }
            then.run();
            begin(table);
        });
    }

    /** Hands a table that has every seat admitted to the plugin to be played. */
    private void begin(Table<T> table) {
        if (!table.beginStart()) return;
        table.timer(null);
        hooks.started(table);
    }

    /**
     * Tells everyone at a table still filling who sat down and how many are missing, and offers the
     * host to start early once enough have.
     */
    private void waiting(Table<T> table, Seat joined) {
        LobbyMessages lines = text.get();
        Values values = values(table).putText("player", joined.name());
        for (Seat seat : table.seats()) send(Bukkit.getPlayer(seat.player()), lines.waiting(), values);
        TableRules rules = table.rules();
        if (rules.startsEarly() && table.seats().size() >= rules.minSeats()) {
            send(Bukkit.getPlayer(table.host().player()), lines.startable(), values(table).put("start",
                    button(rules.command() + " start", lines.startHover(), lines.startButton())));
        }
    }

    /** An open table or an invitation nobody took in time. */
    private void expire(Table<T> table) {
        if (!table.advance(TableState.OPEN, TableState.ENDED)) return;
        close(table, TableEnd.EXPIRED);
        LobbyMessages lines = text.get();
        List<Seat> seats = table.seats();
        if (seats.size() > 1) {
            for (Seat seat : seats) send(Bukkit.getPlayer(seat.player()), lines.tableExpired(), values(table));
        } else if (table.isInvitation()) {
            Player invitee = Bukkit.getPlayer(Objects.requireNonNull(table.invitee()));
            send(Bukkit.getPlayer(table.host().player()), lines.inviteExpired(),
                    values(table).putText("player", invitee == null ? "" : invitee.getName()));
            send(invitee, lines.inviteExpiredTarget(), values(table).putText("player", table.host().name()));
        } else {
            send(Bukkit.getPlayer(table.host().player()), lines.openExpired(), values(table));
        }
    }

    private void expireIn(Table<T> table, Duration expiry) {
        table.expireAt(System.currentTimeMillis() + expiry.toMillis());
        table.timer(tasks.runLater(Math.max(1L, Ticks.fromMillis(expiry.toMillis())), () -> expire(table)));
    }

    /**
     * Ends a table from any state.
     *
     * @param end why, or {@code null} to end it without telling the plugin: a table whose host was
     *            refused never opened, so there is nothing to give back
     */
    private boolean end(Table<T> table, @Nullable TableEnd end) {
        if (table.end() == null) return false;
        if (end == null) {
            forget(table);
            return true;
        }
        close(table, end);
        return true;
    }

    /** What every end does once the table is {@link TableState#ENDED}: forget it, free its players, tell the plugin. */
    private void close(Table<T> table, TableEnd end) {
        forget(table);
        hooks.ended(table, end);
    }

    private void forget(Table<T> table) {
        table.timer(null);
        tables.remove(table.id(), table);
        for (Seat seat : table.seats()) byPlayer.remove(seat.player(), table);
        table.unclaimAll().forEach(Claim::release);
    }

    /** Frees one player from a table: off its seat list, and their claim released. */
    private void unseat(Table<T> table, UUID player) {
        byPlayer.remove(player, table);
        Claim claim = table.unclaim(player);
        if (claim != null) claim.release();
    }

    private Values values(Table<T> table) {
        TableRules rules = table.rules();
        int seated = table.present().size();
        Values values = Values.of();
        Values own = hooks.values(table);
        if (own != null) values.map().putAll(own.map());
        return values.put("seated", seated)
                .put("seats", rules.maxSeats())
                .put("min", rules.minSeats())
                .put("missing", Math.max(0, rules.maxSeats() - seated))
                .put("command", rules.command());
    }

    private void send(@Nullable Player player, @Nullable String line, @Nullable Values values) {
        if (player == null || line == null || line.isBlank()) return;
        onPlayer(player, () -> messages.send(player, line, values));
    }

    private void onPlayer(Player player, Runnable work) {
        if (tasks.isOwnedBy(player)) work.run();
        else tasks.runAtEntity(player, work);
    }
}
