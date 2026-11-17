package net.exylia.lib.party;

import net.exylia.lib.chat.ChatIntercept;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.LibraryMessages;
import net.exylia.lib.text.PluginMessages;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.TimeFormats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Player parties, one set for the whole server: a party made for a dungeon is the same party a duels
 * or events plugin sees.
 *
 * <pre>{@code
 * @Override
 * public void onEnable() {
 *     Parties.use(this);                              // /party appears
 *     Parties.watch(this, new PartyWatcher() {
 *         @Override public void left(Party party, UUID player, PartyLeave reason) { runs.drop(player); }
 *     });
 * }
 *
 * Party party = Parties.of(player).orElse(null);       // null: playing alone
 * Parties.readyCheck(leader, "Crypt of Ash", Duration.ofSeconds(20))
 *        .thenAccept(result -> { if (result == ReadyResult.READY) runs.start(party); });
 * }</pre>
 *
 * <p>Every method is safe from any thread. Lines are sent on each player's own thread. The rules
 * live in {@link PartyBook}; this class adds the players, the lines and the watchers.
 *
 * <p>ponytail: one server only. Members on two servers of a network are not one party yet; a
 * plugin moving a party to another server carries the member list itself. Sync it over Redis when a
 * network feature needs it.
 *
 * @since 1.621.0
 */
public final class Parties {

    private static final PartyBook BOOK = new PartyBook(5);
    private static final Map<String, List<PartyWatcher>> WATCHERS = new ConcurrentHashMap<>();
    private static final Set<UUID> CHATTING = ConcurrentHashMap.newKeySet();
    private static final Set<String> USERS = ConcurrentHashMap.newKeySet();

    private static volatile Plugin library;
    private static volatile TaskScheduler tasks;
    private static volatile PluginMessages messages;
    private static volatile Runnable installCommand = () -> { };

    private Parties() {
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    /**
     * Starts the module. Called once by the library.
     *
     * @param plugin  the library
     * @param command installs {@code /party}, run the first time a plugin calls {@link #use}
     */
    public static void init(@NotNull Plugin plugin, @NotNull Runnable command) {
        library = plugin;
        tasks = Tasks.of(plugin);
        messages = PluginMessages.of(plugin);
        installCommand = command;
        BOOK.maxSize(settings().maxSize());
        Bukkit.getPluginManager().registerEvents(new Presence(), plugin);
        ChatIntercept.register(plugin, EventPriority.LOWEST, true, (player, message) -> {
            if (!CHATTING.contains(player.getUniqueId())) return false;
            if (BOOK.of(player.getUniqueId()) == null) {
                CHATTING.remove(player.getUniqueId());
                return false;
            }
            chat(player, message);
            return true;
        });
        tasks.runTimer(20, 20, Parties::sweep);
    }

    /**
     * Says this plugin uses parties, which puts {@code /party} on the server (once, whoever asks
     * first). A server where no plugin asks never gets the command, so it does not collide with a
     * party plugin of its own.
     *
     * @param plugin the plugin
     */
    public static void use(@NotNull Plugin plugin) {
        boolean first = USERS.isEmpty();
        USERS.add(plugin.getName());
        if (first) installCommand.run();
    }

    /**
     * Hears about every party change until the plugin is disabled.
     *
     * @param plugin  whose watcher it is
     * @param watcher what it wants to hear
     */
    public static void watch(@NotNull Plugin plugin, @NotNull PartyWatcher watcher) {
        WATCHERS.computeIfAbsent(plugin.getName(), k -> new CopyOnWriteArrayList<>()).add(watcher);
    }

    /** Drops a plugin's watchers. Called by the library when the plugin is disabled. */
    public static void release(@NotNull String plugin) {
        WATCHERS.remove(plugin);
        USERS.remove(plugin);
    }

    /** Ends every party quietly. Called by the library on shutdown. */
    public static void shutdown() {
        BOOK.clear();
        CHATTING.clear();
    }

    /** Reads the {@code parties:} settings again. Called by the library on reload. */
    public static void reload() {
        BOOK.maxSize(settings().maxSize());
    }

    /** The name {@code /party} answers to, with its slash. */
    public static @NotNull String command() {
        String name = settings().command().name();
        return "/" + (name == null || name.isBlank() ? "party" : name);
    }

    // ── Reading ───────────────────────────────────────────────────────────

    /** The party this player is in, offline members included. */
    public static @NotNull Optional<Party> of(@NotNull UUID player) {
        return Optional.ofNullable(BOOK.of(player));
    }

    /** The party this player is in. */
    public static @NotNull Optional<Party> of(@NotNull Player player) {
        return of(player.getUniqueId());
    }

    /** A party by its id, while it lives. */
    public static @NotNull Optional<Party> get(@NotNull UUID id) {
        return Optional.ofNullable(BOOK.get(id));
    }

    /** Every party on the server. */
    public static @NotNull List<Party> all() {
        return BOOK.all();
    }

    /** Whether two players are in the same party. */
    public static boolean together(@NotNull UUID a, @NotNull UUID b) {
        Party party = BOOK.of(a);
        return party != null && party.isMember(b);
    }

    /** The leaders whose invitations wait for this player, newest first: suggestions for accept. */
    public static @NotNull List<String> invitesTo(@NotNull UUID player) {
        return BOOK.invitesTo(player, now()).stream().map(p -> name(p.leader())).toList();
    }

    // ── Changing ──────────────────────────────────────────────────────────

    /** Invites a player, starting a party when the inviter has none. */
    public static void invite(@NotNull Player inviter, @NotNull Player target) {
        PartyMessages text = text();
        Duration expiry = settings().inviteExpiry();
        PartyBook.InviteOutcome outcome = BOOK.invite(inviter.getUniqueId(), target.getUniqueId(), now(),
                now() + expiry.toMillis());
        Party party = outcome.party();
        Values values = values(party).putText("player", target.getName()).put("time", time(expiry));
        switch (outcome.result()) {
            case SELF -> send(inviter, text.self(), values);
            case NOT_LEADER -> send(inviter, text.notLeader(), values);
            case ALREADY_MEMBER -> send(inviter, text.alreadyMember(), values);
            case ALREADY_INVITED -> send(inviter, text.alreadyInvited(), values);
            case FULL -> send(inviter, text.full(), values);
            case SENT -> {
                send(inviter, text.inviteSent(), values);
                String command = command();
                Values invite = values(party).putText("player", inviter.getName()).put("time", time(expiry))
                        .put("accept", button(command + " accept " + inviter.getName(), text.acceptHover(), text.acceptButton()))
                        .put("decline", button(command + " deny " + inviter.getName(), text.declineHover(), text.declineButton()));
                send(target, text.invite(), invite);
            }
        }
    }

    /**
     * Joins the party of an invitation, leaving the player's current party first.
     *
     * @param leader whose invitation, by name; {@code null} for the newest
     */
    public static void accept(@NotNull Player player, @Nullable String leader) {
        PartyMessages text = text();
        UUID from = leader == null ? null : leaderNamed(player.getUniqueId(), leader);
        if (leader != null && from == null) {
            send(player, text.noInvite(), null);
            return;
        }
        PartyBook.AcceptOutcome outcome = BOOK.accept(player.getUniqueId(), from, now());
        switch (outcome.result()) {
            case NO_INVITE -> send(player, text.noInvite(), null);
            case FULL -> send(player, text.inviteFull(), values(outcome.party()));
            case JOINED -> {
                if (outcome.left() != null) removed(outcome.left());
                Party party = outcome.party();
                if (party == null) return;
                sendAll(party, text.joined(), values(party).putText("player", player.getName()));
                fire(w -> w.joined(party, player.getUniqueId()));
            }
        }
    }

    /** Declines an invitation; {@code leader} by name, {@code null} for the newest. */
    public static void decline(@NotNull Player player, @Nullable String leader) {
        PartyMessages text = text();
        UUID from = leader == null ? null : leaderNamed(player.getUniqueId(), leader);
        PartyBook.Declined declined = leader != null && from == null ? null : BOOK.decline(player.getUniqueId(), from, now());
        if (declined == null) {
            send(player, text.noInvite(), null);
            return;
        }
        Party party = declined.party();
        send(player, text.youDeclined(), values(party).putText("player", name(party.leader())));
        send(Bukkit.getPlayer(party.leader()), text.declined(), values(party).putText("player", player.getName()));
        if (declined.ended()) fire(w -> w.ended(party));
    }

    /** Leaves the player's party. */
    public static void leave(@NotNull Player player) {
        PartyBook.Removal removal = BOOK.leave(player.getUniqueId());
        if (removal == null) {
            send(player, text().noParty(), values(null));
            return;
        }
        send(player, text().youLeft(), values(removal.party()));
        removed(removal);
    }

    /** The leader removes a member, named as they appear in the party. */
    public static void kick(@NotNull Player leader, @NotNull String member) {
        UUID target = memberNamed(leader.getUniqueId(), member);
        PartyBook.StaffOutcome outcome = target == null ? null : BOOK.kick(leader.getUniqueId(), target);
        if (outcome == null || outcome.result() != PartyBook.Staff.DONE) {
            refuse(leader, outcome, member);
            return;
        }
        PartyBook.Removal removal = outcome.removal();
        send(Bukkit.getPlayer(target), text().youWereKicked(), values(removal.party()));
        removed(removal);
    }

    /** The leader hands the lead to another member. */
    public static void promote(@NotNull Player leader, @NotNull String member) {
        UUID target = memberNamed(leader.getUniqueId(), member);
        PartyBook.StaffOutcome outcome = target == null ? null : BOOK.promote(leader.getUniqueId(), target);
        if (outcome == null || outcome.result() != PartyBook.Staff.DONE) {
            refuse(leader, outcome, member);
            return;
        }
        Party party = outcome.party();
        sendAll(party, text().leader(), values(party).putText("player", name(target)));
        fire(w -> w.leaderChanged(party, leader.getUniqueId()));
    }

    /** The leader ends the party. */
    public static void disband(@NotNull Player leader) {
        PartyBook.StaffOutcome outcome = BOOK.disband(leader.getUniqueId());
        if (outcome.result() != PartyBook.Staff.DONE) {
            refuse(leader, outcome, leader.getName());
            return;
        }
        Party party = outcome.party();
        sendAll(party, text().disbanded(), values(party).putText("player", leader.getName()));
        party.members().forEach(CHATTING::remove);
        fire(w -> w.ended(party));
    }

    /**
     * Sends a line to the party, or turns party chat on and off when there is no line.
     *
     * @param message what they typed; {@code null} or blank toggles their chat
     */
    public static void chat(@NotNull Player player, @Nullable String message) {
        Party party = BOOK.of(player.getUniqueId());
        if (party == null) {
            send(player, text().noParty(), values(null));
            return;
        }
        if (message == null || message.isBlank()) {
            boolean on = CHATTING.add(player.getUniqueId());
            if (!on) CHATTING.remove(player.getUniqueId());
            send(player, on ? text().chatOn() : text().chatOff(), values(party));
            return;
        }
        sendAll(party, text().chat(), values(party).putText("player", player.getName()).putText("message", message));
    }

    /** Tells a player about their party: its members and who is online. */
    public static void info(@NotNull Player player) {
        PartyMessages text = text();
        Party party = BOOK.of(player.getUniqueId());
        if (party == null) {
            send(player, text.noParty(), values(null));
            return;
        }
        Values values = values(party);
        send(player, text.infoHeader(), values);
        for (UUID member : party.members()) {
            send(player, text.infoMember(), values(party).putText("player", name(member))
                    .put("state", party.isOnline(member) ? text.online() : text.offline())
                    .put("role", party.isLeader(member) ? text.leaderTag() : ""));
        }
        send(player, text.infoFooter(), values);
    }

    // ── Ready checks ──────────────────────────────────────────────────────

    /**
     * Asks every member whether they are ready, with clickable buttons. The leader counts as ready.
     * A party of one is ready at once.
     *
     * @param leader  who asks; must lead a party
     * @param what    what is about to start, shown in the question
     * @param timeout how long the members have
     * @return how it ended, completed on the server's global thread a tick later (never inside
     * the party book's lock); {@link ReadyResult#READY} at once for a player alone, {@link
     * ReadyResult#CHANGED} at once for a member who does not lead
     */
    public static @NotNull CompletableFuture<ReadyResult> readyCheck(@NotNull Player leader, @NotNull String what,
                                                                    @NotNull Duration timeout) {
        Party party = BOOK.of(leader.getUniqueId());
        if (party == null || !party.isLeader(leader.getUniqueId())) {
            return CompletableFuture.completedFuture(party == null ? ReadyResult.READY : ReadyResult.CHANGED);
        }
        PartyBook.Ready check = BOOK.startReady(party.id(), what, now() + timeout.toMillis());
        if (check == null) return CompletableFuture.completedFuture(ReadyResult.CHANGED);
        CompletableFuture<ReadyResult> result = check.future.thenApplyAsync(r -> r, work -> tasks.run(work));
        if (check.future.isDone()) {
            if (check.future.getNow(null) == ReadyResult.OFFLINE) send(leader, text().readyOffline(), values(party));
            return result;
        }
        PartyMessages text = text();
        String command = command();
        Values ask = values(party).putText("player", leader.getName()).putText("what", what).put("time", time(timeout))
                .put("accept", button(command + " ready", text.readyHover(), text.readyButton()))
                .put("decline", button(command + " notready", text.notReadyHover(), text.notReadyButton()));
        for (UUID member : party.members()) {
            if (!member.equals(party.leader())) send(Bukkit.getPlayer(member), text.ready(), ask);
        }
        return result;
    }

    /** A member answers the running ready check. */
    public static void vote(@NotNull Player player, boolean ready) {
        PartyMessages text = text();
        PartyBook.VoteOutcome outcome = BOOK.vote(player.getUniqueId(), ready);
        Party party = outcome.party();
        switch (outcome.result()) {
            case NO_CHECK -> send(player, text.noCheck(), null);
            case ALREADY -> { }
            case COUNTED -> sendAll(party, text.readyVote(), values(party).putText("player", player.getName())
                    .put("ready", outcome.check().ready.size()));
            case DECLINED -> {
                sendAll(party, text.readyDeclined(), values(party).putText("player", player.getName()));
                outcome.check().future.complete(ReadyResult.DECLINED);
            }
            case ALL_READY -> {
                sendAll(party, text.readyAll(), values(party));
                outcome.check().future.complete(ReadyResult.READY);
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private static void sweep() {
        PartyBook.Sweep sweep = BOOK.sweep(now(), settings().rejoinGrace().toMillis());
        PartyMessages text = text();
        sweep.removed().forEach(Parties::removed);
        for (PartyBook.ExpiredInvite expired : sweep.invites()) {
            Party party = expired.party();
            send(Bukkit.getPlayer(party.leader()), text.inviteExpired(), values(party).putText("player", name(expired.target())));
            send(Bukkit.getPlayer(expired.target()), text.inviteExpiredTarget(), values(party).putText("player", name(party.leader())));
        }
        for (PartyBook.Ready check : sweep.timedOut()) {
            Party party = BOOK.of(check.ready.iterator().next());
            if (party != null) sendAll(party, text.readyTimedOut(), values(party));
            check.future.complete(ReadyResult.TIMED_OUT);
        }
        for (Party party : sweep.ended()) {
            party.members().forEach(CHATTING::remove);
            fire(w -> w.ended(party));
        }
    }

    /** Tells everyone a member went, and the watchers. */
    private static void removed(PartyBook.Removal removal) {
        PartyMessages text = text();
        Party party = removal.party();
        UUID player = removal.player();
        CHATTING.remove(player);
        Values values = values(party).putText("player", name(player));
        String line = switch (removal.reason()) {
            case LEFT, SWITCHED -> text.left();
            case KICKED -> text.kicked();
            case TIMED_OUT -> text.timedOut();
        };
        if (removal.ended()) {
            party.members().forEach(m -> send(Bukkit.getPlayer(m), text.ended(), values));
            party.members().forEach(CHATTING::remove);
        } else {
            sendAll(party, line, values);
            if (removal.newLeader() != null) {
                sendAll(party, text.leader(), values(party).putText("player", name(removal.newLeader())));
            }
        }
        if (removal.ready() != null) removal.ready().future.complete(ReadyResult.CHANGED);
        fire(w -> w.left(party, player, removal.reason()));
        if (removal.newLeader() != null && !removal.ended()) fire(w -> w.leaderChanged(party, player));
        if (removal.ended()) fire(w -> w.ended(party));
    }

    private static void refuse(Player leader, @Nullable PartyBook.StaffOutcome outcome, String member) {
        PartyMessages text = text();
        Party party = outcome == null ? BOOK.of(leader.getUniqueId()) : outcome.party();
        if (party == null) {
            send(leader, text.noParty(), values(null));
            return;
        }
        Values values = values(party).putText("player", member);
        PartyBook.Staff result = outcome == null ? PartyBook.Staff.NOT_MEMBER : outcome.result();
        switch (result) {
            case NOT_LEADER -> send(leader, text.notLeader(), values);
            case SELF, NOT_MEMBER -> send(leader, text.notMember(), values);
            default -> send(leader, text.noParty(), values);
        }
    }

    private static @Nullable UUID memberNamed(UUID asking, String name) {
        Party party = BOOK.of(asking);
        if (party == null) return null;
        for (UUID member : party.members()) {
            if (name.equalsIgnoreCase(name(member))) return member;
        }
        return null;
    }

    private static @Nullable UUID leaderNamed(UUID invitee, String name) {
        for (Party party : BOOK.invitesTo(invitee, now())) {
            if (name.equalsIgnoreCase(name(party.leader()))) return party.leader();
        }
        return null;
    }

    private static void fire(Consumer<PartyWatcher> call) {
        for (List<PartyWatcher> list : WATCHERS.values()) {
            for (PartyWatcher watcher : list) {
                try {
                    call.accept(watcher);
                } catch (RuntimeException e) {
                    Plugin plugin = library;
                    if (plugin != null) plugin.getLogger().log(java.util.logging.Level.WARNING, "A party watcher failed", e);
                }
            }
        }
    }

    private static Values values(@Nullable Party party) {
        Values values = Values.of().put("command", command()).put("max", party == null ? settings().maxSize() : party.maxSize());
        if (party == null) return values;
        return values.put("size", party.size()).putText("leader", name(party.leader()));
    }

    private static void sendAll(@Nullable Party party, @Nullable String line, Values values) {
        if (party == null) return;
        for (UUID member : party.members()) send(Bukkit.getPlayer(member), line, values);
    }

    private static void send(@Nullable Player player, @Nullable String line, @Nullable Values values) {
        if (player == null || line == null || line.isBlank() || messages == null) return;
        String ready = line.replace("%prefix%", text().prefix());
        onPlayer(player, () -> messages.send(player, ready, values));
    }

    private static void send(@Nullable Player player, @NotNull List<String> lines, @Nullable Values values) {
        if (player == null || lines.isEmpty() || messages == null) return;
        String prefix = text().prefix();
        List<String> ready = lines.stream().map(l -> l.replace("%prefix%", prefix)).toList();
        onPlayer(player, () -> messages.send(player, ready, values));
    }

    private static void onPlayer(Player player, Runnable work) {
        TaskScheduler scheduler = tasks;
        if (scheduler == null || scheduler.isOwnedBy(player)) work.run();
        else scheduler.runAtEntity(player, work);
    }

    private static String button(String command, String hover, String label) {
        return "<click:run_command:'" + command + "'><hover:show_text:'" + hover + "'>" + label + "</hover></click>";
    }

    private static String name(UUID player) {
        return ExyliaPlayers.nameOr(player, "?");
    }

    private static String time(Duration duration) {
        return TimeFormats.render(duration, TimeFormats.Style.AUTO);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static PartyMessages text() {
        return LibraryMessages.get().parties();
    }

    private static LibrarySettings.Parties settings() {
        LibrarySettings.Parties parties = LibrarySettings.get().parties();
        return parties != null ? parties : new LibrarySettings.Parties();
    }

    /** Quits and rejoins, for the grace period and the lead. */
    private static final class Presence implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            UUID player = event.getPlayer().getUniqueId();
            CHATTING.remove(player);
            Set<UUID> online = new HashSet<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (!p.getUniqueId().equals(player)) online.add(p.getUniqueId());
            Party before = BOOK.of(player);
            if (before == null) return;
            UUID newLeader = BOOK.quit(player, now(), online);
            Party party = BOOK.of(player);
            if (party == null) return;
            Values values = values(party).putText("player", event.getPlayer().getName()).put("time", time(settings().rejoinGrace()));
            sendAll(party, text().quit(), values);
            fire(w -> w.quit(party, player));
            if (newLeader != null) {
                sendAll(party, text().leader(), values(party).putText("player", name(newLeader)));
                fire(w -> w.leaderChanged(party, player));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            Party party = BOOK.rejoin(player.getUniqueId());
            if (party == null) return;
            sendAll(party, text().rejoined(), values(party).putText("player", player.getName()));
            fire(w -> w.rejoined(party, player.getUniqueId()));
        }
    }
}
