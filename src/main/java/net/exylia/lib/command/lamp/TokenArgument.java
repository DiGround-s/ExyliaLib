package net.exylia.lib.command.lamp;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import io.papermc.paper.command.brigadier.argument.resolvers.PlayerProfileListResolver;
import org.jetbrains.annotations.NotNull;

/**
 * A one-word command argument that may hold any character, as {@code %top_kills;name;1%}.
 *
 * <p>Same problem as {@link KeyArgument}: an unquoted Brigadier string stops at anything but
 * letters, digits and {@code _-.+}, so the client draws {@code %} in red and Paper refuses it
 * before Lamp sees it. Name this class on the parameter with Lamp's {@code @BType}:
 *
 * <pre>{@code
 * @Subcommand("<name> skin")
 * public void skin(CommandSender sender, String name, @BType(TokenArgument.class) String skin) { ... }
 * }</pre>
 *
 * <p>It reads up to the next space, or a quoted string as before. The client is told the
 * argument is a player name, the one vanilla type it also reads up to the next space, so it
 * draws placeholders without an error and still asks the server for the parameter's suggestions.
 *
 * @since 1.583.0
 */
public final class TokenArgument implements CustomArgumentType<String, PlayerProfileListResolver> {

    @Override
    public @NotNull String parse(@NotNull StringReader reader) throws CommandSyntaxException {
        return KeyArgument.read(reader);
    }

    @Override
    public @NotNull ArgumentType<PlayerProfileListResolver> getNativeType() {
        return ArgumentTypes.playerProfiles();
    }
}
