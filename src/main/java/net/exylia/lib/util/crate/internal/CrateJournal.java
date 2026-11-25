package net.exylia.lib.util.crate.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The operations a crate row remembers, written in the same write as what they did.
 *
 * <p>An operation (an opening, a grant carrying an id, a move of keys between a
 * player's two rows) is applied by a compare-and-set on its row that also adds
 * its entry here. So a retry, a replay or a second server asking again finds the
 * entry and does nothing: what an operation spends, unlocks and refunds lands
 * once, together, or not at all.
 *
 * <p>Bounded: past {@link #MAX} entries the oldest settled ones are dropped. An
 * entry that still owes something ({@link State#TOKENS}, {@link State#MOVING})
 * is never dropped.
 *
 * @param entries oldest first
 */
public record CrateJournal(@NotNull List<Entry> entries) {

    /** How many entries a row keeps; a replay older than that many operations would apply again. */
    public static final int MAX = 64;

    /** How long the stored journal may grow before settled entries are dropped early. */
    private static final int MAX_CHARS = 16_000;

    private static final CrateJournal EMPTY = new CrateJournal(List.of());

    private static int length(CrateJournal journal) {
        String stored = journal.format();
        return stored == null ? 0 : stored.length();
    }

    /** Where an operation stands. */
    public enum State {
        /** Applied, nothing left to do. */
        DONE('D'),
        /** Applied, with token items still to hand over. */
        TOKENS('T'),
        /** Given up on before it was applied: it never will be. */
        ABORTED('X'),
        /** Keys taken off the global row on their way to a scope's row, not credited there yet. */
        MOVING('M');

        final char code;

        State(char code) {
            this.code = code;
        }

        static State of(char code) {
            for (State state : values()) {
                if (state.code == code) return state;
            }
            throw new IllegalArgumentException("Unknown crate journal state " + code);
        }

        public boolean settled() {
            return this == DONE || this == ABORTED;
        }
    }

    /**
     * What one reel of an opening came to.
     *
     * @param won       new to the player rather than a duplicate
     * @param prizeId   the reward's id, as stored
     * @param refunded  keys handed back for a duplicate
     * @param tokenOwed whether its token item is still to be handed over
     */
    public record Reel(boolean won, @NotNull String prizeId, int refunded, boolean tokenOwed) {
    }

    /**
     * One operation.
     *
     * @param id     unique per operation
     * @param state  where it stands
     * @param at     when it was written, epoch milliseconds
     * @param amount keys it moved: spent, granted, or carried to {@code scope}
     * @param scope  the scope a {@link State#MOVING} entry carries keys to; empty otherwise
     * @param reels  an opening's reels, in order; empty for anything else
     */
    public record Entry(@NotNull String id, @NotNull State state, long at, int amount, @NotNull String scope,
                        @NotNull List<Reel> reels) {

        public Entry {
            reels = List.copyOf(reels);
        }

        public @NotNull Entry withState(@NotNull State next) {
            return new Entry(id, next, at, amount, scope, reels);
        }

        /** The same entry with one reel's token handed over, and settled once none is owed. */
        public @NotNull Entry withTokenClaimed(int index) {
            List<Reel> next = new ArrayList<>(reels);
            Reel reel = next.get(index);
            next.set(index, new Reel(reel.won(), reel.prizeId(), reel.refunded(), false));
            boolean owed = next.stream().anyMatch(Reel::tokenOwed);
            return new Entry(id, owed ? State.TOKENS : State.DONE, at, amount, scope, next);
        }
    }

    public static @NotNull CrateJournal empty() {
        return EMPTY;
    }

    public @Nullable Entry find(@NotNull String id) {
        for (Entry entry : entries) {
            if (entry.id().equals(id)) return entry;
        }
        return null;
    }

    /** The journal with {@code entry} in place of the one sharing its id, or added last. */
    public @NotNull CrateJournal with(@NotNull Entry entry) {
        List<Entry> next = new ArrayList<>(entries.size() + 1);
        boolean replaced = false;
        for (Entry existing : entries) {
            if (existing.id().equals(entry.id())) {
                next.add(entry);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) next.add(entry);
        for (int i = 0; next.size() > MAX && i < next.size(); ) {
            if (next.get(i).state().settled()) next.remove(i); else i++;
        }
        // Read and written whole on every change of the row, so it also stays small.
        CrateJournal trimmed = new CrateJournal(List.copyOf(next));
        while (trimmed.entries.size() > 1 && length(trimmed) > MAX_CHARS) {
            int oldest = -1;
            for (int i = 0; i < trimmed.entries.size() - 1 && oldest < 0; i++) {
                if (trimmed.entries.get(i).state().settled()) oldest = i;
            }
            if (oldest < 0) break;
            List<Entry> fewer = new ArrayList<>(trimmed.entries);
            fewer.remove(oldest);
            trimmed = new CrateJournal(List.copyOf(fewer));
        }
        return trimmed;
    }

    /** Every entry still owing something. */
    public @NotNull List<Entry> open() {
        return entries.stream().filter(entry -> !entry.state().settled()).toList();
    }

    // ------------------------------------------------------------------
    // Stored form: entries split by ';', fields by '|', reels by ',', a reel's
    // fields by '~'. Every free-text field is URL-encoded, which leaves none of
    // the separators in it.
    // ------------------------------------------------------------------

    public static @NotNull CrateJournal parse(@Nullable String stored) {
        if (stored == null || stored.isBlank()) return EMPTY;
        List<Entry> entries = new ArrayList<>();
        for (String written : stored.split(";")) {
            if (written.isEmpty()) continue;
            String[] fields = written.split("\\|", -1);
            if (fields.length < 6) continue;
            List<Reel> reels = new ArrayList<>();
            if (!fields[5].isEmpty()) {
                for (String reel : fields[5].split(",")) {
                    String[] parts = reel.split("~", -1);
                    if (parts.length < 4) continue;
                    reels.add(new Reel("W".equals(parts[0]), decode(parts[1]), Integer.parseInt(parts[2]),
                            "1".equals(parts[3])));
                }
            }
            entries.add(new Entry(decode(fields[1]), State.of(fields[0].charAt(0)), Long.parseLong(fields[2]),
                    Integer.parseInt(fields[3]), decode(fields[4]), reels));
        }
        return new CrateJournal(List.copyOf(entries));
    }

    public @Nullable String format() {
        if (entries.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (!out.isEmpty()) out.append(';');
            out.append(entry.state().code).append('|').append(encode(entry.id())).append('|').append(entry.at())
                    .append('|').append(entry.amount()).append('|').append(encode(entry.scope())).append('|');
            for (int i = 0; i < entry.reels().size(); i++) {
                Reel reel = entry.reels().get(i);
                if (i > 0) out.append(',');
                out.append(reel.won() ? 'W' : 'D').append('~').append(encode(reel.prizeId())).append('~')
                        .append(reel.refunded()).append('~').append(reel.tokenOwed() ? '1' : '0');
            }
        }
        return out.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
