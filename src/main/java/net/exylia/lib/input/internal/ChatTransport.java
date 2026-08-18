package net.exylia.lib.input.internal;

import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormInput;
import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputParser;
import net.exylia.lib.input.InputRequest;
import net.exylia.lib.input.Validation;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Chat fallback for every request shape, including sequential forms.
 *
 * <p>The Paper chat event is asynchronous. The listener cancels it immediately,
 * while this transport copies only its plain message and schedules parsing,
 * feedback, and completion onto the player's owning thread. That separation
 * prevents both public-chat leakage and unsafe Bukkit access from the chat
 * thread; it also means parser callbacks supplied by consumers never run on an
 * unexpected asynchronous thread.
 *
 * <p>Form progress is keyed by session identity rather than player identity. A
 * replacement can briefly overlap cleanup of the old request, and sharing state
 * by player would let that cleanup erase the new form's answers.
 *
 * @since 1.31.0
 */
public final class ChatTransport implements Transport {

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();
    private static final String BACK_WORD = "back";
    private static volatile String cancelWord = "cancel";

    private final Plugin plugin;
    private final ConcurrentMap<UUID, FormProgress> forms = new ConcurrentHashMap<>();

    /** Creates the transport owned by ExyliaLib's scheduler. */
    public ChatTransport(@NotNull Plugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Changes the word intercepted as cancellation.
     *
     * <p>The value is volatile because configuration reload may publish it while
     * an async chat event is being intercepted. Rejecting blank values avoids a
     * configuration mistake making every empty-looking answer cancel.
     *
     * @param word new cancellation word
     */
    @ApiStatus.Internal
    public static void setCancelWord(@NotNull String word) {
        java.util.Objects.requireNonNull(word, "word");
        String normalized = word.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("cancel word must not be blank");
        }
        cancelWord = normalized;
    }

    /** Returns the currently configured cancellation word. */
    @ApiStatus.Internal
    public static @NotNull String cancelWord() {
        return cancelWord;
    }

    @Override
    public boolean show(@NotNull InputSession session) {
        Player player = Bukkit.getPlayer(session.playerId());
        if (player == null || !player.isOnline()) {
            return false;
        }

        Object request = session.request();
        if (request instanceof InputRequest<?, ?> single) {
            sendPrompt(player, single.prompt());
        } else if (request instanceof FormInput form) {
            FormProgress progress = new FormProgress(form);
            forms.put(session.id(), progress);
            Text.of("{primary}%prompt%").with("%prompt%", form.prompt()).send(player);
            sendFormField(player, progress);
        } else {
            return false;
        }
        sendHint(player, request instanceof FormInput);
        return true;
    }

    /**
     * Accepts a cancelled Paper chat event after the listener has established
     * that this transport owns the active session.
     *
     * <p>Only component serialization runs on the async event thread. Everything
     * involving consumer parsers, player messages, form state, or terminal
     * delivery is moved through {@link Tasks} to the entity-owning thread.
     */
    void accept(@NotNull InputSession session,
                @NotNull io.papermc.paper.event.player.AsyncChatEvent event) {
        String raw = PLAIN.serialize(event.message());
        Player player = event.getPlayer();
        Tasks.of(plugin).runAtEntity(player,
                () -> answer(session, player, raw),
                () -> session.end(InputOutcome.DISCONNECTED));
    }

    @Override
    public void close(@NotNull InputSession session) {
        forms.remove(session.id());
    }

    @Override
    public @NotNull TransportKind kind() {
        return TransportKind.CHAT;
    }

    private void answer(InputSession session, Player player, String raw) {
        if (InputRuntime.active(player.getUniqueId()) != session
                || session.transportKind() != TransportKind.CHAT) {
            return;
        }
        if (raw.trim().equalsIgnoreCase(cancelWord)) {
            session.end(InputOutcome.CANCELLED);
            return;
        }

        Object request = session.request();
        if (request instanceof FormInput form) {
            answerForm(session, player, form, raw);
            return;
        }
        if (request instanceof InputRequest<?, ?> single) {
            answerSingle(session, player, single, raw);
        }
    }

    private static void answerSingle(InputSession session, Player player,
                                     InputRequest<?, ?> request, String raw) {
        InputParser.Parsed<?> parsed = request.parseRaw(raw);
        if (parsed.ok()) {
            session.complete(parsed.value());
            return;
        }
        sendError(player, parsed.error());
        sendPrompt(player, request.prompt());
    }

    private void answerForm(InputSession session, Player player, FormInput form, String raw) {
        FormProgress progress = forms.get(session.id());
        if (progress == null) {
            return;
        }
        if (raw.trim().equalsIgnoreCase(BACK_WORD)) {
            if (progress.back()) {
                sendFormField(player, progress);
            } else {
                Text.of("{warning}You are already at the first field.").send(player);
            }
            return;
        }

        progress.answer(raw);
        Object parsed = form.parseRaw(progress.answers());
        if (!progress.atEnd()) {
            if (parsed instanceof Validation validation) {
                String fieldError = validation.fieldErrors()
                        .get(progress.current().key().name());
                if (fieldError != null) {
                    sendError(player, fieldError);
                    progress.clearCurrent();
                    sendFormField(player, progress);
                    return;
                }
            }
            progress.advance();
            sendFormField(player, progress);
            return;
        }

        if (!(parsed instanceof Validation validation)) {
            session.complete(parsed);
            return;
        }

        for (String message : validation.messages()) {
            sendError(player, message);
        }
        progress.returnTo(firstOffendingField(form.fields(), validation));
        sendFormField(player, progress);
    }

    private static int firstOffendingField(List<FormField<?>> fields, Validation validation) {
        for (int index = 0; index < fields.size(); index++) {
            if (validation.fieldErrors().containsKey(fields.get(index).key().name())) {
                return index;
            }
        }
        return 0;
    }

    private static void sendPrompt(Player player, String prompt) {
        Text.of("{primary}%prompt%").with("%prompt%", prompt).send(player);
    }

    private static void sendHint(Player player, boolean form) {
        if (form) {
            Text.of("{muted}Type {highlight}%cancel%{muted} to cancel, or "
                            + "{highlight}%back%{muted} to revisit the previous field.")
                    .with("%cancel%", cancelWord)
                    .with("%back%", BACK_WORD)
                    .send(player);
            return;
        }
        Text.of("{muted}Type {highlight}%cancel%{muted} to cancel.")
                .with("%cancel%", cancelWord)
                .send(player);
    }

    private static void sendFormField(Player player, FormProgress progress) {
        FormField<?> field = progress.current();
        Text.of("{primary}%label% {muted}(%position%/%total%)")
                .with("%label%", field.label())
                .with("%position%", progress.index() + 1)
                .with("%total%", progress.fields().size())
                .send(player);
    }

    private static void sendError(Player player, String error) {
        Text.of("{error}%error%")
                .with("%error%", error == null ? "That value is not accepted." : error)
                .send(player);
    }

    /** Mutable only on the player's owning thread after creation in show(). */
    private static final class FormProgress {
        private final List<FormField<?>> fields;
        private final Map<String, String> answers = new LinkedHashMap<>();
        private int index;

        private FormProgress(FormInput form) {
            this.fields = form.fields();
        }

        private List<FormField<?>> fields() {
            return fields;
        }

        private FormField<?> current() {
            return fields.get(index);
        }

        private int index() {
            return index;
        }

        private void answer(String raw) {
            answers.put(current().key().name(), raw);
        }

        private boolean atEnd() {
            return index == fields.size() - 1;
        }

        private void advance() {
            index++;
        }

        private void clearCurrent() {
            answers.remove(current().key().name());
        }

        private boolean back() {
            if (index == 0) {
                return false;
            }
            index--;
            answers.remove(current().key().name());
            return true;
        }

        private void returnTo(int fieldIndex) {
            index = fieldIndex;
            answers.remove(current().key().name());
        }

        private Map<String, String> answers() {
            return Map.copyOf(answers);
        }
    }
}
