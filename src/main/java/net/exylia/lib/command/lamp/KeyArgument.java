package net.exylia.lib.command.lamp;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

/**
 * A command argument that may hold a colon, as {@code tag:heart}.
 *
 * <h2>The problem</h2>
 * Lamp hands a {@code String} parameter to Brigadier as a quotable string, and
 * an unquoted Brigadier string stops at anything but letters, digits and
 * {@code _-.+}. Paper therefore refuses {@code /cca give Notch tag:heart} at the
 * colon ("Expected whitespace to end one argument") before Lamp sees it, and
 * only the quoted {@code "tag:heart"} gets through.
 *
 * <h2>The fix</h2>
 * Name this class on the parameter with Lamp's {@code @BType}:
 *
 * <pre>{@code
 * @Subcommand("give")
 * public void give(CommandSender sender, String player, @BType(KeyArgument.class) String cosmetic) { ... }
 * }</pre>
 *
 * <p>It reads up to the next space, or a quoted string as before, which is
 * exactly what Lamp's own {@code String} reads: the handler gets the same
 * value it always did, and the quoted form keeps working. The client is told
 * the argument is a resource location, so it draws {@code tag:heart} without an
 * error and still asks the server for the parameter's suggestions.
 *
 * <p>Names no Lamp type (Brigadier and Paper come from the server's own class
 * loader), so unlike a Lamp factory it crosses from this library into a
 * plugin's Lamp; see {@link Suggestions}. Paper only, like every Lamp command.
 *
 * @since 1.476.0
 */
public final class KeyArgument implements CustomArgumentType<String, Key> {

    @Override
    public @NotNull String parse(@NotNull StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && StringReader.isQuotedStringStart(reader.peek())) {
            return reader.readQuotedString();
        }
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public @NotNull ArgumentType<Key> getNativeType() {
        return ArgumentTypes.key();
    }
}
