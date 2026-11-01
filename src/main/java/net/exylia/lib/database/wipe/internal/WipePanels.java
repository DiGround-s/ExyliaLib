package net.exylia.lib.database.wipe.internal;

import net.exylia.lib.database.DataKind;
import net.exylia.lib.text.Phrases;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The chat panels of a wipe: what it would do, and what it did.
 *
 * <p>Pure functions of a request and its counts, so every line is testable
 * without a database or a server.
 */
public final class WipePanels {

    private WipePanels() {
    }

    /** A kind as the screens and panels name it. */
    public static @NotNull String kind(@NotNull DataKind kind) {
        return switch (kind) {
            case UNSPECIFIED -> Phrases.tr("Unclassified");
            case PROGRESS -> Phrases.tr("Progress");
            case WORLD -> Phrases.tr("World");
            case ACCOUNT -> Phrases.tr("Account");
            case HISTORY -> Phrases.tr("History");
            case CACHE -> Phrases.tr("Cache");
            case SETUP -> Phrases.tr("Setup");
        };
    }

    /** A preset as the screens and panels name it. */
    public static @NotNull String preset(@NotNull WipeRequest.Preset preset) {
        return switch (preset) {
            case SEASON -> Phrases.tr("New season");
            case PLAYERS -> Phrases.tr("Player data only");
            case PLUGIN -> Phrases.tr("One plugin");
            case PLAYER -> Phrases.tr("One player");
            case CUSTOM -> Phrases.tr("Chosen kinds");
        };
    }

    /** A row count, or "counting…" while it is not known. */
    public static @NotNull String rows(long rows) {
        return rows < 0 ? Phrases.tr("{muted}counting…") : "{info}" + rows;
    }

    /** One line per kind with rows to delete: {@code Progress » 120}. */
    static List<String> kindLines(Map<DataKind, Long> rows) {
        List<String> lines = new ArrayList<>(rows.size());
        rows.forEach((kind, count) -> lines.add(Phrases.tr("{letters}{0} {letters_black}» {1} {letters}rows",
                kind(kind), rows(count))));
        return lines;
    }

    /** The scope line: which preset, whose rows, which kinds. */
    static String scope(WipeRequest request) {
        List<String> kinds = request.kinds().stream().map(WipePanels::kind).toList();
        return Phrases.tr("{letters_black}▎ {secondary}Scope {letters_black}» {letters}{0}", preset(request.preset()))
                + (request.player() == null ? "" : " {letters_black}» {highlight}" + request.playerName())
                + "\n" + Phrases.tr("{letters_black}▎ {secondary}Kinds {letters_black}» {letters}{0}",
                request.table() != null ? request.table() : kinds.isEmpty() ? "-" : String.join(", ", kinds));
    }

    /**
     * What the first run of the command prints: what would go, and the line
     * that confirms it.
     */
    public static @NotNull String preview(@NotNull WipeRequest request, @NotNull String confirm, long seconds) {
        StringBuilder text = new StringBuilder(header(request))
                .append("\n").append(Phrases.tr("{letters_black}▎ {error}This deletes rows. There is no undo."))
                .append("\n").append(scope(request));
        Map<String, List<WipeTable>> plugins = request.byPlugin();
        boolean any = false;
        for (Map.Entry<String, List<WipeTable>> entry : plugins.entrySet()) {
            Map<DataKind, Long> rows = request.rowsByKind(entry.getValue());
            long kept = entry.getValue().stream().filter(table -> !request.includes(table)
                    && table.kind() == DataKind.UNSPECIFIED).count();
            if (rows.isEmpty() && kept == 0) continue;
            any |= !rows.isEmpty();
            text.append("\n{letters_black}▎ {highlight}").append(entry.getKey()).append(" {letters_black}» ")
                    .append(rows.isEmpty() ? Phrases.tr("{muted}nothing") : String.join("{letters_black}, ", kindLines(rows)));
            if (kept > 0) text.append(" ").append(Phrases.tr("{muted}({0} unclassified kept)", kept));
        }
        if (!any) {
            text.append("\n").append(Phrases.tr("{letters_black}▎ {muted}No table of those kinds holds rows to delete."));
        }
        int skipped = skipped(request);
        if (skipped > 0) {
            text.append("\n").append(Phrases.tr("{letters_black}▎ {muted}{0} tables name no player and are left alone.", skipped));
        }
        if (request.vanilla()) {
            text.append("\n").append(Phrases.tr("{letters_black}▎ {secondary}Vanilla {letters_black}» {letters}player files of the main world too"));
        }
        text.append("\n").append(Phrases.tr("{letters_black}▎ {secondary}Backup {letters_black}» {letters}every affected plugin is exported first; a failed export cancels everything"))
                .append("\n").append(request.whole()
                        ? Phrases.tr("{letters_black}▎ {warning}Players are kicked and the servers stay locked until they restart.")
                        : Phrases.tr("{letters_black}▎ {warning}The player is kicked and locked out for a few seconds."))
                .append("\n\n").append(Phrases.tr("{warning}➥ Confirm within {0}s:", seconds))
                .append("\n{letters_black}▎ {muted}").append(confirm);
        return text.toString();
    }

    /** What a finished wipe prints, before anybody is kicked. */
    public static @NotNull String result(@NotNull WipeRequest request, @NotNull WipeRuntime.Outcome outcome) {
        StringBuilder text = new StringBuilder(header(request));
        if (outcome.failure() != null) {
            return text.append("\n").append(Phrases.tr("{letters_black}▎ {error}Cancelled {letters_black}» {letters}{0}", outcome.failure()))
                    .append("\n").append(Phrases.tr("{letters_black}▎ {muted}Nothing was deleted.")).toString();
        }
        text.append("\n").append(Phrases.tr("{letters_black}▎ {success}Done {letters_black}» {info}{0} {letters}rows removed",
                outcome.rows()));
        outcome.removed().forEach((plugin, rows) -> text.append("\n{letters_black}▎ {highlight}").append(plugin)
                .append(" {letters_black}» ").append(String.join("{letters_black}, ", kindLines(rows))));
        for (String problem : outcome.problems()) {
            text.append("\n{letters_black}▎ {error}").append(problem);
        }
        if (outcome.skipped() > 0) {
            text.append("\n").append(Phrases.tr("{letters_black}▎ {muted}{0} tables name no player and are left alone.",
                    outcome.skipped()));
        }
        for (Path backup : outcome.backups()) {
            text.append("\n").append(Phrases.tr("{letters_black}▎ {secondary}Backup {letters_black}» {letters}{0}",
                    backup.getFileName()));
        }
        if (!outcome.backups().isEmpty()) {
            text.append("\n").append(Phrases.tr("{letters_black}▎ {muted}Restore one with /exylialib import <plugin> <file> true"));
        }
        if (outcome.restart()) {
            text.append("\n\n").append(Phrases.tr("{warning}➥ Restart every server of the network now."))
                    .append("\n").append(Phrases.tr("{letters_black}▎ {letters}Until then the emptied tables ignore writes and only staff with {0} can join.",
                            WipeRuntime.PERMISSION));
        }
        return text.toString();
    }

    private static String header(WipeRequest request) {
        return request.whole()
                ? Phrases.tr("{primary}&lWIPE&r {muted}{0}", preset(request.preset()))
                : Phrases.tr("{primary}&lRESET&r {muted}{0}", request.playerName());
    }

    /** Tables a player reset leaves alone because they name no owner. */
    static int skipped(WipeRequest request) {
        List<WipeTable> tables = request.tables();
        if (request.whole() || tables == null) return 0;
        @Nullable String only = request.only();
        return (int) tables.stream().filter(table -> table.owner() == null
                && (only == null || table.plugin().equalsIgnoreCase(only))).count();
    }
}
