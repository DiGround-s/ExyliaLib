package net.exylia.lib.spatial;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.interaction.Interaction;
import net.exylia.lib.interaction.Interactions;
import net.exylia.lib.item.Items;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.screen.ScreenMarkup;
import net.exylia.lib.stage.StageLook;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What {@link SpatialMenus#registerFile} fills a menu with: its file's lists and buttons, read live on every redraw. */
final class FileMenus {

    private FileMenus() {
    }

    static void fill(SpatialMenus menus, SpatialMenu menu, ConfigFile<SpatialLayout> file) {
        Map<List<String>, Interaction> compiled = new ConcurrentHashMap<>();
        String where = "spatial/" + menu.id() + ".yml";
        SpatialLayout.FileMenu written = file.get().menu();
        for (String list : written.lists().keySet()) {
            menu.source(list, context -> entries(context, file.get().menu(), list));
            menu.onSelect(list, (context, entry) -> {
                SpatialLayout.FileMenu now = file.get().menu();
                SpatialLayout.EntryList entries = now.lists().get(list);
                SpatialLayout.FileEntry clicked = entries == null ? null : entries.entries().get(entry.id());
                if (clicked == null) return;
                Player viewer = context.viewer();
                Map<String, Object> data = data(context, now);
                if (!clicked.require().isBlank() && !ScreenMarkup.test(clicked.require(), viewer, text -> fill(text, viewer, data))) {
                    StageLook.play(viewer, file.get().theme().errorSound());
                    run(menus, compiled, clicked.deny(), where, viewer, data);
                    return;
                }
                run(menus, compiled, clicked.click(), where, viewer, data);
            });
        }
        for (String button : written.buttons().keySet()) {
            menu.action(button, context -> {
                SpatialLayout.FileMenu now = file.get().menu();
                run(menus, compiled, now.buttons().getOrDefault(button, List.of()), where, context.viewer(), data(context, now));
            });
        }
    }

    /** ponytail: every click reads as a left click, the one click a spatial entry reports; a file's {@code right:} never runs. */
    private static void run(SpatialMenus menus, Map<List<String>, Interaction> compiled, List<String> lines, String where,
                            Player viewer, Map<String, Object> data) {
        if (lines.isEmpty()) return;
        compiled.computeIfAbsent(lines, written -> Interactions.compile(menus.plugin(), written, where))
                .run(viewer, Click.LEFT, data)
                .thenRun(() -> menus.refresh(viewer));
    }

    private static List<SpatialEntry> entries(SpatialContext context, SpatialLayout.FileMenu menu, String list) {
        SpatialLayout.EntryList entries = menu.lists().get(list);
        if (entries == null) return List.of();
        Player viewer = context.viewer();
        Map<String, Object> data = data(context, menu);
        List<Map.Entry<String, SpatialLayout.FileEntry>> sorted = new ArrayList<>(entries.entries().entrySet());
        sorted.sort(Comparator.comparingInt(entry -> entry.getValue().priority()));
        List<SpatialEntry> out = new ArrayList<>(sorted.size());
        for (Map.Entry<String, SpatialLayout.FileEntry> entry : sorted) {
            SpatialLayout.FileEntry written = entry.getValue();
            if (!written.when().isBlank() && !ScreenMarkup.test(written.when(), viewer, text -> fill(text, viewer, data))) continue;
            out.add(SpatialEntry.of(entry.getKey(), Items.icon(fill(written.material(), viewer, data)), fill(written.name(), viewer, data))
                    .withSubtitle(fill(written.subtitle(), viewer, data)));
        }
        return out;
    }

    /** The values of the menu's {@code args}, which opening it put in its state. */
    private static Map<String, Object> data(SpatialContext context, SpatialLayout.FileMenu menu) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String arg : menu.args()) {
            Object value = context.get(arg);
            if (value != null) data.put(arg, value);
        }
        return data;
    }

    private static String fill(String text, Player viewer, Map<String, Object> data) {
        String out = text;
        for (Map.Entry<String, Object> value : data.entrySet()) {
            out = out.replace("%" + value.getKey() + "%", String.valueOf(value.getValue()));
        }
        return Placeholders.apply(out, viewer);
    }
}
