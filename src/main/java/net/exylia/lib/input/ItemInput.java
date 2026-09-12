package net.exylia.lib.input;

import net.exylia.lib.input.internal.InsertWindow;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Asks somebody for an item, and answers with the item.
 *
 * <pre>{@code
 * inputs.item(player, "{primary}&lWHAT IS SPENT")
 *       .open(stack -> recipe.setInput(stack));
 * }</pre>
 *
 * <p>The window has one slot. What is put in it is the answer, whole: its
 * stack size, its name, its lore, its model, its enchantments, whatever a
 * plugin wrote into its container. It is a clone, and the item the player lent
 * goes back to them on every ending — confirming, closing, logging out, the
 * plugin being disabled.
 *
 * <h2>Why this is not {@link IconInput}</h2>
 * An icon request answers with a {@code material} <em>value</em> — a string for
 * a column — and a plain item has no room in that string for a count, so nine
 * diamonds and one diamond are both {@code DIAMOND}. That is right for
 * something being drawn and wrong for something being <em>measured</em>: a
 * recipe's input, a price, a quantity somebody has to hold. This answers with
 * the stack, and the caller stores whichever parts of it matter.
 *
 * @since 1.149.0
 */
public final class ItemInput {

    private final PluginInputs inputs;
    private final Player player;
    private final String prompt;

    ItemInput(PluginInputs inputs, Player player, String prompt) {
        this.inputs = inputs;
        this.player = Inputs.require(player, "player");
        this.prompt = Inputs.requireText(prompt, "prompt");
    }

    /**
     * Asks, and completes once with whatever happened.
     *
     * <p>An empty slot ends as {@link InputOutcome#CANCELLED}: there is nothing
     * to answer with, and asking again is not what somebody who closed the
     * window meant.
     *
     * @return the item, or how the request ended
     */
    public @NotNull CompletionStage<InputResult<ItemStack>> open() {
        return InsertWindow.openForItem(inputs.plugin(), player, prompt)
                .thenApply(inserted -> inserted
                        .map(InputResult::completed)
                        .orElseGet(() -> InputResult.ended(InputOutcome.CANCELLED)));
    }

    /**
     * The same, running an action only when an item was given.
     *
     * @param completed what to do with the item
     * @return the item, or how the request ended
     */
    public @NotNull CompletionStage<InputResult<ItemStack>> open(
            @NotNull Consumer<? super ItemStack> completed) {
        Inputs.require(completed, "completed");
        return open().thenApply(result -> result.ifCompleted(completed));
    }
}
