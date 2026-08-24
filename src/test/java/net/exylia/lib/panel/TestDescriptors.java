package net.exylia.lib.panel;

import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputResult;
import net.exylia.lib.panel.internal.PanelPrompts;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * A list the library has never heard of, and everything needed to edit it.
 *
 * <p>The point of this file is that it is <em>not</em> one of the shipped
 * descriptors. If paginating, searching, copying, deleting, undoing and saving
 * all work over a record declared here, they work because the engine is generic
 * and not because it recognises a reward or a potion effect. A test written over
 * a built-in descriptor could pass for the wrong reason.
 *
 * <p>Nothing here touches Bukkit beyond accepting a {@code Player} it never
 * uses, so every behaviour it proves is provable without a server.
 */
final class TestDescriptors {

    private TestDescriptors() {
    }

    /**
     * A consumer-owned element with an identity that is not its payload.
     *
     * <p>Two notes may read the same and still be different rows — which is the
     * only shape in which "delete hit the wrong one" is visible at all. A record
     * whose identity <em>is</em> its text would make the Commons bug invisible.
     */
    record Note(String id, String text) {
    }

    /** How the descriptor's {@code edit} answers. */
    enum EditMode {
        /** Answers with the entry's text replaced by the scripted answer. */
        ACCEPT,
        /** Answers as a player who walked away. */
        CANCELLED,
        /** Never answers at all, so a pending edit can be observed. */
        PENDING
    }

    /**
     * A {@link FieldDescriptor} over {@link Note}, with a store it can be asked
     * about afterwards.
     */
    static final class Notes implements FieldDescriptor<Note> {

        private final List<Note> stored = new ArrayList<>();

        /** Every list handed to {@link #save}, in order, so writes can be counted. */
        final List<List<Note>> writes = new ArrayList<>();

        EditMode editMode = EditMode.ACCEPT;
        String editAnswer = "edited";

        private int created;
        private int duplicated;

        Notes(List<Note> initial) {
            stored.addAll(initial);
        }

        /** A store of {@code count} notes named {@code note-1 … note-count}. */
        static Notes of(int count) {
            List<Note> notes = new ArrayList<>(count);
            for (int index = 1; index <= count; index++) {
                notes.add(new Note("id-" + index, "note-" + index));
            }
            return new Notes(notes);
        }

        /** A store holding exactly these notes. */
        static Notes holding(Note... notes) {
            return new Notes(List.of(notes));
        }

        @Override
        public String label(Note entry) {
            return entry.text();
        }

        @Override
        public String icon(Note entry) {
            return "PAPER";
        }

        @Override
        public String identity(Note entry) {
            return entry.id();
        }

        @Override
        public Note create() {
            created++;
            return new Note("created-" + created, "new note " + created);
        }

        @Override
        public Note duplicate(Note entry) {
            duplicated++;
            // A new identity, the same payload. That pairing is what the paste
            // scenario is about: the copy must be a second row, not the same one.
            return new Note("copy-" + duplicated + "-" + entry.id(), entry.text());
        }

        @Override
        public CompletionStage<InputResult<Note>> edit(Player viewer, Note entry) {
            return switch (editMode) {
                case ACCEPT -> CompletableFuture.completedFuture(
                        InputResult.completed(new Note(entry.id(), editAnswer)));
                case CANCELLED -> CompletableFuture.completedFuture(
                        InputResult.ended(InputOutcome.CANCELLED));
                case PENDING -> new CompletableFuture<>();
            };
        }

        @Override
        public List<Note> load() {
            return List.copyOf(stored);
        }

        @Override
        public void save(List<Note> entries) {
            writes.add(List.copyOf(entries));
            stored.clear();
            stored.addAll(entries);
        }

        /** What is persisted right now — what a cancel must leave untouched. */
        List<Note> persisted() {
            return List.copyOf(stored);
        }
    }

    /**
     * Answers every question a list panel asks, without a transport.
     *
     * <p>Records what it was asked as well as what it answered, because two of
     * the contracts are about the <em>question</em>: that a delete is asked
     * dangerously, and that a denied delete changes nothing.
     */
    static final class Prompts implements PanelPrompts.Prompts {

        final List<String> texts = new ArrayList<>();
        final List<String> confirms = new ArrayList<>();
        final List<Boolean> confirmsDangerous = new ArrayList<>();
        final List<List<?>> searches = new ArrayList<>();

        String textAnswer = "";
        InputOutcome textOutcome;
        boolean confirmAnswer = true;
        InputOutcome confirmOutcome;
        Object searchAnswer;

        @Override
        public CompletionStage<InputResult<String>> text(Plugin plugin, Player viewer, String prompt) {
            texts.add(prompt);
            return CompletableFuture.completedFuture(textOutcome != null
                    ? InputResult.ended(textOutcome)
                    : InputResult.completed(textAnswer));
        }

        @Override
        public CompletionStage<InputResult<Boolean>> confirm(Plugin plugin, Player viewer,
                                                             String prompt, boolean dangerous) {
            confirms.add(prompt);
            confirmsDangerous.add(dangerous);
            return CompletableFuture.completedFuture(confirmOutcome != null
                    ? InputResult.ended(confirmOutcome)
                    : InputResult.completed(confirmAnswer));
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> CompletionStage<InputResult<T>> search(Plugin plugin, Player viewer, String prompt,
                                                          List<T> choices, Function<T, String> label) {
            searches.add(List.copyOf(choices));
            return CompletableFuture.completedFuture(InputResult.completed((T) searchAnswer));
        }
    }
}
