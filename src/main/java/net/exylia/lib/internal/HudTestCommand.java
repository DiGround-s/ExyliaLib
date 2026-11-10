package net.exylia.lib.internal;

import net.exylia.lib.hud.HudAnchor;
import net.exylia.lib.hud.HudNotice;
import net.exylia.lib.hud.HudWidget;
import net.exylia.lib.hud.Huds;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/** {@code /exylialib hudtest}: a sample HUD on every anchor, for checking it in a real client. */
@Command("exylialib hudtest")
@CommandPermission("exylialib.admin")
public final class HudTestCommand {

    private static Huds huds() {
        return Huds.of(JavaPlugin.getProvidingPlugin(HudTestCommand.class));
    }

    private static Component line(String text) {
        return Text.of(text).build();
    }

    /** Widgets on every anchor: health and food that change live, a quest tracker, a combat timer. */
    @Subcommand("show")
    public void show(@NotNull Player player) {
        Huds huds = huds();
        huds.show(player, HudWidget.of("health", HudAnchor.TOP_LEFT, p -> line(
                "{error}❤ {letters}Health{letters_black}: {error}" + Math.round(p.getHealth())
                        + "{letters_black}/20\n{warning}◆ {letters}Food{letters_black}: {warning}" + p.getFoodLevel()))
                .every(4));
        huds.show(player, HudWidget.of("coords", HudAnchor.TOP_LEFT, p -> line(
                "{info}✦ {letters}" + p.getLocation().getBlockX() + " {letters_black}/ {letters}"
                        + p.getLocation().getBlockY() + " {letters_black}/ {letters}" + p.getLocation().getBlockZ()))
                .order(1).every(4));
        huds.show(player, HudWidget.of("quest", HudAnchor.LEFT, line(
                "{primary}✎ Quest {letters_black}· {letters}The first ore\n {success}✔ {letters_black}Reach the mine\n"
                        + " {letters}• {letters}Mine 5 iron {info}2/5")));
        huds.show(player, HudWidget.of("zone", HudAnchor.TOP, line("{highlight}⚑ {letters}Spawn {letters_black}· {success}Safe zone")));
        huds.show(player, HudWidget.of("combat", HudAnchor.ABOVE_HOTBAR, p -> p.isSprinting()
                ? line("{warning}⚡ {letters}Sprinting") : null).every(2));
        huds.show(player, HudWidget.of("money", HudAnchor.BOTTOM_RIGHT, line("{success}♦ {letters}1,250 {letters_black}coins")));
        huds.show(player, HudWidget.of("side", HudAnchor.RIGHT, line("{accent}★ {letters}Streak {accent}3")));
    }

    /** A notice at the top right; the same group again counts up. */
    @Subcommand("notice")
    public void notice(@NotNull Player player, @Optional String group) {
        huds().notify(player, HudNotice.of(line("{success}+120 {letters}coins")).group(group));
    }

    /** A big moment over the crosshair. */
    @Subcommand("moment")
    public void moment(@NotNull Player player) {
        huds().notify(player, HudNotice.moment(line("{error}&lDOUBLE KILL")));
    }

    @Subcommand("hide")
    public void hide(@NotNull Player player) {
        huds().clear(player);
    }
}
