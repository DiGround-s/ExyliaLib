package net.exylia.lib.command.lamp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.Lamp;
import revxrsal.commands.annotation.list.AnnotationList;
import revxrsal.commands.autocomplete.AsyncSuggestionProvider;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.command.CommandActor;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;

/**
 * Tab suggestions that answer what is being typed.
 *
 * <h2>The problem</h2>
 * Lamp's Brigadier bridge sends back every value a suggestion provider
 * returns, whatever the argument already reads: typing {@code /punish Drak}
 * offers the whole player list, because nothing between the provider and the
 * client compares the two. Every plugin that writes its own provider has the
 * same screen full of names nobody asked for.
 *
 * <h2>The fix</h2>
 * One factory, registered on the Lamp builder, wraps every provider the
 * registry hands out — {@code @Suggest}, {@code @SuggestWith} and anything
 * registered by type — so each of them is cut down to the word being typed.
 *
 * <h2>{@link #matching} is the half a plugin may call</h2>
 * {@link #filtering()} is <b>for this library's own Lamp only</b>. A consumer
 * plugin must not call it, and calling it fails at run time rather than at
 * compile time, which is the worst way to find out:
 *
 * <pre>
 * LinkageError: loader constraint violation ...
 *   addProviderFactory(SuggestionProvider$Factory)
 *   ... have different Class objects for the type SuggestionProvider$Factory
 * </pre>
 *
 * <p>Lamp is declared in {@code libraries:}, and the server's library loader
 * builds one of those <em>per plugin</em>. This library's
 * {@code SuggestionProvider.Factory} and a consumer's are therefore different
 * classes with the same name, and no Lamp object can cross between them. The
 * rule for everything in this package: a method whose signature names a Lamp
 * type belongs to whoever's class loader built it.
 *
 * <p>{@link #matching(String, Collection)} names none — a {@code String}, a
 * {@code Collection} and a {@code List} — so it is safe from anywhere, and it
 * is the part worth sharing. A plugin writes the twenty-line factory itself
 * and delegates the comparison here; ExyliaStaff's {@code PlayerArguments} is
 * the copy to start from.
 *
 * @since 1.129.0
 */
public final class Suggestions {

    private Suggestions() {
        throw new AssertionError("No instances.");
    }

    /**
     * The factory to register on <em>this library's</em> Lamp builder.
     *
     * <p>Not callable from a consumer plugin: see the class note. A plugin
     * builds its own and calls {@link #matching(String, Collection)} from it.
     *
     * @param <A> the actor type of the Lamp instance being built
     * @return a factory that filters every provider registered behind it
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static <A extends CommandActor> SuggestionProvider.@NotNull Factory<A> filtering() {
        return new Filtering<>();
    }

    /**
     * The values that carry on what has been typed.
     *
     * @param input  the command buffer as typed so far
     * @param values every suggestion that could be offered
     * @return the ones that start with the word being typed, case-insensitively
     */
    public static @NotNull List<String> matching(@NotNull String input, @NotNull Collection<String> values) {
        String typed = input.substring(input.lastIndexOf(' ') + 1);
        if (typed.isEmpty()) {
            return List.copyOf(values);
        }
        return values.stream()
                .filter(value -> value.regionMatches(true, 0, typed, 0, typed.length()))
                .toList();
    }

    private static final class Filtering<A extends CommandActor> implements SuggestionProvider.Factory<A> {

        @Override
        public @Nullable SuggestionProvider<A> create(@NotNull Type type,
                                                      @NotNull AnnotationList annotations,
                                                      @NotNull Lamp<A> lamp) {
            SuggestionProvider<A> source = lamp.findNextSuggestionProvider(type, annotations, this, lamp);
            // Nothing behind this factory suggests anything for this argument,
            // so it is left alone: Brigadier's own argument type goes on
            // suggesting — and filtering — the way the client expects.
            if (source.equals(SuggestionProvider.empty())) {
                return null;
            }
            if (source instanceof AsyncSuggestionProvider<?>) {
                @SuppressWarnings("unchecked")
                AsyncSuggestionProvider<A> async = (AsyncSuggestionProvider<A>) source;
                // Still answered off the main thread: a provider that reads a
                // database must not start doing it inside the tab key.
                return SuggestionProvider.fromAsync(context -> async.getSuggestionsAsync(context)
                        .thenApply(values -> matching(context.input().source(), values)));
            }
            return context -> matching(context.input().source(), source.getSuggestions(context));
        }
    }
}
