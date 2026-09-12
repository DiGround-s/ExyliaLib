package net.exylia.lib.command.lamp;

import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.player.PlayerTarget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.Lamp;
import revxrsal.commands.annotation.list.AnnotationList;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.exception.SendableException;
import revxrsal.commands.node.ExecutionContext;
import revxrsal.commands.parameter.ParameterType;
import revxrsal.commands.stream.MutableStringStream;

import java.lang.reflect.Type;

/**
 * Player arguments that work on somebody who is not here.
 *
 * <h2>What it gives a command</h2>
 * Two parameter types, and between them every shape a target comes in:
 *
 * <ul>
 *   <li>{@link ExyliaPlayer} — somebody this server knows: online here, or
 *       in its user cache because they have played here before. Resolved
 *       while the command is parsed, from memory, and a name nobody knows is
 *       reported before the handler runs.</li>
 *   <li>{@link PlayerTarget} — anybody at all, including a player on another
 *       backend and one only Mojang can identify. Always parses; the handler
 *       calls {@link PlayerTarget#then} and is handed the answer on the
 *       server thread.</li>
 * </ul>
 *
 * <p>Both suggest this server's players and the network's, filtered to what
 * is being typed.
 *
 * <h2>Registering them</h2>
 * One line, next to the suggestion filter every plugin already registers:
 *
 * <pre>{@code
 * Lamp<BukkitCommandActor> lamp = BukkitLamp.builder(plugin)
 *         .parameterTypes(types -> types.addParameterTypeFactory(PlayerTypes.factory()))
 *         .suggestionProviders(providers -> providers.addProviderFactory(Suggestions.filtering()))
 *         .build();
 * }</pre>
 *
 * <p>Nothing else is needed: the not-found line comes from the library's own
 * {@code messages.yml}, sent by the exception itself, so a plugin adopting
 * these types registers no exception handler and writes no message of its
 * own.
 *
 * <h2>Why the cheap type does no I/O</h2>
 * Lamp calls {@code parse} for every keystroke of a tab completion and
 * several times per execution while it works out which overload is meant. A
 * parameter type that went to a database or to the proxy would do it dozens
 * of times a second, on whichever thread the completion arrived on. That is
 * the whole reason the network tiers live behind {@link PlayerTarget}
 * instead.
 *
 * @since 1.146.0
 */
public final class PlayerTypes {

    private PlayerTypes() {
        throw new AssertionError("No instances.");
    }

    /**
     * The factory that registers both types at once.
     *
     * @return the factory to hand to {@code addParameterTypeFactory}
     */
    public static @NotNull ParameterType.Factory<BukkitCommandActor> factory() {
        return new Types();
    }

    /**
     * The type that resolves a player this server knows, from memory.
     *
     * @return the parameter type
     */
    public static @NotNull ParameterType<BukkitCommandActor, ExyliaPlayer> known() {
        return new Known();
    }

    /**
     * The type that takes a name now and looks it up in the handler.
     *
     * @return the parameter type
     */
    public static @NotNull ParameterType<BukkitCommandActor, PlayerTarget> target() {
        return new Target();
    }

    /**
     * Sent when no tier could put an id to a name.
     *
     * <p>A {@link SendableException} rather than an ordinary one because Lamp
     * hands those straight to {@link #sendTo}, bypassing the exception
     * handler: a plugin that registers these types does not have to register
     * anything to make the message appear, and one that has its own handler
     * does not have to remember this case in it.
     */
    public static final class PlayerNotFoundException extends SendableException {

        private final String name;

        /**
         * @param name the name as the sender typed it
         */
        public PlayerNotFoundException(@NotNull String name) {
            super(name);
            this.name = name;
        }

        /** The name nobody answered to. */
        public @NotNull String name() {
            return name;
        }

        @Override
        public void sendTo(@NotNull CommandActor actor) {
            if (actor instanceof BukkitCommandActor bukkit) {
                ExyliaPlayers.notFound(bukkit.sender(), name);
            } else {
                actor.reply("No player named " + name + " was found.");
            }
        }
    }

    private static final class Known implements ParameterType<BukkitCommandActor, ExyliaPlayer> {

        @Override
        public ExyliaPlayer parse(@NotNull MutableStringStream input,
                                  @NotNull ExecutionContext<BukkitCommandActor> context) {
            String typed = input.readString();
            ExyliaPlayer found = ExyliaPlayers.cached(typed);
            if (found == null) {
                throw new PlayerNotFoundException(typed);
            }
            return found;
        }

        @Override
        public @NotNull SuggestionProvider<BukkitCommandActor> defaultSuggestions() {
            return context -> Suggestions.matching(context.input().source(), ExyliaPlayers.names());
        }
    }

    private static final class Target implements ParameterType<BukkitCommandActor, PlayerTarget> {

        @Override
        public PlayerTarget parse(@NotNull MutableStringStream input,
                                  @NotNull ExecutionContext<BukkitCommandActor> context) {
            String typed = input.readString();
            if (typed.isBlank()) {
                // A quoted empty argument. Reported as a player nobody
                // answers to, which is what it is: the alternative is the
                // record's own exception reaching Lamp as an internal error
                // and the sender reading a stack trace's worth of nothing.
                throw new PlayerNotFoundException(typed);
            }
            return new PlayerTarget(typed);
        }

        @Override
        public @NotNull SuggestionProvider<BukkitCommandActor> defaultSuggestions() {
            return context -> Suggestions.matching(context.input().source(), ExyliaPlayers.names());
        }
    }

    private static final class Types implements ParameterType.Factory<BukkitCommandActor> {

        @SuppressWarnings("unchecked")
        @Override
        public <T> @Nullable ParameterType<BukkitCommandActor, T> create(
                @NotNull Type parameterType,
                @NotNull AnnotationList annotations,
                @NotNull Lamp<BukkitCommandActor> lamp) {
            if (parameterType == ExyliaPlayer.class) {
                return (ParameterType<BukkitCommandActor, T>) known();
            }
            if (parameterType == PlayerTarget.class) {
                return (ParameterType<BukkitCommandActor, T>) target();
            }
            return null;
        }
    }
}
