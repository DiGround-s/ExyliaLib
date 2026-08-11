package net.exylia.lib.scoreboard.internal;

import net.kyori.adventure.text.Component;
import net.megavex.scoreboardlibrary.api.sidebar.Sidebar;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link SidebarHandle} over the shaded scoreboard-library.
 *
 * <p>This is the only class in the module that references the relocated
 * {@code net.megavex} types. The library is packet-level and fully async, so
 * every call here is safe from whichever thread the refresh driver runs on.
 */
final class MegavexSidebar implements SidebarHandle {

    private final Sidebar sidebar;
    private final Player player;

    MegavexSidebar(Sidebar sidebar, Player player) {
        this.sidebar = sidebar;
        this.player = player;
    }

    @Override
    public void show() {
        sidebar.addPlayer(player);
    }

    @Override
    public void hide() {
        sidebar.removePlayer(player);
    }

    @Override
    public void close() {
        sidebar.close();
    }

    @Override
    public boolean closed() {
        return sidebar.closed();
    }

    @Override
    public void title(Component title) {
        sidebar.title(title);
    }

    @Override
    public void line(int index, @Nullable Component line) {
        sidebar.line(index, line);
    }
}
