package net.exylia.lib.util.reward;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * How a reward draws and edits itself on screen.
 *
 * <p>Handed to the list editor by {@link PluginRewards#editor}; a plugin does
 * not normally construct one.
 *
 * <h2>Creating a reward is a question</h2>
 * What a reward gives decides what its form can even ask, so the type is chosen
 * first and the form is built around the answer. A command reward is never asked
 * for a stack size, and an item reward is never asked for a currency.
 *
 * @since 1.56.0
 */
public final class RewardDescriptor implements EditorDescriptor<RewardEntry> {

    /** The clipboard bucket rewards share, whichever plugin's editor copied them. */
    public static final String TYPE_KEY = "exylia:rewards";

    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<Boolean> ICON = FormKey.flag("icon");
    private static final FormKey<String> PAYLOAD = FormKey.text("payload");
    private static final FormKey<String> CURRENCY = FormKey.text("currency");
    private static final FormKey<Long> MINIMUM = FormKey.integer("minimum");
    private static final FormKey<Long> MAXIMUM = FormKey.integer("maximum");
    private static final FormKey<BigDecimal> CHANCE = FormKey.decimal("chance");
    private static final FormKey<BigDecimal> WEIGHT = FormKey.decimal("weight");
    private static final FormKey<String> PERMISSION = FormKey.text("permission");
    private static final FormKey<String> CONDITION = FormKey.text("condition");
    private static final FormKey<String> MESSAGE = FormKey.text("message");

    /** Said under the command field, where the wrong guess fails silently. */
    private static final String COMMAND_HINT = "%player_name% is the player, no leading slash";

    /** Said under the fields the player reads, which take markup as well. */
    private static final String TEXT_HINT = "%player_name% and colour codes work";

    private final Plugin plugin;

    RewardDescriptor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String label(@NotNull RewardEntry entry) {
        return "{primary}&l" + entry.displayName().toUpperCase(Locale.ROOT);
    }

    @Override
    public @NotNull String icon(@NotNull RewardEntry entry) {
        return entry.resolvedIcon();
    }

    @Override
    public @NotNull List<String> lore(@NotNull RewardEntry entry) {
        return lore(entry, List.of(entry));
    }

    /**
     * The same, plus what this reward is worth against the others.
     *
     * <p>A chance read alone lies by omission: twenty rewards at forty percent
     * and three rewards at forty percent are the same row and a different table.
     * The extra line is each reward's share of everything the list hands out,
     * which is the number an admin is actually balancing.
     */
    @Override
    public @NotNull List<String> lore(@NotNull RewardEntry entry,
                                      @NotNull List<RewardEntry> siblings) {
        List<String> lore = new ArrayList<>(9);
        lore.add("{secondary}Reward:");
        lore.add(" {letters_black}▎ {letters}Gives {letters_black}» {info}" + readable(entry.type()));
        lore.add(" {letters_black}▎ {letters}Value {letters_black}» {highlight}" + entry.preview());
        lore.add("");
        lore.add("{secondary}Odds:");
        lore.add(" {letters_black}▎ {letters}Chance {letters_black}» " + chance(entry));
        lore.add(" {letters_black}▎ {letters}Weight 🎲 {letters_black}» {info}" + number(entry.weight()));
        String share = share(entry.chance(), siblings);
        if (share != null) {
            lore.add(" {letters_black}▎ {letters}Real {letters_black}» {success}" + share
                    + "% {muted}of all drops");
        }
        if (entry.permission() != null || entry.condition() != null) {
            lore.add("");
            lore.add("{secondary}Only for:");
            if (entry.permission() != null) {
                lore.add(" {letters_black}▎ {letters}Permission {letters_black}» {info}" + entry.permission());
            }
            if (entry.condition() != null) {
                lore.add(" {letters_black}▎ {letters}Condition {letters_black}» {info}" + entry.condition());
            }
        }
        return lore;
    }

    @Override
    public @NotNull RewardEntry create() {
        return RewardEntry.of(RewardType.COMMAND).build();
    }

    /** Asks what the reward gives, then opens the form that fits the answer. */
    @Override
    public @NotNull CompletionStage<Optional<RewardEntry>> create(@NotNull Player viewer) {
        return Inputs.of(plugin).choice(viewer, "{primary}&lWHAT DOES IT GIVE?",
                        List.of(RewardType.values()))
                .label(type -> "{primary}&l" + readable(type).toUpperCase(Locale.ROOT))
                .icon(RewardDescriptor::iconOf)
                .key(Enum::name)
                .open()
                .thenApply(result -> result.completed()
                        ? Optional.of(RewardEntry.of(result.value()).build())
                        : Optional.empty());
    }

    @Override
    public @NotNull RewardEntry copy(@NotNull RewardEntry entry) {
        return entry.copy();
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    /**
     * Whether the reward has the one thing its type needs.
     *
     * <p>A command reward with no command is a row that looks configured and
     * does nothing when it is earned.
     */
    @Override
    public boolean isComplete(@NotNull RewardEntry entry) {
        return switch (entry.type()) {
            case COMMAND -> notBlank(entry.command());
            case ITEM -> notBlank(entry.itemSnapshot());
            case MESSAGE -> notBlank(entry.message());
            case ECONOMY, EXPERIENCE, POTION -> notBlank(entry.value());
        };
    }

    /**
     * One window with every field this reward's type has.
     *
     * <p>An item reward is a special case and only one: an item is picked, not
     * typed, so the icon picker comes first and the form follows with the rest.
     */
    @Override
    public @NotNull CompletionStage<Optional<RewardEntry>> edit(@NotNull Player viewer,
                                                                @NotNull RewardEntry entry) {
        if (entry.type() == RewardType.ITEM) {
            return Inputs.of(plugin).icon(viewer, "{primary}&lWHAT ITEM?")
                    .open()
                    .thenCompose(icon -> icon.completed()
                            ? form(viewer, entry.toBuilder().itemSnapshot(icon.value()).build())
                            : CompletableFuture.completedFuture(Optional.<RewardEntry>empty()));
        }
        return form(viewer, entry);
    }

    private CompletionStage<Optional<RewardEntry>> form(Player viewer, RewardEntry entry) {
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lEDIT REWARD")
                .text(NAME, "Display name", entry.name(), 2)
                .flag(ICON, "Change the icon", false)
                .hint(iconHint(entry));

        boolean payload = entry.type() != RewardType.ITEM;
        if (payload) {
            form.text(PAYLOAD, payloadLabel(entry.type()), payloadOf(entry), payloadLines(entry.type()))
                    .hint(payloadHint(entry.type()));
        }
        if (entry.type() == RewardType.ECONOMY) {
            form.text(CURRENCY, "Currency (blank for the default)", entry.currency());
        }
        boolean counted = entry.type() == RewardType.ITEM || entry.type() == RewardType.EXPERIENCE;
        if (counted) {
            form.integer(MINIMUM, "Least amount", low(entry))
                    .integer(MAXIMUM, "Most amount", high(entry));
        }
        form.decimal(CHANCE, "Chance out of 100", BigDecimal.valueOf(entry.chance()))
                .decimal(WEIGHT, "Weight against its siblings", BigDecimal.valueOf(entry.weight()))
                .text(PERMISSION, "Permission needed (blank for none)", entry.permission())
                .text(CONDITION, "Condition (blank for none)", entry.condition(), 2)
                .text(MESSAGE, "Message when it lands (blank for none)", entry.deliveryMessage(), 3)
                .hint(TEXT_HINT);

        boolean withPayload = payload;
        boolean withAmounts = counted;
        boolean withCurrency = entry.type() == RewardType.ECONOMY;
        return form.<Draft>ask(values -> new Draft(
                        rebuild(entry, values, withPayload, withAmounts, withCurrency),
                        values.getBoolean(ICON)))
                .thenCompose(draft -> draft.isEmpty()
                        ? CompletableFuture.completedFuture(Optional.<RewardEntry>empty())
                        : draft.get().pick()
                                ? pickIcon(viewer, draft.get().entry())
                                : CompletableFuture.completedFuture(Optional.of(draft.get().entry())));
    }

    /** What the form answered, and whether the icon question follows it. */
    private record Draft(RewardEntry entry, boolean pick) {
    }

    /**
     * Asks what the reward is drawn as.
     *
     * <p>Asked after the form and only when it was asked for: an icon is picked,
     * not typed, so it cannot be a field, and a picker in front of every edit is
     * a window nobody wanted. A cancelled pick keeps the rest of the edit rather
     * than throwing the form away.
     */
    private CompletionStage<Optional<RewardEntry>> pickIcon(Player viewer, RewardEntry entry) {
        return Inputs.of(plugin).icon(viewer, "{primary}&lWHAT ICON?")
                .open()
                .thenApply(result -> Optional.of(result.completed()
                        ? entry.toBuilder().icon(result.value()).build()
                        : entry));
    }

    /** What the reward is drawn as now, said in a line a tooltip can hold. */
    private static String iconHint(RewardEntry entry) {
        String icon = entry.resolvedIcon();
        String now = icon.length() > 32
                ? "a custom item"
                : icon.toLowerCase(Locale.ROOT).replace('_', ' ');
        return "now " + now + "; a picker opens after submitting";
    }

    private static RewardEntry rebuild(RewardEntry entry, FormValues values,
                                       boolean payload, boolean amounts, boolean currency) {
        RewardEntry.Builder builder = entry.toBuilder()
                .name(blankToNull(values.getText(NAME)))
                .chance(values.getDecimal(CHANCE).doubleValue())
                .weight(values.getDecimal(WEIGHT).doubleValue())
                .permission(blankToNull(values.getText(PERMISSION)))
                .condition(blankToNull(values.getText(CONDITION)))
                .deliveryMessage(blankToNull(values.getText(MESSAGE)));

        if (payload) {
            String written = values.getText(PAYLOAD);
            switch (entry.type()) {
                case COMMAND -> builder.command(blankToNull(written));
                case MESSAGE -> builder.message(blankToNull(written));
                default -> builder.value(blankToNull(written));
            }
        }
        if (currency) {
            builder.currency(blankToNull(values.getText(CURRENCY)));
        }
        if (amounts) {
            int least = (int) Math.max(1, values.getLong(MINIMUM));
            int most = (int) Math.max(1, values.getLong(MAXIMUM));
            if (least == most) {
                builder.fixedAmount(least);
            } else {
                builder.amountBetween(least, most);
            }
        }
        return builder.build();
    }

    // ------------------------------------------------------------------

    private static String payloadLabel(RewardType type) {
        return switch (type) {
            case COMMAND -> "Command the console runs";
            case MESSAGE -> "Message to send";
            case ECONOMY -> "How much money";
            case EXPERIENCE -> "How much experience";
            case POTION -> "Effect, as SPEED:1:300";
            case ITEM -> "Item";
        };
    }

    /**
     * What a valid payload looks like, for the fields where the label leaves it
     * open. A command that guesses {@code %player%} runs, does nothing, and is
     * only found much later; the note costs a line and saves that.
     */
    private static String payloadHint(RewardType type) {
        return switch (type) {
            case COMMAND -> COMMAND_HINT;
            case MESSAGE -> TEXT_HINT;
            default -> null;
        };
    }

    /** Commands and messages carry markup and placeholders; amounts do not. */
    private static int payloadLines(RewardType type) {
        return switch (type) {
            case COMMAND, MESSAGE -> 3;
            default -> 1;
        };
    }

    private static String payloadOf(RewardEntry entry) {
        return switch (entry.type()) {
            case COMMAND -> entry.command();
            case MESSAGE -> entry.message();
            default -> entry.value();
        };
    }

    private static long low(RewardEntry entry) {
        return entry.isRanged() ? entry.minAmount() : Math.max(1, entry.itemAmount());
    }

    private static long high(RewardEntry entry) {
        return entry.isRanged() ? entry.maxAmount() : Math.max(1, entry.itemAmount());
    }

    private static String chance(RewardEntry entry) {
        return entry.isGuaranteed()
                ? "{success}always"
                : "{highlight}" + number(entry.chance()) + "%";
    }

    /**
     * A reward's share of everything the list gives out, as a percentage.
     *
     * <p>Rewards roll independently, so a reward's chance is its expected count
     * and the shares are those counts normalised. Nothing is drawn for a list of
     * one &mdash; the answer is always a hundred percent &mdash; nor for a list
     * nothing can ever drop out of.
     *
     * @return the share, or {@code null} when there is no useful one
     */
    private static String share(double chance, List<RewardEntry> siblings) {
        if (siblings.size() < 2) {
            return null;
        }
        double total = 0.0;
        for (RewardEntry sibling : siblings) {
            total += Math.max(0.0, sibling.chance());
        }
        if (total <= 0.0) {
            return null;
        }
        return number(Math.round(Math.max(0.0, chance) * 1000.0 / total) / 10.0);
    }

    private static String number(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String readable(RewardType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private static Material iconOf(RewardType type) {
        Material material = Material.matchMaterial(type.defaultIcon());
        return material == null ? Material.PAPER : material;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
