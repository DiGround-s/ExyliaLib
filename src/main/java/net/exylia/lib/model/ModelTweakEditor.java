package net.exylia.lib.model;

import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.util.editor.EditorButton;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.util.editor.EditorView;
import net.exylia.lib.util.editor.Editors;
import net.exylia.lib.util.editor.ListEditor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One screen for everything a wearer may change about a model without touching
 * its file, with the model standing in front of the viewer as they change it.
 *
 * <pre>{@code
 * models.tweakEditor("stone_golem", template.model().options())
 *         .title("{primary}&lMODEL TWEAKS {letters_black}» {highlight}" + template.id())
 *         .onSave(options -> store.save(template.withModel(template.model()
 *                 .withScale(options.scale()).withTweaks(options.tweaks()))))
 *         .onCancel(() -> ModelMenu.open(player))
 *         .open(player);
 * }</pre>
 *
 * <p>The rows are the bones it changes: hidden, or drawn in another block or
 * item. The buttons below them change its size and place, how fast each state
 * plays and the box it is hit by, and PREVIEW puts it in front of the viewer,
 * turning slowly and playing each of its animations, as the edits stand.
 * Nothing is written until save, as in every editor; every field opens on the
 * value it holds.
 *
 * <p>{@code onSave} is told the options handed in with their scale and tweaks
 * replaced; everything else in them passes through.
 *
 * @since 1.321.0
 */
public final class ModelTweakEditor {

    /** How long a preview stands, in ticks. */
    private static final long PREVIEW_TICKS = 600;
    /** How often a preview plays its next animation, in ticks. */
    private static final long PREVIEW_STEP = 60;

    private static final FormKey<String> BONE = FormKey.text("bone");
    private static final FormKey<Boolean> HIDDEN = FormKey.flag("hidden");
    private static final FormKey<String> MATERIAL = FormKey.text("material");
    private static final FormKey<BigDecimal> SCALE = FormKey.decimal("scale");
    private static final FormKey<BigDecimal> OFFSET_X = FormKey.decimal("offset_x");
    private static final FormKey<BigDecimal> OFFSET_Y = FormKey.decimal("offset_y");
    private static final FormKey<BigDecimal> OFFSET_Z = FormKey.decimal("offset_z");
    private static final FormKey<BigDecimal> WIDTH = FormKey.decimal("width");
    private static final FormKey<BigDecimal> HEIGHT = FormKey.decimal("height");

    /** One bone and what is done to it. */
    record Row(String bone, ModelTweaks.Bone tweak) {
    }

    private final Plugin plugin;
    private final PluginModels models;
    private final Model model;
    private final ModelOptions options;
    private final ListEditor<Row> list;
    private final TaskScheduler tasks;

    private double scale;
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private final Map<String, Double> speeds;
    private double width;
    private double height;

    private Consumer<ModelOptions> onSave = edited -> { };
    private Runnable onCancel = () -> { };
    private @Nullable ArmorStand stand;
    private @Nullable ModelInstance preview;
    private @Nullable TaskHandle previewTimer;

    ModelTweakEditor(@NotNull Plugin plugin, @NotNull PluginModels models, @NotNull Model model,
                     @NotNull ModelOptions options) {
        this.plugin = plugin;
        this.models = models;
        this.model = model;
        this.options = options;
        this.tasks = Tasks.of(plugin);
        ModelTweaks tweaks = options.tweaks();
        this.scale = options.scale();
        this.offsetX = tweaks.offsetX();
        this.offsetY = tweaks.offsetY();
        this.offsetZ = tweaks.offsetZ();
        this.speeds = new LinkedHashMap<>(tweaks.speeds());
        this.width = tweaks.hitboxWidth();
        this.height = tweaks.hitboxHeight();

        List<Row> rows = new ArrayList<>();
        tweaks.bones().forEach((bone, tweak) -> rows.add(new Row(bone, tweak)));
        this.list = Editors.of(plugin).list(new Bones(), Row.class, rows)
                .title(Phrases.tr("{primary}&lMODEL TWEAKS {letters_black}» {highlight}{0}", model.name()))
                .button(EditorButton.<Row>of("SLIME_BALL")
                        .name(Phrases.tr("{primary}&lSIZE & PLACE"))
                        .lore("", Phrases.tr(" {letters_black}▎ {letters}How big it is drawn, and where it"),
                                Phrases.tr(" {letters_black}▎ {letters}sits on the mob that wears it."), "",
                                Phrases.tr("{warning}➥ Click to change them"), "")
                        .onClick(view -> view.ask(() -> place(view.viewer())))
                        .build())
                .button(EditorButton.<Row>of("CLOCK")
                        .name(Phrases.tr("{primary}&lSPEEDS"))
                        .lore("", Phrases.tr(" {letters_black}▎ {letters}How fast each state plays, as a"),
                                Phrases.tr(" {letters_black}▎ {letters}multiple of the pace it was animated at."), "",
                                Phrases.tr("{warning}➥ Click to change them"), "")
                        .onClick(view -> view.ask(() -> speeds(view.viewer())))
                        .build())
                .button(EditorButton.<Row>of("SHIELD")
                        .name(Phrases.tr("{primary}&lHITBOX"))
                        .lore("", Phrases.tr(" {letters_black}▎ {letters}The box it is hit by. Zero works it"),
                                Phrases.tr(" {letters_black}▎ {letters}out from the model's own size."), "",
                                Phrases.tr("{warning}➥ Click to change it"), "")
                        .onClick(view -> view.ask(() -> hitbox(view.viewer())))
                        .build())
                .button(EditorButton.<Row>of("ARMOR_STAND")
                        .name(Phrases.tr("{success}&lPREVIEW"))
                        .lore("", Phrases.tr(" {letters_black}▎ {letters}Puts it in front of you for 30s ⌚,"),
                                Phrases.tr(" {letters_black}▎ {letters}turning and playing every animation."), "",
                                Phrases.tr("{warning}➥ Click to see it as it stands"), "")
                        .glowing()
                        .onClick(this::preview)
                        .build())
                .onSave(edited -> {
                    stopPreview();
                    onSave.accept(result(edited));
                })
                .onCancel(() -> {
                    stopPreview();
                    onCancel.run();
                });
    }

    /** The window title; the model's name by default. */
    public @NotNull ModelTweakEditor title(@NotNull String title) {
        list.title(title);
        return this;
    }

    /** Told the options with the edited size and tweaks, on the viewer's thread. */
    public @NotNull ModelTweakEditor onSave(@NotNull Consumer<ModelOptions> onSave) {
        this.onSave = Objects.requireNonNull(onSave, "onSave");
        return this;
    }

    /** Told the editor ended with nothing kept: cancel, a close, leaving, the plugin disabling. */
    public @NotNull ModelTweakEditor onCancel(@NotNull Runnable onCancel) {
        this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
        return this;
    }

    /** Opens it, from any thread. */
    public void open(@NotNull Player viewer) {
        list.open(viewer);
    }

    // ------------------------------------------------------------------ what it saves

    private ModelOptions result(List<Row> rows) {
        Map<String, ModelTweaks.Bone> bones = new LinkedHashMap<>();
        for (Row row : rows) {
            if (!row.bone().isEmpty()) bones.put(row.bone(), row.tweak());
        }
        return options.withScale(scale).withTweaks(new ModelTweaks(offsetX, offsetY, offsetZ, bones, speeds,
                width, height));
    }

    // ------------------------------------------------------------------ the buttons

    private CompletionStage<Optional<Boolean>> place(Player viewer) {
        return EditorForm.of(plugin, viewer, Phrases.tr("{primary}&lSIZE & PLACE"))
                .decimal(SCALE, Phrases.tr("Size, 1 as modelled"), decimal(scale))
                .hint(Phrases.tr("It grows with the mob's own scale too. 0.1 to 16."))
                .decimal(OFFSET_X, Phrases.tr("Moved right, in blocks"), decimal(offsetX))
                .decimal(OFFSET_Y, Phrases.tr("Moved up, in blocks"), decimal(offsetY))
                .decimal(OFFSET_Z, Phrases.tr("Moved back, in blocks"), decimal(offsetZ))
                .hint(Phrases.tr("Its own right, up and back, before it turns with the mob. -8 to 8."))
                .ask(values -> {
                    scale = Math.clamp(read(values, SCALE, scale), 0.1, 16);
                    offsetX = Math.clamp(read(values, OFFSET_X, offsetX), -ModelTweaks.MAX_OFFSET, ModelTweaks.MAX_OFFSET);
                    offsetY = Math.clamp(read(values, OFFSET_Y, offsetY), -ModelTweaks.MAX_OFFSET, ModelTweaks.MAX_OFFSET);
                    offsetZ = Math.clamp(read(values, OFFSET_Z, offsetZ), -ModelTweaks.MAX_OFFSET, ModelTweaks.MAX_OFFSET);
                    return true;
                });
    }

    private CompletionStage<Optional<Boolean>> speeds(Player viewer) {
        EditorForm form = EditorForm.of(plugin, viewer, Phrases.tr("{primary}&lSPEEDS"));
        Map<String, FormKey<BigDecimal>> keys = new LinkedHashMap<>();
        for (String state : ModelState.ALL) {
            FormKey<BigDecimal> key = FormKey.decimal("speed_" + state);
            keys.put(state, key);
            form.decimal(key, Phrases.tr("{0}, times its own pace", state), decimal(speeds.getOrDefault(state, 1.0)));
        }
        form.hint(Phrases.tr("1 plays it as animated; 0.1 to 10."));
        return form.ask(values -> {
            keys.forEach((state, key) -> {
                double speed = read(values, key, speeds.getOrDefault(state, 1.0));
                if (Math.abs(speed - 1) < 1e-9) {
                    speeds.remove(state);
                } else {
                    speeds.put(state, Math.clamp(speed, 0.1, 10));
                }
            });
            return true;
        });
    }

    private CompletionStage<Optional<Boolean>> hitbox(Player viewer) {
        return EditorForm.of(plugin, viewer, Phrases.tr("{primary}&lHITBOX"))
                .decimal(WIDTH, Phrases.tr("Width, in blocks"), decimal(width))
                .decimal(HEIGHT, Phrases.tr("Height, in blocks"), decimal(height))
                .hint(Phrases.tr("0 for either works the boxes out from the model. Up to 16."))
                .ask(values -> {
                    width = Math.clamp(read(values, WIDTH, width), 0, ModelTweaks.MAX_HITBOX);
                    height = Math.clamp(read(values, HEIGHT, height), 0, ModelTweaks.MAX_HITBOX);
                    return true;
                });
    }

    // ------------------------------------------------------------------ preview

    /** The model in front of the viewer as the edits stand, turning, playing each animation in turn. */
    private void preview(EditorView<Row> view) {
        stopPreview();
        Player viewer = view.viewer();
        Location eyes = viewer.getLocation();
        Vector ahead = eyes.getDirection().setY(0);
        if (ahead.lengthSquared() < 1e-6) ahead = new Vector(0, 0, 1);
        Location spot = eyes.clone().add(ahead.normalize().multiply(3));
        if (!tasks.isOwnedBy(spot)) spot = eyes.clone();
        spot.setYaw(eyes.getYaw() + 180);
        spot.setPitch(0);
        ArmorStand placed = spot.getWorld().spawn(spot, ArmorStand.class, body -> {
            body.setInvisible(true);
            body.setMarker(true);
            body.setGravity(false);
            body.setInvulnerable(true);
            body.setPersistent(false);
            body.setSilent(true);
        });
        ModelInstance live = models.attach(placed, model, result(view.entries()).withNametag(false));
        if (live == null) {
            placed.remove();
            return;
        }
        stand = placed;
        preview = live;
        List<String> animations = new ArrayList<>(model.animations());
        long[] age = {0};
        previewTimer = tasks.runAtEntityTimer(placed, 1L, 1L, () -> {
            age[0]++;
            if (age[0] >= PREVIEW_TICKS || !placed.isValid()) {
                stopPreview();
                return;
            }
            placed.setRotation(placed.getLocation().getYaw() + 2f, 0);
            if (!animations.isEmpty() && age[0] % PREVIEW_STEP == 0) {
                live.play(animations.get((int) (age[0] / PREVIEW_STEP - 1) % animations.size()));
            }
        });
    }

    private void stopPreview() {
        TaskHandle timer = previewTimer;
        ModelInstance live = preview;
        ArmorStand placed = stand;
        previewTimer = null;
        preview = null;
        stand = null;
        if (timer != null) timer.cancel();
        if (live != null) live.remove();
        if (placed != null) {
            if (tasks.isOwnedBy(placed)) {
                placed.remove();
            } else {
                tasks.runAtEntity(placed, placed::remove);
            }
        }
    }

    // ------------------------------------------------------------------ rows

    /** The bones it changes, as rows. */
    private final class Bones implements EditorDescriptor<Row> {

        @Override
        public @NotNull String label(@NotNull Row row) {
            return row.bone().isEmpty() ? Phrases.tr("{muted}No bone") : "{primary}&l" + row.bone().toUpperCase(Locale.ROOT);
        }

        @Override
        public @NotNull String icon(@NotNull Row row) {
            if (row.tweak().hidden()) return "BARRIER";
            Material material = row.tweak().material().isEmpty() ? null : Material.matchMaterial(row.tweak().material());
            return material != null && material.isItem() ? material.name() : "BONE";
        }

        @Override
        public @NotNull List<String> lore(@NotNull Row row) {
            String drawn = row.tweak().hidden() ? Phrases.tr("{error}hidden")
                    : row.tweak().material().isEmpty() ? Phrases.tr("its own look")
                    : "{highlight}" + row.tweak().material();
            return List.of(Phrases.tr("{secondary}Bone:"),
                    " {letters_black}▎ {letters}" + Phrases.tr("Drawn") + " {letters_black}» {info}" + drawn,
                    Phrases.tr(" {letters_black}▎ {letters}Every bone below it follows."));
        }

        @Override
        public @NotNull Row create() {
            List<String> bones = model.bones();
            return new Row(bones.isEmpty() ? "" : bones.get(0).toLowerCase(Locale.ROOT), ModelTweaks.Bone.HIDDEN);
        }

        @Override
        public @NotNull Row copy(@NotNull Row row) {
            return new Row(row.bone(), row.tweak());
        }

        @Override
        public @NotNull String typeKey() {
            return "exylia:model-bones";
        }

        @Override
        public boolean isComplete(@NotNull Row row) {
            if (row.bone().isEmpty() || !row.tweak().changes()) return false;
            return row.tweak().material().isEmpty() || Material.matchMaterial(row.tweak().material()) != null;
        }

        @Override
        public @NotNull CompletionStage<Optional<Row>> create(@NotNull Player viewer) {
            return edit(viewer, create());
        }

        @Override
        public @NotNull CompletionStage<Optional<Row>> edit(@NotNull Player viewer, @NotNull Row row) {
            List<FormField.Option> options = new ArrayList<>();
            for (String bone : model.bones()) {
                String key = bone.toLowerCase(Locale.ROOT);
                options.add(new FormField.Option(key, bone));
            }
            if (options.isEmpty()) options.add(new FormField.Option("", Phrases.tr("{muted}No bone")));
            return EditorForm.of(plugin, viewer, Phrases.tr("{primary}&lBONE"))
                    .choice(BONE, Phrases.tr("Bone"), row.bone(), options)
                    .flag(HIDDEN, Phrases.tr("Hidden"), row.tweak().hidden())
                    .field(MATERIAL, FormField.text(MATERIAL, Phrases.tr("Drawn in")).defaultValue(row.tweak().material())
                            .optional())
                    .hint(Phrases.tr("A block or item, such as gold_block. Blank keeps its own look."))
                    .ask(values -> new Row(values.getOr(BONE, row.bone()),
                            new ModelTweaks.Bone(values.getOr(HIDDEN, row.tweak().hidden()),
                                    values.getOr(MATERIAL, ""))));
        }
    }

    // ------------------------------------------------------------------ numbers

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static double read(FormValues values, FormKey<BigDecimal> key, double current) {
        return values.has(key) ? values.get(key).doubleValue() : current;
    }
}
