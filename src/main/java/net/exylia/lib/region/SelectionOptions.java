package net.exylia.lib.region;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * How a block selection looks and behaves.
 *
 * <pre>{@code
 * SelectionOptions arena = SelectionOptions.builder()
 *         .selectorName("{primary}&lARENA SELECTOR")
 *         .previewParticle("FLAME")
 *         .build();
 * }</pre>
 *
 * <h2>The defaults are the ExyliaCommons selector, not WorldEdit's</h2>
 * A golden axe, handed to the player, drawing the box it is about to accept,
 * and waiting for a deliberate confirmation before it answers. Every one of
 * those was in ExyliaCommons and every one of them was lost in the first port:
 * a wooden axe nobody was given is indistinguishable from WorldEdit's wand, and
 * a selection that completes the instant the second corner is clicked gives an
 * admin no chance to look at what they picked.
 *
 * <p>Each is a switch rather than a rule. A plugin that draws its own outline
 * turns the preview off; one asking for a corner inside its own flow turns the
 * confirmation off and answers on the second click.
 *
 * @since 1.23.0
 */
public final class SelectionOptions {

    /** The material ExyliaCommons handed out, and the one handed out here. */
    public static final Material DEFAULT_SELECTOR = Material.GOLDEN_AXE;

    /** The particle an outline is drawn with while a selection is open. */
    public static final String DEFAULT_PREVIEW_PARTICLE = "END_ROD";

    /** How far apart the outline's points are, in blocks. */
    public static final double DEFAULT_PREVIEW_SPACING = 1.0;

    /** Ticks between outline frames: four a second, cheap and not strobing. */
    public static final long DEFAULT_PREVIEW_PERIOD_TICKS = 5L;

    /** What the handed-out selector is called. */
    public static final String DEFAULT_SELECTOR_NAME = "{primary}&lREGION SELECTOR";

    /** What the handed-out selector says it does. */
    public static final List<String> DEFAULT_SELECTOR_LORE = List.of(
            "{secondary}Selection:",
            " {letters_black}▎ {letters}Left-click {letters_black}» {success}first corner",
            " {letters_black}▎ {letters}Right-click {letters_black}» {error}second corner",
            "",
            "{warning}➥ Shift + left-click to confirm");

    /** Shared default options. */
    public static final SelectionOptions DEFAULT = builder().build();

    private final Material selectorMaterial;
    private final boolean cancelInteractions;
    private final boolean requireSameWorld;
    private final boolean giveSelector;
    private final boolean requireConfirmation;
    private final boolean feedback;
    private final String previewParticle;
    private final double previewSpacing;
    private final long previewPeriodTicks;
    private final String selectorName;
    private final List<String> selectorLore;

    private SelectionOptions(Builder builder) {
        this.selectorMaterial = builder.selectorMaterial;
        this.cancelInteractions = builder.cancelInteractions;
        this.requireSameWorld = builder.requireSameWorld;
        this.giveSelector = builder.giveSelector;
        this.requireConfirmation = builder.requireConfirmation;
        this.feedback = builder.feedback;
        this.previewParticle = builder.previewParticle;
        this.previewSpacing = builder.previewSpacing;
        this.previewPeriodTicks = builder.previewPeriodTicks;
        this.selectorName = builder.selectorName;
        this.selectorLore = builder.selectorLore;
    }

    /** The default options: a golden axe, given, previewed and confirmed. */
    public SelectionOptions() {
        this(builder());
    }

    /**
     * The three settings this class had before it grew the rest.
     *
     * <p>Everything else takes its default, so a caller that only ever cared
     * about the material still compiles and still gets the better selector.
     *
     * @param selectorMaterial   what identifies a selecting click
     * @param cancelInteractions whether a handled click is cancelled
     * @param requireSameWorld   whether both corners must share a world
     */
    public SelectionOptions(@NotNull Material selectorMaterial,
                            boolean cancelInteractions,
                            boolean requireSameWorld) {
        this(builder()
                .selectorMaterial(selectorMaterial)
                .cancelInteractions(cancelInteractions)
                .requireSameWorld(requireSameWorld));
    }

    /** The shared default options. */
    public static @NotNull SelectionOptions defaults() {
        return DEFAULT;
    }

    /** A builder holding the defaults. */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** A builder holding these options' values. */
    public @NotNull Builder toBuilder() {
        Builder builder = new Builder();
        builder.selectorMaterial = selectorMaterial;
        builder.cancelInteractions = cancelInteractions;
        builder.requireSameWorld = requireSameWorld;
        builder.giveSelector = giveSelector;
        builder.requireConfirmation = requireConfirmation;
        builder.feedback = feedback;
        builder.previewParticle = previewParticle;
        builder.previewSpacing = previewSpacing;
        builder.previewPeriodTicks = previewPeriodTicks;
        builder.selectorName = selectorName;
        builder.selectorLore = selectorLore;
        return builder;
    }

    /** The material a click must be made with to select. */
    public @NotNull Material selectorMaterial() {
        return selectorMaterial;
    }

    /** Whether a click this session handled is cancelled rather than played out. */
    public boolean cancelInteractions() {
        return cancelInteractions;
    }

    /** Whether both corners must belong to the same world. */
    public boolean requireSameWorld() {
        return requireSameWorld;
    }

    /**
     * Whether the player is handed the selector when the session starts.
     *
     * <p>It goes into the <em>main hand</em> and is taken back when the session
     * ends, however it ends. Whatever was in that hand moves to a free slot, and
     * is lost when there is no free slot: the selector being where the player is
     * already looking is worth more than a stack an admin can get back.
     *
     * @since 1.56.0
     */
    public boolean giveSelector() {
        return giveSelector;
    }

    /**
     * Whether both corners are a proposal that still has to be confirmed.
     *
     * <p>With this on — the default — the second corner puts the session into
     * {@link SelectionState#AWAITING_CONFIRMATION} and a shift + left-click
     * accepts it. Either corner can be re-clicked until then.
     *
     * @since 1.56.0
     */
    public boolean requireConfirmation() {
        return requireConfirmation;
    }

    /**
     * Whether the library tells the player what it just recorded.
     *
     * <p>Corner coordinates, the volume, and what to press to confirm. Off for
     * a plugin that words all of that itself.
     *
     * @since 1.56.0
     */
    public boolean feedback() {
        return feedback;
    }

    /**
     * The particle the live outline is drawn with, or {@code null} for none.
     *
     * @since 1.56.0
     */
    public @Nullable String previewParticle() {
        return previewParticle;
    }

    /** Whether an outline is drawn at all. */
    public boolean hasPreview() {
        return previewParticle != null;
    }

    /**
     * Blocks between neighbouring outline points.
     *
     * @since 1.56.0
     */
    public double previewSpacing() {
        return previewSpacing;
    }

    /**
     * Ticks between outline frames.
     *
     * @since 1.56.0
     */
    public long previewPeriodTicks() {
        return previewPeriodTicks;
    }

    /**
     * What the handed-out selector is called, in Exylia text notation.
     *
     * @since 1.56.0
     */
    public @NotNull String selectorName() {
        return selectorName;
    }

    /**
     * The handed-out selector's lore, in Exylia text notation.
     *
     * @since 1.56.0
     */
    public @NotNull List<String> selectorLore() {
        return selectorLore;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof SelectionOptions options
                && selectorMaterial == options.selectorMaterial
                && cancelInteractions == options.cancelInteractions
                && requireSameWorld == options.requireSameWorld
                && giveSelector == options.giveSelector
                && requireConfirmation == options.requireConfirmation
                && feedback == options.feedback
                && Objects.equals(previewParticle, options.previewParticle)
                && Double.compare(previewSpacing, options.previewSpacing) == 0
                && previewPeriodTicks == options.previewPeriodTicks
                && selectorName.equals(options.selectorName)
                && selectorLore.equals(options.selectorLore);
    }

    @Override
    public int hashCode() {
        return Objects.hash(selectorMaterial, cancelInteractions, requireSameWorld, giveSelector,
                requireConfirmation, feedback, previewParticle, previewSpacing, previewPeriodTicks,
                selectorName, selectorLore);
    }

    @Override
    public String toString() {
        return "SelectionOptions[" + selectorMaterial
                + (giveSelector ? ", given" : "")
                + (requireConfirmation ? ", confirmed" : "")
                + (hasPreview() ? ", " + previewParticle : "")
                + ']';
    }

    /** Builds {@link SelectionOptions}. */
    public static final class Builder {

        private Material selectorMaterial = DEFAULT_SELECTOR;
        private boolean cancelInteractions = true;
        private boolean requireSameWorld = true;
        private boolean giveSelector = true;
        private boolean requireConfirmation = true;
        private boolean feedback = true;
        private String previewParticle = DEFAULT_PREVIEW_PARTICLE;
        private double previewSpacing = DEFAULT_PREVIEW_SPACING;
        private long previewPeriodTicks = DEFAULT_PREVIEW_PERIOD_TICKS;
        private String selectorName = DEFAULT_SELECTOR_NAME;
        private List<String> selectorLore = DEFAULT_SELECTOR_LORE;

        private Builder() {
        }

        /**
         * What a selecting click must be made with.
         *
         * @param selectorMaterial a non-air item material
         * @return this builder
         */
        public @NotNull Builder selectorMaterial(@NotNull Material selectorMaterial) {
            Objects.requireNonNull(selectorMaterial, "selectorMaterial");
            if (selectorMaterial == Material.AIR || selectorMaterial.name().endsWith("_AIR")
                    || !isItem(selectorMaterial)) {
                throw new IllegalArgumentException("Selector material must be a non-air item material");
            }
            this.selectorMaterial = selectorMaterial;
            return this;
        }

        /**
         * Whether a handled click is cancelled rather than played out.
         *
         * @param cancelInteractions whether to cancel
         * @return this builder
         */
        public @NotNull Builder cancelInteractions(boolean cancelInteractions) {
            this.cancelInteractions = cancelInteractions;
            return this;
        }

        /**
         * Whether both corners must belong to the same world.
         *
         * @param requireSameWorld whether to require it
         * @return this builder
         */
        public @NotNull Builder requireSameWorld(boolean requireSameWorld) {
            this.requireSameWorld = requireSameWorld;
            return this;
        }

        /**
         * Whether to hand the player the selector.
         *
         * @param giveSelector whether to give it
         * @return this builder
         */
        public @NotNull Builder giveSelector(boolean giveSelector) {
            this.giveSelector = giveSelector;
            return this;
        }

        /**
         * Whether the second corner is a proposal awaiting a confirmation.
         *
         * @param requireConfirmation whether to wait for one
         * @return this builder
         */
        public @NotNull Builder requireConfirmation(boolean requireConfirmation) {
            this.requireConfirmation = requireConfirmation;
            return this;
        }

        /**
         * Whether the library sends its own progress messages.
         *
         * @param feedback whether to send them
         * @return this builder
         */
        public @NotNull Builder feedback(boolean feedback) {
            this.feedback = feedback;
            return this;
        }

        /**
         * The particle the live outline is drawn with.
         *
         * @param previewParticle a particle name, or {@code null} to draw nothing
         * @return this builder
         */
        public @NotNull Builder previewParticle(@Nullable String previewParticle) {
            if (previewParticle != null && previewParticle.isBlank()) {
                throw new IllegalArgumentException("previewParticle must not be blank");
            }
            this.previewParticle = previewParticle;
            return this;
        }

        /**
         * Blocks between neighbouring outline points.
         *
         * @param previewSpacing a finite, positive distance
         * @return this builder
         */
        public @NotNull Builder previewSpacing(double previewSpacing) {
            if (!Double.isFinite(previewSpacing) || previewSpacing <= 0.0) {
                throw new IllegalArgumentException("previewSpacing must be finite and positive");
            }
            this.previewSpacing = previewSpacing;
            return this;
        }

        /**
         * Ticks between outline frames.
         *
         * @param previewPeriodTicks at least one tick
         * @return this builder
         */
        public @NotNull Builder previewPeriodTicks(long previewPeriodTicks) {
            if (previewPeriodTicks < 1L) {
                throw new IllegalArgumentException("previewPeriodTicks must be at least one");
            }
            this.previewPeriodTicks = previewPeriodTicks;
            return this;
        }

        /**
         * What the handed-out selector is called.
         *
         * @param selectorName a name in Exylia text notation
         * @return this builder
         */
        public @NotNull Builder selectorName(@NotNull String selectorName) {
            this.selectorName = Objects.requireNonNull(selectorName, "selectorName");
            return this;
        }

        /**
         * What the handed-out selector says it does.
         *
         * @param selectorLore lore lines in Exylia text notation
         * @return this builder
         */
        public @NotNull Builder selectorLore(@NotNull List<String> selectorLore) {
            this.selectorLore = List.copyOf(Objects.requireNonNull(selectorLore, "selectorLore"));
            return this;
        }

        /** The finished options. */
        public @NotNull SelectionOptions build() {
            return new SelectionOptions(this);
        }

        private static boolean isItem(Material material) {
            try {
                return material.isItem();
            } catch (LinkageError unavailableRegistry) {
                // Paper resolves item registries lazily. Unit environments do not install that
                // registry; a live server always returns from the authoritative branch above.
                return !material.isLegacy() && material != Material.WATER && material != Material.LAVA
                        && material != Material.FIRE && material != Material.SOUL_FIRE
                        && material != Material.NETHER_PORTAL && material != Material.END_PORTAL;
            }
        }
    }
}
