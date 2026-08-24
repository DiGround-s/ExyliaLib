package net.exylia.lib.input.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.DialogAction;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.body.DialogBody;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody;
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton;
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData;
import com.github.retrooper.packetevents.protocol.dialog.input.BooleanInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.Input;
import com.github.retrooper.packetevents.protocol.dialog.input.InputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.common.client.WrapperCommonClientCustomClickAction;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientCustomClickAction;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerClearDialog;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerShowDialog;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearDialog;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog;
import net.exylia.lib.input.ChoiceInput;
import net.exylia.lib.input.ConfirmInput;
import net.exylia.lib.input.FlagInput;
import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormInput;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputParser;
import net.exylia.lib.input.InputRequest;
import net.exylia.lib.input.SearchInput;
import net.exylia.lib.input.Validation;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The only class in the input module linked to PacketEvents.
 *
 * <p>Keeping imports, listener descriptors, dialog controls, and wrappers in one
 * guarded class prevents a server without PacketEvents from resolving any of
 * those symbols while {@link DialogTransport} is discovered. The class is first
 * touched only after that transport's dependency probe succeeds.
 *
 * <p>A choice is drawn as one button per option and not as the dialog's own
 * option control, which is a single button whose label changes as it is clicked:
 * answering with that means cycling to the right label and then pressing Submit,
 * and a button that only changes its own text reads as a broken one. Choices are
 * therefore also limited to {@value #SMALL_CHOICE_LIMIT} entries — that many
 * buttons is still a screen somebody can read; beyond it, search or a paged menu
 * is faster and the transport returns {@code false} so the runtime picks one.
 */
final class DialogPackets {

    static final int SMALL_CHOICE_LIMIT = 12;

    private static final String NAMESPACE = "exylialib";
    /** Action id prefix carrying which option was pressed, by position. */
    private static final String CHOOSE = "choose";
    private static final int CONTROL_WIDTH = 260;
    private static final int BODY_WIDTH = 300;
    private static final int TEXT_LIMIT = 32_767;

    private static final ConcurrentMap<String, State> STATES = new ConcurrentHashMap<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static volatile PacketListenerCommon packetListener;
    private static volatile Listener shutdownListener;
    private static volatile Plugin owner;

    private DialogPackets() {
    }

    /** Returns whether PacketEvents is installed, initialized, and safe to call. */
    static boolean available() {
        try {
            Plugin packetEvents = Bukkit.getPluginManager().getPlugin("packetevents");
            if (packetEvents == null) {
                packetEvents = Bukkit.getPluginManager().getPlugin("PacketEvents");
            }
            var api = PacketEvents.getAPI();
            return packetEvents != null && packetEvents.isEnabled()
                    && api != null && api.isLoaded() && api.isInitialized();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** Displays a supported request, returning false before retaining any state on fallback. */
    static boolean show(@NotNull Plugin plugin, @NotNull InputSession session) {
        try {
            Player player = Bukkit.getPlayer(session.playerId());
            if (player == null || !player.isOnline()) {
                return false;
            }
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null || user.getClientVersion() == null
                    || !user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_6)) {
                return false;
            }
            if (!supports(session.request())) {
                return false;
            }
            ensureListener(plugin);
            State state = new State(session, sessionKey(session), initialValues(session.request()), null);
            STATES.put(state.key(), state);
            send(user, dialog(state));
            return true;
        } catch (Throwable failure) {
            STATES.remove(sessionKey(session));
            return false;
        }
    }

    /** Removes retained state and clears the visible dialog without ever escaping a cleanup failure. */
    static void close(@NotNull InputSession session) {
        STATES.remove(sessionKey(session));
        try {
            Player player = Bukkit.getPlayer(session.playerId());
            User user = player == null ? null : PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user != null) {
                PacketWrapper<?> clear = user.getConnectionState() == ConnectionState.CONFIGURATION
                        ? new WrapperConfigServerClearDialog()
                        : new WrapperPlayServerClearDialog();
                user.sendPacket(clear);
            }
        } catch (Throwable ignored) {
            // Cleanup is best effort: disconnect and PacketEvents shutdown may race this call.
        }
    }

    private static boolean supports(Object request) {
        if (request instanceof ChoiceInput<?> choice) {
            return choice.choices().size() <= SMALL_CHOICE_LIMIT;
        }
        if (request instanceof SearchInput<?>) {
            return false;
        }
        return request instanceof InputRequest<?, ?> || request instanceof FormInput;
    }

    private static void ensureListener(Plugin plugin) {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        owner = plugin;
        packetListener = new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                WrapperCommonClientCustomClickAction<?> packet;
                if (event.getPacketType() == PacketType.Play.Client.CUSTOM_CLICK_ACTION) {
                    packet = new WrapperPlayClientCustomClickAction(event);
                } else if (event.getPacketType() == PacketType.Configuration.Client.CUSTOM_CLICK_ACTION) {
                    packet = new WrapperConfigClientCustomClickAction(event);
                } else {
                    return;
                }
                receive(packet, event.getUser().getUUID());
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        shutdownListener = new Listener() {
            @EventHandler
            public void onDisable(PluginDisableEvent event) {
                if (event.getPlugin() == plugin) {
                    unregister();
                }
            }
        };
        Bukkit.getPluginManager().registerEvents(shutdownListener, plugin);
    }

    private static void unregister() {
        PacketListenerCommon listener = packetListener;
        packetListener = null;
        STATES.clear();
        if (listener != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            } catch (Throwable ignored) {
                // PacketEvents can disable before ExyliaLib; there is then no manager left to clean.
            }
        }
        Listener bukkitListener = shutdownListener;
        shutdownListener = null;
        if (bukkitListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(bukkitListener);
        }
        owner = null;
        REGISTERED.set(false);
    }

    private static Dialog dialog(State state) {
        Object request = state.session().request();
        ActionButton cancel = button("Cancel", "cancel/" + state.key());
        List<DialogBody> body = errorBody(state.validation());
        if (request instanceof ChoiceInput<?> choice) {
            return new MultiActionDialog(new CommonDialogData(Text.component(prompt(request)),
                    null, true, false, DialogAction.CLOSE, body, List.of()),
                    choiceButtons(choice, state.key()), cancel, 1);
        }
        List<Input> inputs = request instanceof FormInput form
                ? formInputs(form, state.values(), state.validation())
                : List.of(singleInput((InputRequest<?, ?>) request, state.values().get("value"), state.validation()));
        String submitLabel = request instanceof FormInput form ? form.submitLabel() : "Submit";
        ActionButton submit = button(submitLabel, "submit/" + state.key());
        CommonDialogData common = new CommonDialogData(
                Text.component(prompt(request)), null, true, false, DialogAction.CLOSE, body, inputs);
        return new MultiActionDialog(common, List.of(submit), cancel, 1);
    }

    /**
     * One button per option, rather than one control the player cycles.
     *
     * <p>A dialog's option control is a single button whose label changes as it
     * is clicked, and answering with it means finding the right label and then
     * pressing Submit: two steps, one of which reads as a broken button. Three
     * choices are three buttons, and choosing one is choosing it — the same
     * shape the menu transport has always had.
     *
     * <p>The option is carried in the action id by position rather than by its
     * key, because a key is a plugin's own string — {@code MATERIAL}, an id with
     * a colon in it — and an action id is a namespaced resource location, which
     * accepts neither.
     */
    private static List<ActionButton> choiceButtons(ChoiceInput<?> choice, String stateKey) {
        List<String> labels = ChoiceOptions.labels(choice);
        List<ActionButton> buttons = new ArrayList<>(labels.size());
        for (int index = 0; index < labels.size(); index++) {
            buttons.add(button(labels.get(index), CHOOSE + index + "/" + stateKey));
        }
        return buttons;
    }

    private static Input singleInput(InputRequest<?, ?> request, @Nullable String value,
                                     @Nullable Validation validation) {
        String initial = value != null ? value : stringify(request.defaultValue());
        Component label = fieldLabel("", validation == null ? null : validation.generalError());
        InputControl control;
        if (request instanceof FlagInput || request instanceof ConfirmInput) {
            control = new BooleanInputControl(label, Boolean.parseBoolean(initial), "true", "false");
        } else {
            control = new TextInputControl(CONTROL_WIDTH, label, validation != null,
                    initial, TEXT_LIMIT, null);
        }
        return new Input("value", control);
    }

    private static List<Input> formInputs(FormInput form, Map<String, String> values,
                                          @Nullable Validation validation) {
        List<Input> inputs = new ArrayList<>(form.fields().size());
        for (FormField<?> field : form.fields()) {
            String name = field.key().name();
            String initial = values.getOrDefault(name, stringify(field.defaultValue()));
            String error = validation == null ? null : validation.fieldErrors().get(name);
            Component label = fieldLabel(field.label(), error);
            InputControl control = switch (field.kind()) {
                case FLAG -> new BooleanInputControl(label, Boolean.parseBoolean(initial), "true", "false");
                // FormField currently exposes parser semantics but no choice option list. A text
                // control is therefore the only lossless raw-value control until such metadata exists.
                case TEXT, INTEGER, DECIMAL, AMOUNT, DURATION, CHOICE ->
                        new TextInputControl(CONTROL_WIDTH, label, true, initial, TEXT_LIMIT, null);
            };
            inputs.add(new Input(name, control));
        }
        return inputs;
    }

    private static ActionButton button(String label, String action) {
        return new ActionButton(new CommonButtonData(Text.component(label), null, CONTROL_WIDTH),
                new DynamicCustomAction(new ResourceLocation(NAMESPACE, action), null));
    }

    private static List<DialogBody> errorBody(@Nullable Validation validation) {
        if (validation == null || validation.valid() || validation.generalError() == null) {
            return List.of();
        }
        return List.of(new PlainMessageDialogBody(
                new PlainMessage(Text.component("{error}" + validation.generalError()), BODY_WIDTH)));
    }

    private static Component fieldLabel(String label, @Nullable String error) {
        String text = label;
        if (error != null && !error.isBlank()) {
            text = text.isBlank() ? "{error}" + error : text + "\n{error}" + error;
        }
        return Text.component(text);
    }

    private static void receive(WrapperCommonClientCustomClickAction<?> packet, UUID sender) {
        ResourceLocation id = packet.getId();
        if (!NAMESPACE.equals(id.getNamespace())) {
            return;
        }
        String action = id.getKey();
        int slash = action.indexOf('/');
        if (slash < 0) {
            return;
        }
        State state = STATES.get(action.substring(slash + 1));
        if (state == null || !state.session().playerId().equals(sender)) {
            return;
        }
        if (action.startsWith("cancel/")) {
            STATES.remove(state.key(), state);
            state.session().end(InputOutcome.CANCELLED);
            return;
        }
        if (action.startsWith(CHOOSE)) {
            chosen(state, action.substring(CHOOSE.length(), slash));
            return;
        }
        if (!action.startsWith("submit/")) {
            return;
        }

        NBT payload = packet.getPayload();
        NBTCompound compound = payload instanceof NBTCompound value ? value : new NBTCompound();
        Map<String, String> raw = rawValues(state.session().request(), compound);
        Object request = state.session().request();
        if (request instanceof FormInput form) {
            Object parsed = form.parseRaw(raw);
            if (parsed instanceof FormValues values) {
                STATES.remove(state.key(), state);
                state.session().complete(values);
            } else {
                reshow(new State(state.session(), state.key(), raw, (Validation) parsed));
            }
            return;
        }
        answer(state, (InputRequest<?, ?>) request, raw.getOrDefault("value", ""));
    }

    /**
     * Answers with the option a button stood for.
     *
     * <p>Parsed from its key rather than completed with the object, so a
     * {@code validate} rule on the request is checked here exactly as it is
     * when the same choice arrives from chat or from a menu.
     */
    private static void chosen(State state, String position) {
        if (!(state.session().request() instanceof ChoiceInput<?> choice)) {
            return;
        }
        String key = ChoiceOptions.keyAt(choice, position);
        if (key == null) {
            // A dialog left open across a reload, whose options are no longer
            // these. Answering position 2 of a list that changed would answer
            // with something the player never read.
            return;
        }
        answer(state, choice, key);
    }

    /** Parses one raw value, completing the session or re-showing the error. */
    private static void answer(State state, InputRequest<?, ?> request, String raw) {
        InputParser.Parsed<?> parsed = request.parseRaw(raw);
        if (parsed.ok()) {
            STATES.remove(state.key(), state);
            state.session().complete(parsed.value());
            return;
        }
        String error = parsed.error() == null ? "That value is not accepted." : parsed.error();
        reshow(new State(state.session(), state.key(), Map.of("value", raw), Validation.error(error)));
    }

    private static Map<String, String> rawValues(Object request, NBTCompound compound) {
        Map<String, String> values = new LinkedHashMap<>();
        if (request instanceof FormInput form) {
            for (FormField<?> field : form.fields()) {
                values.put(field.key().name(), raw(compound, field.key().name()));
            }
        } else {
            values.put("value", raw(compound, "value"));
        }
        return Map.copyOf(values);
    }

    private static String raw(NBTCompound compound, String key) {
        String text = compound.getStringTagValueOrNull(key);
        if (text != null) {
            return text;
        }
        NBTByte flag = compound.getTagOfTypeOrNull(key, NBTByte.class);
        if (flag != null) {
            return Boolean.toString(flag.getAsBool());
        }
        Number number = compound.getNumberTagValueOrNull(key);
        return number == null ? "" : number.toString();
    }

    private static void reshow(State replacement) {
        State current = STATES.get(replacement.key());
        if (current == null || current.session() != replacement.session()) {
            return;
        }
        STATES.put(replacement.key(), replacement);
        Plugin plugin = owner;
        Player player = Bukkit.getPlayer(replacement.session().playerId());
        if (plugin == null || player == null || !player.isOnline()) {
            replacement.session().end(InputOutcome.UNAVAILABLE);
            return;
        }
        Tasks.of(plugin).runAtEntity(player, () -> {
            try {
                User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
                if (user == null) {
                    replacement.session().end(InputOutcome.UNAVAILABLE);
                    return;
                }
                send(user, dialog(replacement));
            } catch (Throwable failure) {
                replacement.session().end(InputOutcome.UNAVAILABLE);
            }
        }, () -> replacement.session().end(InputOutcome.UNAVAILABLE));
    }

    private static void send(User user, Dialog dialog) {
        PacketWrapper<?> packet = user.getConnectionState() == ConnectionState.CONFIGURATION
                ? new WrapperConfigServerShowDialog(dialog)
                : new WrapperPlayServerShowDialog(dialog);
        user.sendPacket(packet);
    }

    private static String prompt(Object request) {
        return request instanceof FormInput form ? form.prompt() : ((InputRequest<?, ?>) request).prompt();
    }

    private static Map<String, String> initialValues(Object request) {
        Map<String, String> values = new LinkedHashMap<>();
        if (request instanceof FormInput form) {
            for (FormField<?> field : form.fields()) {
                values.put(field.key().name(), stringify(field.defaultValue()));
            }
        } else {
            values.put("value", stringify(((InputRequest<?, ?>) request).defaultValue()));
        }
        return Map.copyOf(values);
    }

    private static String stringify(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String sessionKey(InputSession session) {
        return session.id().toString().replace("-", "");
    }

    private record State(InputSession session, String key, Map<String, String> values,
                         @Nullable Validation validation) {
    }
}
