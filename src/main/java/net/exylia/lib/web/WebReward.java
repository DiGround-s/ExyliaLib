package net.exylia.lib.web;

import net.exylia.lib.config.Comment;
import net.exylia.lib.util.reward.RewardEntry;
import net.exylia.lib.util.reward.RewardType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One reward as the web editor shows it: the form of a {@link RewardEntry}, minus what only the game
 * can set (an item reward's item). Use it as a record component of an editor's value type; the
 * helpers convert whole lists and check them.
 *
 * <p>Entries that stay keep their hidden fields (item snapshots and the like) because the id the
 * browser sends back finds them in the list the edit started from.
 *
 * @since 1.646.0
 */
public record WebReward(
        @Comment("Keeps the reward's identity, and with it an item reward's item; empty gets a new one.")
        String id,
        @Comment("COMMAND, MESSAGE, ECONOMY, EXPERIENCE or POTION. ITEM rewards are added in game.")
        RewardType type,
        @Comment("COMMAND: run from the console, without the slash; %player% is the player.")
        String command,
        @Comment("ECONOMY and EXPERIENCE: the amount. POTION: EFFECT|LEVEL|SECONDS.")
        String value,
        @Comment("ECONOMY: the currency; empty is the default one.")
        String currency,
        @Comment("MESSAGE: the text sent.")
        String message,
        @Comment("Chance in percent; 100 always gives it.")
        double chance,
        @Comment("Only players with this permission get it; empty is everyone.")
        String permission,
        String name) {

    /** A new, empty command reward: what the browser's "add" starts from. */
    public WebReward() {
        this("", RewardType.COMMAND, "", "", "", "", RewardEntry.ALWAYS, "", "");
    }

    public static @NotNull WebReward of(@NotNull RewardEntry entry) {
        return new WebReward(entry.id(), entry.type(), text(entry.command()), text(entry.value()),
                text(entry.currency()), text(entry.message()), entry.chance(), text(entry.permission()),
                text(entry.name()));
    }

    /** The rewards of a list, in order. */
    public static @NotNull List<WebReward> fromEntries(@NotNull List<RewardEntry> entries) {
        return entries.stream().map(WebReward::of).toList();
    }

    /**
     * The entries the edited list stands for.
     *
     * @param before the list the edit started from; a reward whose id and type match one there keeps its
     *               other fields (an item snapshot included)
     */
    public static @NotNull List<RewardEntry> toEntries(@Nullable List<WebReward> rewards, @NotNull List<RewardEntry> before) {
        Map<String, RewardEntry> kept = byId(before);
        List<RewardEntry> out = new ArrayList<>();
        if (rewards != null) rewards.forEach(reward -> out.add(reward.toEntry(kept)));
        return List.copyOf(out);
    }

    /**
     * What is wrong with an edited list, each problem on its field ({@code at.2.value}).
     *
     * @param at     the list's path
     * @param before the list the edit started from; an ITEM reward is valid only if it is still there
     */
    public static @NotNull List<Problem> problems(@NotNull String at, @Nullable List<WebReward> rewards,
                                                  @NotNull List<RewardEntry> before) {
        Map<String, RewardEntry> kept = byId(before);
        List<Problem> problems = new ArrayList<>();
        List<WebReward> list = rewards == null ? List.of() : rewards;
        for (int i = 0; i < list.size(); i++) {
            WebReward reward = list.get(i);
            String path = at + "." + i;
            if (reward.type() == null) {
                problems.add(Problem.at(path + ".type", "a reward has no type"));
                continue;
            }
            switch (reward.type()) {
                case ITEM -> {
                    RewardEntry item = kept.get(reward.id());
                    if (item == null || item.type() != RewardType.ITEM || item.itemSnapshot() == null) {
                        problems.add(Problem.at(path + ".type", "item rewards are added in game; '" + reward.id()
                                + "' is not one already there"));
                    }
                }
                case COMMAND -> {
                    if (text(reward.command()).isBlank()) {
                        problems.add(Problem.at(path + ".command", "a command reward has no command"));
                    }
                }
                case MESSAGE -> {
                    if (text(reward.message()).isBlank()) {
                        problems.add(Problem.at(path + ".message", "a message reward has no message"));
                    }
                }
                case ECONOMY, EXPERIENCE -> {
                    try {
                        double amount = Double.parseDouble(text(reward.value()).trim());
                        if (!(amount > 0) || Double.isInfinite(amount)) {
                            problems.add(Problem.at(path + ".value", "must be above 0"));
                        }
                    } catch (NumberFormatException notANumber) {
                        problems.add(Problem.at(path + ".value", "needs a number"));
                    }
                }
                case POTION -> {
                    if (text(reward.value()).isBlank()) {
                        problems.add(Problem.at(path + ".value", "needs EFFECT|LEVEL|SECONDS"));
                    }
                }
            }
            if (!(reward.chance() >= 0 && reward.chance() <= 100)) {
                problems.add(Problem.at(path + ".chance", "0 to 100"));
            }
        }
        return problems;
    }

    private RewardEntry toEntry(Map<String, RewardEntry> before) {
        RewardEntry kept = before.get(id);
        RewardEntry.Builder builder = kept != null && kept.type() == type
                ? kept.toBuilder()
                : RewardEntry.of(type).id(id == null || id.isBlank() ? UUID.randomUUID().toString() : id);
        return builder.command(orNull(command)).value(orNull(value)).currency(orNull(currency))
                .message(orNull(message)).chance(chance).permission(orNull(permission)).name(orNull(name))
                .build();
    }

    private static Map<String, RewardEntry> byId(List<RewardEntry> entries) {
        Map<String, RewardEntry> out = new LinkedHashMap<>();
        entries.forEach(entry -> out.put(entry.id(), entry));
        return out;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String orNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
