package net.exylia.lib.dialogue.internal;

import net.exylia.lib.dialogue.Dialogue;
import net.exylia.lib.guide.Guides;
import net.exylia.lib.hud.HudAnchor;
import net.exylia.lib.hud.HudHandle;
import net.exylia.lib.hud.HudWidget;
import net.exylia.lib.hud.Huds;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.npc.PlacedNpcClickEvent;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.screen.Screens;
import net.exylia.lib.screen.internal.Pictures;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.text.Typewriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/** The conversations players are in, and the presses that move them on. */
public final class DialogueRuntime implements Listener {

    /** Presses closer than this are one press (a sneak and the swing that comes with a click). */
    private static final long PRESS_GAP = 200;
    /** A swing at a block this close is mining, not a press. */
    private static final int REACH = 5;
    private static final String WIDGET = "dialogue";
    /** Ticks a screen waits before it opens, so a teleport that opened it has finished moving the player. */
    private static final long SETTLE = 2;
    private static final String BOX = "#120e1ac0";
    private static final String EDGE = "{primary}50";

    private static final Map<UUID, Talk> TALKS = new ConcurrentHashMap<>();
    private static @Nullable Plugin library;

    private DialogueRuntime() {
    }

    /** Wires the module. Called by ExyliaLib at startup. */
    public static void init(Plugin plugin) {
        library = plugin;
        plugin.getServer().getPluginManager().registerEvents(new DialogueRuntime(), plugin);
    }

    static final class Talk {
        final Plugin plugin;
        final Player player;
        final Dialogue dialogue;
        final CompletableFuture<Dialogue.Result> future = new CompletableFuture<>();
        int line = -1;
        Typewriter writer;
        long lineStarted;
        boolean whole;
        int selected;
        long lastPress;
        @Nullable HudHandle hud;
        @Nullable ScreenSession screen;
        boolean over;

        Talk(Plugin plugin, Player player, Dialogue dialogue) {
            this.plugin = plugin;
            this.player = player;
            this.dialogue = dialogue;
        }

        boolean written() {
            if (whole || dialogue.speed() <= 0) return true;
            return (System.currentTimeMillis() - lineStarted) * dialogue.speed() / 1000 >= writer.length();
        }

        Component shownText() {
            if (written()) return writer.whole();
            return writer.frame((int) ((System.currentTimeMillis() - lineStarted) * dialogue.speed() / 1000));
        }

        boolean last() {
            return line == dialogue.lines().size() - 1;
        }

        boolean asking() {
            return last() && !dialogue.answers().isEmpty() && written();
        }
    }

    public static CompletableFuture<Dialogue.Result> open(Plugin plugin, Player player, Dialogue dialogue) {
        Talk talk = new Talk(plugin, player, dialogue);
        if (Bukkit.isOwnedByCurrentRegion(player)) start(talk);
        else Tasks.of(plugin).runAtEntity(player, () -> start(talk), () -> talk.future.complete(Dialogue.Result.CANCELLED));
        return talk.future;
    }

    public static void close(Player player) {
        Talk talk = TALKS.get(player.getUniqueId());
        if (talk != null) end(talk, Dialogue.Result.CANCELLED);
    }

    public static boolean talking(Player player) {
        return TALKS.containsKey(player.getUniqueId());
    }

    private static void start(Talk talk) {
        Player player = talk.player;
        if (!player.isOnline()) {
            talk.future.complete(Dialogue.Result.CANCELLED);
            return;
        }
        close(player);
        TALKS.put(player.getUniqueId(), talk);
        // Nowhere to draw a box (Bedrock, no PacketEvents, the player hid their HUD): the lines go to chat.
        boolean screen = talk.dialogue.mode() == Dialogue.Mode.SCREEN;
        if (Guides.isBedrock(player) || !Guides.isSupported() || (!screen && Huds.hidden(player))) {
            inChat(talk);
            return;
        }
        // The press that opened it (a click on an NPC) must not also skip the first line.
        talk.lastPress = System.currentTimeMillis();
        next(talk);
        if (talk.over) return;
        if (talk.dialogue.mode() == Dialogue.Mode.SCREEN) {
            // Two ticks later: opened from a teleport (a warp into a region), the move would close the screen at once.
            Tasks.of(talk.plugin).runAtEntityLater(player, SETTLE, () -> {
                if (talk.over || !player.isOnline()) return;
                talk.screen = Screens.of(talk.plugin).open(player, Screen.of(session -> layout(talk, true))
                        .name("dialogue")
                        .backdrop("#00000000")
                        .onClose(session -> end(talk, Dialogue.Result.CANCELLED)));
                if (talk.screen == null) showHud(talk);
            }, () -> end(talk, Dialogue.Result.CANCELLED));
            return;
        }
        showHud(talk);
    }

    private static void showHud(Talk talk) {
        talk.hud = Huds.of(talk.plugin).show(talk.player,
                HudWidget.layout(WIDGET, HudAnchor.ABOVE_HOTBAR, p -> talk.over ? null : layout(talk, false)).every(2));
    }

    /** A Bedrock client draws display text poorly: every line in chat, then the answers in a form. */
    private static void inChat(Talk talk) {
        Player player = talk.player;
        for (int index = 0; index < talk.dialogue.lines().size(); index++) {
            Dialogue.Line line = talk.dialogue.lines().get(index);
            player.sendMessage(line.speaker() == null ? line.text()
                    : line.speaker().append(Text.of(" {letters_black}» ").build()).append(line.text()));
            talk.line = index;
            lineStarted(talk);
            if (talk.over) return;
        }
        List<Component> answers = talk.dialogue.answers();
        if (answers.isEmpty()) {
            end(talk, new Dialogue.Result(true, -1));
            return;
        }
        List<Integer> indexes = IntStream.range(0, answers.size()).boxed().toList();
        Inputs.of(talk.plugin).choice(player, Phrases.tr("{letters}Choose an answer"), indexes)
                .label(index -> MiniMessage.miniMessage().serialize(answers.get(index)))
                .key(String::valueOf)
                .open().thenAccept(result -> Tasks.of(talk.plugin).runAtEntity(player, () -> {
                    int[] chosen = {-1};
                    result.ifCompleted(index -> chosen[0] = index);
                    end(talk, chosen[0] < 0 ? Dialogue.Result.CANCELLED : new Dialogue.Result(true, chosen[0]));
                }, () -> end(talk, Dialogue.Result.CANCELLED)));
    }

    private static void next(Talk talk) {
        talk.line++;
        talk.writer = Typewriter.of(talk.dialogue.lines().get(talk.line).text(), talk.dialogue.width());
        talk.lineStarted = System.currentTimeMillis();
        talk.whole = false;
        talk.selected = 0;
        lineStarted(talk);
        redraw(talk);
    }

    private static void lineStarted(Talk talk) {
        if (talk.dialogue.onLine() == null) return;
        try {
            talk.dialogue.onLine().accept(talk.line);
        } catch (RuntimeException failure) {
            talk.plugin.getLogger().warning("A dialogue line's handler failed: " + failure);
        }
    }

    private static void press(Talk talk) {
        long now = System.currentTimeMillis();
        if (talk.over || now - talk.lastPress < PRESS_GAP) return;
        talk.lastPress = now;
        if (!talk.written()) {
            talk.whole = true;
            redraw(talk);
        } else if (talk.asking()) {
            end(talk, new Dialogue.Result(true, talk.selected));
        } else if (talk.last()) {
            end(talk, new Dialogue.Result(true, -1));
        } else {
            next(talk);
        }
    }

    private static void choose(Talk talk, int answer) {
        if (!talk.over && talk.asking()) end(talk, new Dialogue.Result(true, answer));
    }

    private static void redraw(Talk talk) {
        if (talk.hud != null) talk.hud.refresh();
        if (talk.screen != null) talk.screen.redraw();
    }

    private static void end(Talk talk, Dialogue.Result result) {
        if (talk.over) return;
        talk.over = true;
        TALKS.remove(talk.player.getUniqueId(), talk);
        if (talk.hud != null) talk.hud.remove();
        if (talk.screen != null && talk.screen.open()) talk.screen.close();
        talk.future.complete(result);
    }

    /** The box: face, name and words, the answers under the last line, and a hint. */
    static Node layout(Talk talk, boolean screen) {
        Dialogue.Line line = talk.dialogue.lines().get(talk.line);
        Node words = Node.column().gap(2);
        if (line.speaker() != null) words.add(Node.text(line.speaker()));
        Node text = Node.text(talk.shownText()).wrap(talk.dialogue.width() + 8);
        if (screen) text.refresh(2);
        words.add(text);
        Node top = words;
        if (line.face() != null) {
            Pictures.Picture face = Pictures.face(line.face());
            top = Node.row((face == null ? Pictures.stranger(false) : face).node().size(20, 20), words)
                    .gap(6).align(Node.Align.START);
        }
        Node box = Node.column(top).gap(4).padding(6, 8).background(BOX).outline(EDGE, 1).radius(2)
                .width(talk.dialogue.width() + (line.face() == null ? 16 : 42));
        boolean asking = talk.asking();
        if (asking) {
            List<Node> answers = new ArrayList<>();
            for (int index = 0; index < talk.dialogue.answers().size(); index++) {
                Component answer = talk.dialogue.answers().get(index);
                boolean chosen = !screen && index == talk.selected;
                Node row = Node.text(Text.of(chosen ? "{highlight}▸ " : "{letters_black}  ").build().append(answer))
                        .padding(1, 4).key("answer" + index);
                if (chosen) row.background("{highlight}20");
                if (screen) {
                    int picked = index;
                    row.hover("{primary}40").onClick(event -> choose(talk, picked));
                }
                answers.add(row);
            }
            box.add(Node.column().gap(1).add(answers));
        }
        Dialogue.Hints hints = talk.dialogue.hints();
        Component hint = !asking ? (hints != null ? hints.reading() : phrase("{muted}Sneak or click to continue"))
                : screen ? (hints != null ? hints.clicking() : phrase("{muted}Click an answer"))
                : (hints != null ? hints.choosing() : phrase("{muted}Scroll to choose · Sneak to answer"));
        box.add(Node.text(hint).scale(0.75));
        if (screen) {
            if (!asking) box.onClick(event -> press(talk));
            return Node.column(box).justify(Node.Justify.END).align(Node.Align.CENTER).padding(0, 0, 16, 0);
        }
        return box;
    }

    private static Component phrase(String english) {
        return Text.of(Phrases.tr(english)).build();
    }

    private static @Nullable Talk onHud(Player player) {
        Talk talk = TALKS.get(player.getUniqueId());
        return talk == null || talk.hud == null ? null : talk;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent event) {
        Talk talk = event.isSneaking() ? onHud(event.getPlayer()) : null;
        if (talk != null) press(talk);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        Talk talk = onHud(event.getPlayer());
        if (talk != null && event.getPlayer().getTargetBlockExact(REACH) == null) press(talk);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNpc(PlacedNpcClickEvent event) {
        Talk talk = onHud(event.getPlayer());
        if (talk != null) Tasks.of(talk.plugin).runAtEntity(event.getPlayer(), () -> press(talk));
    }

    /** Under the last line the wheel and the number keys move between the answers; the held item stays put. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onScroll(PlayerItemHeldEvent event) {
        Talk talk = onHud(event.getPlayer());
        if (talk == null || !talk.asking()) return;
        event.setCancelled(true);
        talk.selected = scrolled(talk.selected, talk.dialogue.answers().size(), event.getPreviousSlot(), event.getNewSlot());
        talk.hud.refresh();
    }

    /** One wheel notch (a slot either side, wrapping) moves one answer; a number key picks its answer directly. */
    static int scrolled(int selected, int count, int from, int to) {
        int step = (to - from + 9) % 9;
        if (step == 1) return (selected + 1) % count;
        if (step == 8) return (selected + count - 1) % count;
        return to < count ? to : selected;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        close(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        close(event.getPlayer());
    }
}
