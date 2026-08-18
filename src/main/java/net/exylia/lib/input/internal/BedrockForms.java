package net.exylia.lib.input.internal;

import net.exylia.lib.input.ChoiceInput;
import net.exylia.lib.input.ConfirmInput;
import net.exylia.lib.input.FlagInput;
import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormInput;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputParser;
import net.exylia.lib.input.InputRequest;
import net.exylia.lib.input.NumberInput;
import net.exylia.lib.input.SearchInput;
import net.exylia.lib.input.Validation;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Reflective Floodgate/Cumulus form implementation.
 *
 * <p>Floodgate is intentionally not a build dependency. Every bridge and form
 * class is named with {@link Class#forName(String)}, and every method used by a
 * response callback is cached once in {@link Access}. This keeps ExyliaLib
 * compilable and loadable without Floodgate while still providing complete
 * native forms when the API is present; a direct Cumulus type in a descriptor
 * would make the JVM reject this class before a fallback transport could run.
 *
 * <p>Callbacks retain only a generation-tagged state. Re-showing after invalid
 * input increments the generation, so a delayed close callback from the old
 * form cannot cancel the corrected form that replaced it.
 */
final class BedrockForms {

    private static final Access ACCESS = Access.detect();
    private static final ConcurrentMap<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATIONS = new AtomicLong();

    private BedrockForms() {
    }

    /** Returns whether the complete form API surface was resolved at startup. */
    static boolean available() {
        return ACCESS != null;
    }

    /** Shows one supported request and retains it only after form construction succeeds. */
    static boolean show(@NotNull Plugin plugin, @NotNull InputSession session) {
        if (ACCESS == null || !supports(session.request())) {
            return false;
        }
        Player player = Bukkit.getPlayer(session.playerId());
        if (player == null || !player.isOnline()) {
            return false;
        }
        State state = new State(plugin, session, GENERATIONS.incrementAndGet(),
                initialValues(session.request()), null);
        try {
            Object form = build(state);
            STATES.put(session.playerId(), state);
            ACCESS.send(session.playerId(), form);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            STATES.remove(session.playerId(), state);
            return false;
        }
    }

    /** Invalidates callbacks belonging to a terminal session. Floodgate exposes no portable close-form call. */
    static void close(@NotNull InputSession session) {
        STATES.computeIfPresent(session.playerId(), (ignored, state) ->
                state.session() == session ? null : state);
    }

    private static boolean supports(Object request) {
        return request instanceof FormInput
                || request instanceof InputRequest<?, ?> && !(request instanceof SearchInput<?>);
    }

    private static Object build(State state) throws ReflectiveOperationException {
        Object request = state.session().request();
        if (request instanceof ChoiceInput<?> choice) {
            return simple(state, choice);
        }
        if (request instanceof FlagInput || request instanceof ConfirmInput) {
            return modal(state, (InputRequest<?, ?>) request);
        }
        if (request instanceof FormInput form) {
            return customForm(state, form);
        }
        return customSingle(state, (InputRequest<?, ?>) request);
    }

    private static Object customSingle(State state, InputRequest<?, ?> request)
            throws ReflectiveOperationException {
        Object builder = ACCESS.customBuilder.invoke(null);
        ACCESS.title.invoke(builder, title(request.prompt(), state.validation()));
        Range range = integerRange(request);
        if (range != null) {
            float initial = boundedInitial(state.values().get("value"), range.minimum(), range.maximum());
            ACCESS.slider.invoke(builder, label("", state.validation(), null),
                    range.minimum(), range.maximum(), 1.0F, initial);
        } else {
            ACCESS.input.invoke(builder, label("", state.validation(), null), "",
                    state.values().getOrDefault("value", ""));
        }
        handlers(builder, state, response -> {
            String raw = range == null
                    ? ACCESS.inputResponse.invoke(response, 0).toString()
                    : Long.toString(Math.round((Float) ACCESS.sliderResponse.invoke(response, 0)));
            submitSingle(state, request, raw);
        });
        return ACCESS.build.invoke(builder);
    }

    private static Object modal(State state, InputRequest<?, ?> request)
            throws ReflectiveOperationException {
        Object builder = ACCESS.modalBuilder.invoke(null);
        ACCESS.title.invoke(builder, plain(request.prompt()));
        String error = state.validation() == null ? "" : String.join("\n", state.validation().messages());
        ACCESS.content.invoke(builder, error);
        if (request instanceof ConfirmInput confirm) {
            ACCESS.button1.invoke(builder, plain(confirm.confirmLabel()));
            ACCESS.button2.invoke(builder, plain(confirm.denyLabel()));
        } else {
            ACCESS.button1.invoke(builder, "Yes");
            ACCESS.button2.invoke(builder, "No");
        }
        handlers(builder, state, response -> submitSingle(state, request,
                Boolean.toString((Boolean) ACCESS.clickedFirst.invoke(response))));
        return ACCESS.build.invoke(builder);
    }

    private static Object simple(State state, ChoiceInput<?> choice)
            throws ReflectiveOperationException {
        Object builder = ACCESS.simpleBuilder.invoke(null);
        ACCESS.title.invoke(builder, plain(choice.prompt()));
        String error = state.validation() == null ? "" : String.join("\n", state.validation().messages());
        ACCESS.content.invoke(builder, error);
        for (Object option : choice.choices()) {
            ACCESS.simpleButton.invoke(builder, choiceLabel(choice, option));
        }
        handlers(builder, state, response -> {
            int selected = (Integer) ACCESS.clickedButtonId.invoke(response);
            if (selected < 0 || selected >= choice.choices().size()) {
                reshow(state, state.values(), Validation.error("Choose one of the available options."));
                return;
            }
            submitSingle(state, choice, choiceKey(choice, choice.choices().get(selected)));
        });
        return ACCESS.build.invoke(builder);
    }

    private static Object customForm(State state, FormInput form)
            throws ReflectiveOperationException {
        Object builder = ACCESS.customBuilder.invoke(null);
        ACCESS.title.invoke(builder, title(form.prompt(), state.validation()));
        for (FormField<?> field : form.fields()) {
            String name = field.key().name();
            String initial = state.values().getOrDefault(name, stringify(field.defaultValue()));
            String label = label(field.label(), state.validation(), name);
            if (field.kind() == FormField.Kind.FLAG) {
                ACCESS.toggle.invoke(builder, label, Boolean.parseBoolean(initial));
            } else {
                // FormField.Kind.CHOICE currently carries no options. An input preserves the
                // raw key and still obeys form.parseRaw; inventing a dropdown would lose data.
                ACCESS.input.invoke(builder, label, "", initial);
            }
        }
        handlers(builder, state, response -> {
            Map<String, String> raw = new LinkedHashMap<>();
            List<FormField<?>> fields = form.fields();
            for (int index = 0; index < fields.size(); index++) {
                FormField<?> field = fields.get(index);
                Object value = field.kind() == FormField.Kind.FLAG
                        ? ACCESS.toggleResponse.invoke(response, index)
                        : ACCESS.inputResponse.invoke(response, index);
                raw.put(field.key().name(), String.valueOf(value));
            }
            submitForm(state, form, Map.copyOf(raw));
        });
        return ACCESS.build.invoke(builder);
    }

    private static void handlers(Object builder, State state, ThrowingConsumer valid)
            throws ReflectiveOperationException {
        Consumer<Object> validHandler = response -> dispatch(state, () -> {
            try {
                valid.accept(response);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                state.session().end(InputOutcome.UNAVAILABLE);
            }
        });
        Runnable closed = () -> dispatch(state, () -> {
            if (isCurrent(state)) {
                STATES.remove(state.session().playerId(), state);
                state.session().end(InputOutcome.CANCELLED);
            }
        });
        ACCESS.validResultHandler.invoke(builder, validHandler);
        ACCESS.closedOrInvalidResultHandler.invoke(builder, closed);
    }

    private static void submitSingle(State state, InputRequest<?, ?> request, String raw) {
        if (!isCurrent(state)) {
            return;
        }
        InputParser.Parsed<?> parsed = request.parseRaw(raw);
        if (parsed.ok()) {
            STATES.remove(state.session().playerId(), state);
            state.session().complete(parsed.value());
            return;
        }
        String error = parsed.error() == null ? "That value is not accepted." : parsed.error();
        reshow(state, Map.of("value", raw), Validation.error(error));
    }

    private static void submitForm(State state, FormInput form, Map<String, String> raw) {
        if (!isCurrent(state)) {
            return;
        }
        Object parsed = form.parseRaw(raw);
        if (parsed instanceof FormValues values) {
            STATES.remove(state.session().playerId(), state);
            state.session().complete(values);
        } else {
            reshow(state, raw, (Validation) parsed);
        }
    }

    private static void reshow(State previous, Map<String, String> values, Validation validation) {
        if (!isCurrent(previous)) {
            return;
        }
        State replacement = new State(previous.plugin(), previous.session(),
                GENERATIONS.incrementAndGet(), Map.copyOf(values), validation);
        STATES.put(previous.session().playerId(), replacement);
        try {
            ACCESS.send(previous.session().playerId(), build(replacement));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            STATES.remove(previous.session().playerId(), replacement);
            previous.session().end(InputOutcome.UNAVAILABLE);
        }
    }

    private static void dispatch(State state, Runnable action) {
        Player player = Bukkit.getPlayer(state.session().playerId());
        if (player == null || !player.isOnline()) {
            state.session().end(InputOutcome.DISCONNECTED);
            return;
        }
        try {
            Tasks.of(state.plugin()).runAtEntity(player, action,
                    () -> state.session().end(InputOutcome.UNAVAILABLE));
        } catch (Throwable failure) {
            state.session().end(InputOutcome.UNAVAILABLE);
        }
    }

    private static boolean isCurrent(State state) {
        State current = STATES.get(state.session().playerId());
        return current != null && current.session() == state.session()
                && current.generation() == state.generation();
    }

    private static @Nullable Range integerRange(InputRequest<?, ?> request) {
        if (!(request instanceof NumberInput<?> number) || request.parser() != net.exylia.lib.input.InputParser.integer()) {
            return null;
        }
        try {
            Object minimum = ACCESS.numberMinimum.get(number);
            Object maximum = ACCESS.numberMaximum.get(number);
            if (!(minimum instanceof Long min) || !(maximum instanceof Long max)) {
                return null;
            }
            if (min < -16_777_216L || max > 16_777_216L || min >= max) {
                return null;
            }
            return new Range(min.floatValue(), max.floatValue());
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static float boundedInitial(String raw, float minimum, float maximum) {
        try {
            float value = Float.parseFloat(raw);
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException ignored) {
            return minimum;
        }
    }

    private static String title(String prompt, @Nullable Validation validation) {
        if (validation == null || validation.generalError() == null) {
            return plain(prompt);
        }
        return plain(prompt) + "\n" + plain(validation.generalError());
    }

    private static String label(String base, @Nullable Validation validation, @Nullable String field) {
        String error = validation == null ? null
                : field == null ? validation.generalError() : validation.fieldErrors().get(field);
        return error == null ? plain(base) : plain(base) + (base.isBlank() ? "" : "\n") + plain(error);
    }

    private static String plain(String value) {
        return Text.of(value == null ? "" : value).plain();
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

    @SuppressWarnings("unchecked")
    private static <T> String choiceLabel(ChoiceInput<?> untyped, Object value) {
        return plain(((ChoiceInput<T>) untyped).labelOf((T) value));
    }

    @SuppressWarnings("unchecked")
    private static <T> String choiceKey(ChoiceInput<?> untyped, Object value) {
        return ((ChoiceInput<T>) untyped).keyOf((T) value);
    }

    private record State(Plugin plugin, InputSession session, long generation,
                         Map<String, String> values, @Nullable Validation validation) {
    }

    private record Range(float minimum, float maximum) {
    }

    @FunctionalInterface
    private interface ThrowingConsumer {
        void accept(Object value) throws ReflectiveOperationException;
    }

    /** All optional API classes and reflective lookups, resolved once rather than per form. */
    private static final class Access {

        private final Object floodgate;
        private final Method sendForm;
        private final Method customBuilder;
        private final Method modalBuilder;
        private final Method simpleBuilder;
        private final Method title;
        private final Method input;
        private final Method slider;
        private final Method toggle;
        private final Method content;
        private final Method button1;
        private final Method button2;
        private final Method simpleButton;
        private final Method validResultHandler;
        private final Method closedOrInvalidResultHandler;
        private final Method build;
        private final Method inputResponse;
        private final Method sliderResponse;
        private final Method toggleResponse;
        private final Method clickedFirst;
        private final Method clickedButtonId;
        private final Field numberMinimum;
        private final Field numberMaximum;

        private Access(Object floodgate, Method sendForm, Method customBuilder,
                       Method modalBuilder, Method simpleBuilder, Method title,
                       Method input, Method slider, Method toggle, Method content,
                       Method button1, Method button2, Method simpleButton,
                       Method validResultHandler, Method closedOrInvalidResultHandler,
                       Method build, Method inputResponse, Method sliderResponse,
                       Method toggleResponse, Method clickedFirst, Method clickedButtonId,
                       Field numberMinimum, Field numberMaximum) {
            this.floodgate = floodgate;
            this.sendForm = sendForm;
            this.customBuilder = customBuilder;
            this.modalBuilder = modalBuilder;
            this.simpleBuilder = simpleBuilder;
            this.title = title;
            this.input = input;
            this.slider = slider;
            this.toggle = toggle;
            this.content = content;
            this.button1 = button1;
            this.button2 = button2;
            this.simpleButton = simpleButton;
            this.validResultHandler = validResultHandler;
            this.closedOrInvalidResultHandler = closedOrInvalidResultHandler;
            this.build = build;
            this.inputResponse = inputResponse;
            this.sliderResponse = sliderResponse;
            this.toggleResponse = toggleResponse;
            this.clickedFirst = clickedFirst;
            this.clickedButtonId = clickedButtonId;
            this.numberMinimum = numberMinimum;
            this.numberMaximum = numberMaximum;
        }

        private static @Nullable Access detect() {
            try {
                Class<?> floodgateType = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Class<?> formType = Class.forName("org.geysermc.cumulus.form.Form");
                Class<?> customType = Class.forName("org.geysermc.cumulus.form.CustomForm");
                Class<?> modalType = Class.forName("org.geysermc.cumulus.form.ModalForm");
                Class<?> simpleType = Class.forName("org.geysermc.cumulus.form.SimpleForm");
                Class<?> builderType = Class.forName("org.geysermc.cumulus.form.util.FormBuilder");
                Class<?> customBuilderType = Class.forName("org.geysermc.cumulus.form.CustomForm$Builder");
                Class<?> modalBuilderType = Class.forName("org.geysermc.cumulus.form.ModalForm$Builder");
                Class<?> simpleBuilderType = Class.forName("org.geysermc.cumulus.form.SimpleForm$Builder");
                Class<?> customResponse = Class.forName("org.geysermc.cumulus.response.CustomFormResponse");
                Class<?> modalResponse = Class.forName("org.geysermc.cumulus.response.ModalFormResponse");
                Class<?> simpleResponse = Class.forName("org.geysermc.cumulus.response.SimpleFormResponse");
                Object floodgate = floodgateType.getMethod("getInstance").invoke(null);
                Field minimum = NumberInput.class.getDeclaredField("minimum");
                Field maximum = NumberInput.class.getDeclaredField("maximum");
                minimum.setAccessible(true);
                maximum.setAccessible(true);
                return new Access(floodgate,
                        floodgateType.getMethod("sendForm", UUID.class, formType),
                        customType.getMethod("builder"), modalType.getMethod("builder"),
                        simpleType.getMethod("builder"), builderType.getMethod("title", String.class),
                        customBuilderType.getMethod("input", String.class, String.class, String.class),
                        customBuilderType.getMethod("slider", String.class, float.class, float.class,
                                float.class, float.class),
                        customBuilderType.getMethod("toggle", String.class, boolean.class),
                        modalBuilderType.getMethod("content", String.class),
                        modalBuilderType.getMethod("button1", String.class),
                        modalBuilderType.getMethod("button2", String.class),
                        simpleBuilderType.getMethod("button", String.class),
                        builderType.getMethod("validResultHandler", Consumer.class),
                        builderType.getMethod("closedOrInvalidResultHandler", Runnable.class),
                        builderType.getMethod("build"),
                        customResponse.getMethod("asInput", int.class),
                        customResponse.getMethod("asSlider", int.class),
                        customResponse.getMethod("asToggle", int.class),
                        modalResponse.getMethod("clickedFirst"),
                        simpleResponse.getMethod("clickedButtonId"), minimum, maximum);
            } catch (Throwable absentOrIncompatible) {
                return null;
            }
        }

        private void send(UUID player, Object form) throws ReflectiveOperationException {
            sendForm.invoke(floodgate, player, form);
        }
    }
}
