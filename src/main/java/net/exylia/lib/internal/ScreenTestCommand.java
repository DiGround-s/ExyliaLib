package net.exylia.lib.internal;

import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.Screens;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.Suggest;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.ArrayList;
import java.util.List;

/** {@code /exylialib screentest}: a demo screen built on {@link Screens}, for checking it in a real client. */
@Command("exylialib screentest")
@CommandPermission("exylialib.admin")
public final class ScreenTestCommand {

    private static final String[] TABS = {"Queue", "Duels", "Party"};
    private static final int KITS = 20;
    private static final int ROWS = 5;
    private static final String PANEL = "#17131f";
    private static final String BAR = "#211a2e";
    private static final String BUTTON = "#2a2240";
    private static final String ROW = "#1f1a2b";
    private static final String ROW_HOVER = "#3a2f55";

    private static Screens screens() {
        return Screens.of(JavaPlugin.getProvidingPlugin(ScreenTestCommand.class));
    }

    /**
     * Opens the demo.
     *
     * @param mode {@code spectator} (default: no hand, no crosshair, no wheel) or {@code wheel}
     */
    @Subcommand("open")
    public void open(@NotNull Player player, @Optional @Suggest({"spectator", "wheel"}) String mode) {
        screens().open(player, demo().wheel("wheel".equalsIgnoreCase(mode)));
    }

    /** Opens the same kind of screen written as markup ({@code screens/demo.html} in the library jar). */
    @Subcommand("markup")
    public void markup(@NotNull Player player, @Optional @Suggest({"spectator", "wheel"}) String mode) {
        screens().open(player, markupDemo().screen().wheel("wheel".equalsIgnoreCase(mode)));
    }

    private static final String[][] KIT_LIST = {
            {"nodebuff", "NoDebuff", "pot", "splash_potion"}, {"debuff", "Debuff", "pot", "potion"},
            {"sumo", "Sumo", "sword", "lead"}, {"boxing", "Boxing", "sword", "diamond_sword"},
            {"gapple", "Gapple", "sword", "golden_apple"}, {"archer", "Archer", "sword", "bow"},
            {"builduhc", "BuildUHC", "sword", "lava_bucket"}, {"combo", "Combo", "pot", "pufferfish"},
            {"soup", "Soup", "sword", "mushroom_stew"}, {"axe", "Axe", "sword", "diamond_axe"}};

    static net.exylia.lib.screen.ScreenMarkup markupDemo() {
        try (java.io.InputStream html = ScreenTestCommand.class.getResourceAsStream("/screens/demo.html");
             java.io.InputStream css = ScreenTestCommand.class.getResourceAsStream("/screens/demo.css")) {
            net.exylia.lib.screen.ScreenMarkup markup = net.exylia.lib.screen.ScreenMarkup.parse("demo.html",
                    new String(java.util.Objects.requireNonNull(html).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    new String(java.util.Objects.requireNonNull(css).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return markup
                    .source("kits", session -> {
                        String category = session.state("category", "all");
                        String picked = session.state("picked", "");
                        List<java.util.Map<String, Object>> rows = new ArrayList<>();
                        for (String[] kit : KIT_LIST) {
                            if (!category.equals("all") && !category.equals(kit[2])) continue;
                            rows.add(java.util.Map.of("kit_id", kit[0], "kit_name", kit[1], "kit_icon", kit[3],
                                    "kit_playing", Math.abs((kit[0].hashCode() + System.currentTimeMillis() / 5000) % 40),
                                    "kit_picked", kit[0].equals(picked)));
                        }
                        return rows;
                    })
                    .value("speed", session -> String.format("%.1f", Screens.sensitivity(session.viewer())))
                    .action("pick", (event, args) -> event.session().set("picked", args.get(0)))
                    .action("speed", (event, args) -> Screens.sensitivity(event.viewer(),
                            Screens.sensitivity(event.viewer()) + Double.parseDouble(args.get(0))));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Closes it, for when Shift does not. */
    @Subcommand("close")
    public void close(@NotNull Player player) {
        screens().close(player);
    }

    static Screen demo() {
        return Screen.of(session -> {
            String tab = session.state("tab", TABS[0]);
            int top = session.state("top", 0);
            int picked = session.state("picked", -1);

            Node tabs = Node.row().gap(4);
            for (String name : TABS) {
                boolean on = name.equals(tab);
                tabs.add(Node.text((on ? "{letters}" : "{letters_black}") + name).padding(5, 12)
                        .background(on ? "{primary}" : BUTTON).hover(on ? "{primary}" : ROW_HOVER).id("tab-" + name)
                        .onClick(event -> event.session().set("tab", name)));
            }
            Node bar = Node.row(Node.text("{primary}&lPRACTICE").padding(0, 4), Node.box().grow(1), tabs)
                    .align(Node.Align.CENTER).padding(6, 8).background(BAR);

            List<Node> rows = new ArrayList<>();
            for (int i = top; i < Math.min(KITS, top + ROWS); i++) {
                int kit = i;
                rows.add(Node.text("{letters}Kit " + (kit + 1) + (kit == picked ? " {success}✔" : ""))
                        .padding(5, 8).background(kit == picked ? "#2e5c47" : ROW).hover(ROW_HOVER).id("kit-" + kit)
                        .onClick(event -> event.session().set("picked", kit)));
            }
            Node list = Node.column().gap(3).add(rows).grow(1)
                    .onScroll(event -> event.session().set("top", page(top + event.steps())));
            Node arrows = Node.row(
                    button("▲", event -> event.session().set("top", page(top - ROWS))),
                    Node.text("{letters_black}" + (top + 1) + "-" + Math.min(KITS, top + ROWS) + " of " + KITS)
                            .padding(5, 6),
                    button("▼", event -> event.session().set("top", page(top + ROWS))))
                    .gap(4).justify(Node.Justify.CENTER);

            double sensitivity = Screens.sensitivity(session.viewer());
            Node side = Node.column(
                    Node.text("{letters}" + tab).scale(1.3),
                    Node.text("{letters_black}Sensitivity " + String.format("%.1f", sensitivity)),
                    Node.row(
                            button("−", event -> Screens.sensitivity(event.viewer(), sensitivity - 0.1)),
                            button("+", event -> Screens.sensitivity(event.viewer(), sensitivity + 0.1))).gap(4),
                    Node.box().grow(1),
                    Node.text("{letters}Close").padding(5, 10).background(BUTTON).hover("{error}")
                            .onClick(event -> event.session().close()))
                    .gap(6).width(110).align(Node.Align.START);

            Node body = Node.row(Node.column(list, arrows).gap(4).grow(1), side).gap(10).padding(10).grow(1);
            Node panel = Node.column(bar, body).width(300).height(180).background(PANEL);
            return Node.column(panel, Node.text("{letters_black}Shift closes")).gap(6)
                    .align(Node.Align.CENTER).justify(Node.Justify.CENTER);
        });
    }

    private static Node button(String label, java.util.function.Consumer<net.exylia.lib.screen.ScreenEvent> onClick) {
        return Node.text("{letters}" + label).padding(5, 8).background(BUTTON).hover("{primary}").onClick(onClick);
    }

    private static int page(int top) {
        return Math.max(0, Math.min(KITS - ROWS, top));
    }
}
