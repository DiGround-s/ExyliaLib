package net.exylia.lib.ability;

import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.util.editor.Editors;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * How an ability draws and edits itself on screen; handed to the list editor
 * by {@link PluginAbilities#editor}.
 *
 * <h2>Editing one is a section at a time</h2>
 * A row click asks which part: {@code MECHANICS} (trigger, chance, cooldown,
 * period, chain) is one prefilled form; {@code CONDITIONS} is one box, a
 * condition per line; {@code EFFECTS} is a list of its own where adding asks
 * which effect and the form shows exactly the keys that effect reads.
 *
 * <p>Everything round-trips through {@link Ability#source()}: a key the
 * library does not know is shown, kept and written back, never dropped.
 */
final class AbilityDescriptor implements EditorDescriptor<Ability> {

    static final String TYPE_KEY = "exylia:abilities";

    private static final FormKey<String> TRIGGER = FormKey.text("trigger");
    private static final FormKey<String> CHANCE = FormKey.text("chance");
    private static final FormKey<String> COOLDOWN = FormKey.text("cooldown");
    private static final FormKey<String> EVERY = FormKey.text("every");
    private static final FormKey<Boolean> CHAIN = FormKey.flag("chain");
    private static final FormKey<String> LINES = FormKey.text("lines");

    private enum Section { MECHANICS, CONDITIONS, EFFECTS }

    private final PluginAbilities abilities;

    AbilityDescriptor(PluginAbilities abilities) {
        this.abilities = abilities;
    }

    @Override
    public @NotNull String label(@NotNull Ability ability) {
        return "{primary}&l" + ability.trigger().replace('_', ' ');
    }

    @Override
    public @NotNull String icon(@NotNull Ability ability) {
        return iconOf(ability.trigger()).name();
    }

    @Override
    public @NotNull List<String> lore(@NotNull Ability ability) {
        Map<String, Object> source = ability.source();
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(Phrases.tr("{secondary}Information:"));
        lore.add(Phrases.tr(" {letters_black}▎ {letters}Chance {letters_black}» {info}{0}%",
                written(source.get("chance"), "100")));
        Object cooldown = source.get("cooldown");
        if (cooldown != null) {
            lore.add(Phrases.tr(" {letters_black}▎ {letters}Cooldown {letters_black}» {info}{0} ⌚", written(cooldown, "")));
        }
        lore.add(Phrases.tr(" {letters_black}▎ {letters}Conditions {letters_black}» {info}{0}", lines(source.get("conditions")).size()));
        List<String> effects = new ArrayList<>();
        for (Line line : lines(source.get("effects"))) effects.add(line.name());
        lore.add(Phrases.tr(" {letters_black}▎ {letters}Effects {letters_black}» {info}{0}",
                effects.isEmpty() ? Phrases.tr("nothing") : String.join(", ", effects)));
        return lore;
    }

    @Override
    public @NotNull Ability create() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("trigger", Triggers.ATTACK);
        return compileOr(freshId(), source, null);
    }

    @Override
    public @NotNull CompletionStage<Optional<Ability>> create(@NotNull Player viewer) {
        List<String> triggers = sortedTriggers();
        return Inputs.of(abilities.plugin())
                .choice(viewer, Phrases.tr("{primary}&lWHEN?"), triggers)
                .label(trigger -> "{primary}&l" + trigger.replace('_', ' '))
                .icon(AbilityDescriptor::iconOf)
                .key(trigger -> trigger)
                .open()
                .thenApply(picked -> {
                    if (!picked.completed()) return Optional.empty();
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("trigger", picked.value());
                    source.put("effects", new ArrayList<>());
                    return Optional.of(compileOr(freshId(), source, null));
                });
    }

    @Override
    public @NotNull Ability copy(@NotNull Ability ability) {
        return compileOr(ability.id() + "-copy", ability.source(), ability);
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    @Override
    public boolean isComplete(@NotNull Ability ability) {
        return !ability.effects().isEmpty();
    }

    @Override
    public @NotNull CompletionStage<Optional<Ability>> edit(@NotNull Player viewer, @NotNull Ability ability) {
        return Inputs.of(abilities.plugin())
                .choice(viewer, Phrases.tr("{primary}&lEDIT {0}", ability.trigger().replace('_', ' ')),
                        List.of(Section.values()))
                .label(section -> switch (section) {
                    case MECHANICS -> Phrases.tr("{primary}&lMECHANICS");
                    case CONDITIONS -> Phrases.tr("{primary}&lCONDITIONS");
                    case EFFECTS -> Phrases.tr("{primary}&lEFFECTS");
                })
                .description(section -> switch (section) {
                    case MECHANICS -> Phrases.tr("Trigger, odds, cooldown and chaining.");
                    case CONDITIONS -> Phrases.tr("What must hold before it rolls.");
                    case EFFECTS -> Phrases.tr("What it does when it goes off.");
                })
                .icon(section -> switch (section) {
                    case MECHANICS -> Material.CLOCK;
                    case CONDITIONS -> Material.COMPARATOR;
                    case EFFECTS -> Material.BLAZE_POWDER;
                })
                .key(Enum::name)
                .open()
                .thenCompose(section -> {
                    if (!section.completed()) return CompletableFuture.completedFuture(Optional.<Ability>empty());
                    return switch (section.value()) {
                        case MECHANICS -> mechanics(viewer, ability);
                        case CONDITIONS -> conditions(viewer, ability);
                        case EFFECTS -> effects(viewer, ability);
                    };
                });
    }

    private CompletionStage<Optional<Ability>> mechanics(Player viewer, Ability ability) {
        Map<String, Object> source = ability.source();
        List<FormField.Option> triggers = new ArrayList<>();
        for (String trigger : sortedTriggers()) {
            triggers.add(new FormField.Option(trigger, "{letters}" + trigger.replace('_', ' ')));
        }
        EditorForm form = EditorForm.of(abilities.plugin(), viewer, Phrases.tr("{primary}&lMECHANICS"))
                .choice(TRIGGER, Phrases.tr("When"), ability.trigger(), triggers)
                .text(CHANCE, Phrases.tr("Chance, in percent, or a formula of level"), written(source.get("chance"), ""))
                .text(COOLDOWN, Phrases.tr("Cooldown, or a formula of level in seconds"),
                        written(source.get("cooldown"), ""))
                .text(EVERY, Phrases.tr("Every"), written(source.get("every"), ""))
                .flag(CHAIN, Phrases.tr("Its damage may set off other abilities"), Boolean.TRUE.equals(source.get("chain")));
        return form.ask(values -> {
            Map<String, Object> edited = ability.source();
            edited.put("trigger", values.getOr(TRIGGER, ability.trigger()));
            put(edited, "chance", values.getOr(CHANCE, ""));
            put(edited, "cooldown", values.getOr(COOLDOWN, ""));
            put(edited, "every", values.getOr(EVERY, ""));
            if (values.getOr(CHAIN, false)) edited.put("chain", true);
            else edited.remove("chain");
            return compileOr(ability.id(), edited, ability);
        });
    }

    private CompletionStage<Optional<Ability>> conditions(Player viewer, Ability ability) {
        StringBuilder current = new StringBuilder();
        for (Line line : lines(ability.source().get("conditions"))) {
            if (!current.isEmpty()) current.append('\n');
            current.append(line.text());
        }
        return EditorForm.of(abilities.plugin(), viewer, Phrases.tr("{primary}&lCONDITIONS"))
                .text(LINES, Phrases.tr("One per line, such as  target: player"), current.toString(), 6)
                .ask(values -> {
                    List<Object> parsed = new ArrayList<>();
                    for (String text : values.getOr(LINES, "").split("\\R")) {
                        if (!text.isBlank()) parsed.add(Line.parse(text).toYaml());
                    }
                    Map<String, Object> edited = ability.source();
                    if (parsed.isEmpty()) edited.remove("conditions");
                    else edited.put("conditions", parsed);
                    return compileOr(ability.id(), edited, ability);
                });
    }

    private CompletionStage<Optional<Ability>> effects(Player viewer, Ability ability) {
        CompletableFuture<Optional<Ability>> answer = new CompletableFuture<>();
        Editors.of(abilities.plugin())
                .list(new EffectDescriptor(), Line.class, lines(ability.source().get("effects")))
                .title(Phrases.tr("{primary}&lEFFECTS"))
                .onSave(edited -> {
                    Map<String, Object> source = ability.source();
                    List<Object> written = new ArrayList<>();
                    edited.forEach(line -> written.add(line.toYaml()));
                    source.put("effects", written);
                    answer.complete(Optional.of(compileOr(ability.id(), source, ability)));
                })
                .onCancel(() -> answer.complete(Optional.empty()))
                .open(viewer);
        return answer;
    }

    // ------------------------------------------------------------------ helpers

    private Ability compileOr(String id, Map<String, Object> source, @Nullable Ability fallback) {
        Ability compiled = abilities.compile(id, source, problem -> { });
        if (compiled != null) return compiled;
        if (fallback != null) return fallback;
        Map<String, Object> plain = new LinkedHashMap<>(source);
        plain.put("trigger", Triggers.ATTACK);
        return java.util.Objects.requireNonNull(abilities.compile(id, plain, problem -> { }));
    }

    private List<String> sortedTriggers() {
        List<String> out = new ArrayList<>(Triggers.BUILT_IN);
        abilities.triggers().stream().filter(trigger -> !out.contains(trigger)).sorted().forEach(out::add);
        return out;
    }

    private static String freshId() {
        return "ability-" + Long.toString(System.nanoTime() & 0xFFFFFF, 36);
    }

    private static void put(Map<String, Object> source, String key, String value) {
        if (value == null || value.isBlank()) source.remove(key);
        else source.put(key, Line.scalar(value.trim()));
    }

    private static String written(@Nullable Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    static List<Line> lines(@Nullable Object raw) {
        List<Line> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                map.forEach((key, value) -> out.add(new Line(String.valueOf(key), Ability.deepCopy(value))));
            } else if (item != null) {
                out.add(new Line(String.valueOf(item), null));
            }
        }
        return out;
    }

    static Material iconOf(String trigger) {
        return switch (trigger) {
            case Triggers.ATTACK -> Material.IRON_SWORD;
            case Triggers.HIT_TAKEN -> Material.SHIELD;
            case Triggers.KILL -> Material.SKELETON_SKULL;
            case Triggers.DEATH -> Material.TOTEM_OF_UNDYING;
            case Triggers.BOW_SHOOT -> Material.BOW;
            case Triggers.PROJECTILE_HIT -> Material.ARROW;
            case Triggers.BLOCK_BREAK -> Material.IRON_PICKAXE;
            case Triggers.FISH -> Material.FISHING_ROD;
            case Triggers.JUMP -> Material.RABBIT_FOOT;
            case Triggers.SNEAK -> Material.LEATHER_BOOTS;
            case Triggers.LOW_HEALTH -> Material.REDSTONE;
            case Triggers.EQUIP, Triggers.UNEQUIP -> Material.DIAMOND_CHESTPLATE;
            case Triggers.PASSIVE -> Material.BEACON;
            case Triggers.INTERVAL -> Material.CLOCK;
            default -> Material.ENCHANTED_BOOK;
        };
    }

    /**
     * One {@code - name: value} line of conditions or effects, as written.
     *
     * @param name  the condition or effect
     * @param value its single value or map of keys; {@code null} for none
     */
    record Line(@NotNull String name, @Nullable Object value) {

        private static final Yaml FLOW = flow();

        /** What goes back in the list: {@code name: value}, or the bare name. */
        Object toYaml() {
            if (value == null || (value instanceof String text && text.isEmpty())) return name;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(name, value);
            return map;
        }

        /** The line as a person types it: {@code damage: {amount: 2, target: victim}}. */
        String text() {
            if (value == null || (value instanceof String text && text.isEmpty())) return name;
            return name + ": " + FLOW.dump(value).trim();
        }

        static Line parse(String text) {
            try {
                Object loaded = new Yaml().load(text.trim());
                if (loaded instanceof Map<?, ?> map && map.size() == 1) {
                    Map.Entry<?, ?> entry = map.entrySet().iterator().next();
                    return new Line(String.valueOf(entry.getKey()), entry.getValue());
                }
            } catch (YAMLException unreadable) {
                // Kept as written: an editor that eats a line it cannot read eats a working config.
            }
            return new Line(text.trim(), null);
        }

        /** A typed value as YAML would read it: {@code 2} a number, {@code 0.5 * level} text. */
        static Object scalar(String text) {
            try {
                Object loaded = new Yaml().load(text);
                return loaded == null ? text : loaded;
            } catch (YAMLException unreadable) {
                return text;
            }
        }

        private static Yaml flow() {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
            options.setWidth(Integer.MAX_VALUE);
            return new Yaml(options);
        }
    }

    /** One effect of the list: adding asks which, the form shows the keys it reads. */
    private final class EffectDescriptor implements EditorDescriptor<Line> {

        @Override
        public @NotNull String label(@NotNull Line line) {
            return "{primary}&l" + line.name().toUpperCase(Locale.ROOT).replace('-', ' ');
        }

        @Override
        public @NotNull String icon(@NotNull Line line) {
            return Material.BLAZE_POWDER.name();
        }

        @Override
        public @NotNull List<String> lore(@NotNull Line line) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            if (line.value() instanceof Map<?, ?> map) {
                map.forEach((key, value) -> lore.add(" {letters_black}▎ {letters}" + key + " {letters_black}» {info}"
                        + value));
            } else if (line.value() != null && !String.valueOf(line.value()).isEmpty()) {
                lore.add(" {letters_black}▎ {info}" + line.value());
            }
            return lore;
        }

        @Override
        public @NotNull Line create() {
            return new Line("damage", new LinkedHashMap<String, Object>());
        }

        @Override
        public @NotNull CompletionStage<Optional<Line>> create(@NotNull Player viewer) {
            List<String> names = new ArrayList<>(abilities.effects());
            names.sort(null);
            return Inputs.of(abilities.plugin())
                    .choice(viewer, Phrases.tr("{primary}&lWHAT DOES IT DO?"), names)
                    .label(name -> "{primary}&l" + name.toUpperCase(Locale.ROOT).replace('-', ' '))
                    .icon(name -> Material.BLAZE_POWDER)
                    .key(name -> name)
                    .open()
                    .thenApply(picked -> picked.completed()
                            ? Optional.of(new Line(picked.value(), new LinkedHashMap<String, Object>()))
                            : Optional.empty());
        }

        @Override
        public @NotNull Line copy(@NotNull Line line) {
            return new Line(line.name(), Ability.deepCopy(line.value()));
        }

        @Override
        public @NotNull String typeKey() {
            return TYPE_KEY + "-effects";
        }

        @Override
        public @NotNull CompletionStage<Optional<Line>> edit(@NotNull Player viewer, @NotNull Line line) {
            Set<String> keys = new LinkedHashSet<>();
            boolean single = line.value() != null && !(line.value() instanceof Map<?, ?>);
            if (line.value() instanceof Map<?, ?> map) map.keySet().forEach(key -> keys.add(String.valueOf(key)));
            keys.addAll(abilities.effectKeys(line.name()));
            if (single || keys.isEmpty() || keys.equals(Set.of("value"))) {
                FormKey<String> value = FormKey.text("value");
                return EditorForm.of(abilities.plugin(), viewer, Phrases.tr("{primary}&lEDIT EFFECT"))
                        .text(value, line.name(), line.value() == null ? "" : String.valueOf(line.value()), 2)
                        .ask(values -> {
                            String text = values.getOr(value, "").trim();
                            return new Line(line.name(), text.isEmpty() ? null : Line.scalar(text));
                        });
            }
            keys.remove("value");
            List<String> ordered = new ArrayList<>(keys);
            Map<?, ?> current = line.value() instanceof Map<?, ?> map ? map : Map.of();
            EditorForm form = EditorForm.of(abilities.plugin(), viewer, Phrases.tr("{primary}&lEDIT EFFECT"));
            for (int i = 0; i < ordered.size(); i++) {
                Object now = current.get(ordered.get(i));
                form.text(FormKey.text("f" + i), ordered.get(i), now == null ? "" : String.valueOf(now));
            }
            return form.ask((FormValues values) -> {
                Map<String, Object> edited = new LinkedHashMap<>();
                for (int i = 0; i < ordered.size(); i++) {
                    String text = values.getOr(FormKey.text("f" + i), "").trim();
                    if (!text.isEmpty()) edited.put(ordered.get(i), Line.scalar(text));
                }
                return new Line(line.name(), edited);
            });
        }
    }
}
