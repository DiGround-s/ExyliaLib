package net.exylia.lib.util.sequence;

import net.exylia.lib.util.sequence.internal.Shapes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One line of the sequence notation, taken apart so a screen can ask about it.
 *
 * <p>The notation is the storage format and stays exactly as it was — this is
 * the same string, plus enough structure to draw it as a row and rebuild it from
 * a form. Nothing here compiles anything; the compiler is still the only reader
 * that decides what a line <em>does</em>.
 *
 * <h2>Why a class and not a {@code String}</h2>
 * Two identical lines in one effect are two rows, and the list editor tells rows
 * apart by identity before equality. Interned strings are the same object, so a
 * duplicated {@code [DELAY] 0.2} would have edited its twin.
 *
 * @since 1.71.0
 */
final class SequenceLine {

    /** The clipboard bucket sequence lines share, across every effect. */
    static final String TYPE_KEY = "exylia:sequence-lines";

    /** What the whole payload is, for the tokens that take prose. */
    private static final String FREE_KEY = "text";

    private final String text;
    private final String token;
    private final String head;
    private final String rest;
    private final Map<String, String> values;
    private final List<String> segments;

    private SequenceLine(String text) {
        this.text = text.trim();
        int close = this.text.indexOf(']');
        boolean tokenised = !this.text.isEmpty() && this.text.charAt(0) == '[' && close >= 2;
        this.token = tokenised
                ? this.text.substring(1, close).trim().toUpperCase(Locale.ROOT)
                : "";
        this.rest = tokenised && close + 1 < this.text.length()
                ? this.text.substring(close + 1).trim()
                : "";
        // The same split the compiler's Args does, because a screen that reads a
        // line differently from the reader that plays it is a screen that lies.
        String[] parts = rest.split(";");
        this.head = parts.length > 0 ? parts[0].trim() : "";
        Map<String, String> named = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index].trim();
            positional.add(part);
            int colon = part.indexOf(':');
            if (colon > 0) {
                named.put(part.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        part.substring(colon + 1).trim());
            }
        }
        this.values = Map.copyOf(named);
        this.segments = List.copyOf(positional);
    }

    /**
     * Reads a line.
     *
     * @param text the line, as it is stored
     * @return the line, taken apart
     */
    static @NotNull SequenceLine of(@NotNull String text) {
        return new SequenceLine(Objects.requireNonNull(text, "text"));
    }

    /** The line as it is stored. */
    @NotNull String text() {
        return text;
    }

    /** The {@code [TOKEN]}, uppercase, or blank when the line has none. */
    @NotNull String token() {
        return token;
    }

    /** The first segment: the particle, sound, effect or block. */
    @NotNull String head() {
        return head;
    }

    /** Everything after the token, for the lines that are prose. */
    @NotNull String rest() {
        return rest;
    }

    /** A named argument as written, or blank. */
    @NotNull String value(@NotNull String key) {
        return values.getOrDefault(key, "");
    }

    /** A segment by position, counting the head as zero; blank when absent. */
    @NotNull String segment(int index) {
        return index <= 0 ? head
                : index - 1 < segments.size() ? segments.get(index - 1) : "";
    }

    /** Whether this line says anything at all. */
    boolean isPlayable() {
        return !token.isEmpty();
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes a line back, keeping only what was answered.
     *
     * <p>Blank fields are left out rather than written as their defaults: a line
     * that says {@code [CIRCLE] FLAME;radius:1.5} is one an admin can read, and
     * the same line carrying all sixteen parameters at their default values is
     * not. Headless tokens are written {@code [FIREWORK];color:red}, which is
     * the spelling the compiler and every stored row already use.
     */
    static @NotNull String write(@NotNull String token, @NotNull String head,
                                 @NotNull Map<String, String> values) {
        StringBuilder line = new StringBuilder("[").append(token).append(']');
        if (!head.isBlank()) {
            line.append(' ').append(head.trim());
        }
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (!value.getValue().isBlank()) {
                line.append(';').append(value.getKey()).append(':').append(value.getValue().trim());
            }
        }
        return line.toString();
    }

    /** Writes a line whose whole payload is one piece of text. */
    static @NotNull String writeFree(@NotNull String token, @NotNull String text) {
        return text.isBlank() ? "[" + token + "]" : "[" + token + "] " + text.trim();
    }

    /**
     * Writes a line whose parts are positional, trimming the trailing empties.
     *
     * <p>{@code [TITLE] Welcome} rather than {@code [TITLE] Welcome;;;;}: the
     * compiler reads a missing part as its default either way, and one of those
     * two is legible.
     */
    static @NotNull String writePositional(@NotNull String token, @NotNull List<String> parts) {
        int last = -1;
        for (int index = 0; index < parts.size(); index++) {
            if (!parts.get(index).isBlank()) {
                last = index;
            }
        }
        if (last < 0) {
            return "[" + token + "]";
        }
        StringBuilder line = new StringBuilder("[").append(token).append("] ");
        for (int index = 0; index <= last; index++) {
            if (index > 0) {
                line.append(';');
            }
            line.append(parts.get(index).trim());
        }
        return line.toString();
    }

    // ------------------------------------------------------------------ drawing

    /**
     * A material that says what a line does, read from its token alone.
     *
     * <p>Deliberately not a lookup of the effect itself: a page draws forty-five
     * of these and redraws after every click, and the token is enough to tell a
     * sound from a title at a glance.
     */
    static @NotNull String icon(@NotNull String token) {
        return switch (token) {
            case "SOUND" -> "NOTE_BLOCK";
            case "PARTICLE" -> "FIREWORK_ROCKET";
            case "POTION" -> "POTION";
            case "FIREWORK" -> "FIREWORK_STAR";
            case "TITLE" -> "OAK_SIGN";
            case "ACTION_BAR" -> "PAPER";
            case "MESSAGE" -> "WRITTEN_BOOK";
            case "COMMAND" -> "COMMAND_BLOCK";
            case "LIGHTNING" -> "LIGHTNING_ROD";
            case "EXPLOSION" -> "TNT";
            case "BLOCK_BREAK" -> "IRON_PICKAXE";
            case "DELAY" -> "CLOCK";
            case "DISPLAY" -> "ARMOR_STAND";
            case "NPC" -> "PLAYER_HEAD";
            case "" -> "BARRIER";
            default -> "END_ROD";
        };
    }

    /** A token, or a parameter name, as a person reads it. */
    static @NotNull String pretty(@NotNull String name) {
        String spaced = name.replace('_', ' ').toLowerCase(Locale.ROOT);
        return spaced.isEmpty() ? spaced
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    // ------------------------------------------------------------------- shapes

    /**
     * What a line of each kind is made of.
     *
     * @param token  the {@code [TOKEN]}
     * @param head   what the first segment names, if anything
     * @param form   how the rest of the line is written
     * @param fields the questions worth asking, in the order they are asked
     */
    record Spec(@NotNull String token, @NotNull Head head, @NotNull Form form,
                @NotNull List<Field> fields) {

        /** Whether the line's payload is one piece of prose. */
        boolean isFree() {
            return form == Form.FREE;
        }
    }

    /** One question a line's form asks. */
    record Field(@NotNull String key, @NotNull String label, @Nullable String hint) {
    }

    /** What a line's first segment names, and therefore what can search for it. */
    enum Head {

        /** Nothing; the line is all named parameters. */
        NONE,
        PARTICLE,
        SOUND,
        POTION,
        MATERIAL
    }

    /** How a line's parameters are written. */
    enum Form {

        /** {@code key:value} pairs, in any order. */
        NAMED,

        /** One piece of prose: a message, a command, a number of seconds. */
        FREE,

        /** Parts in a fixed order, which only {@code [TITLE]} has. */
        POSITIONAL
    }

    /**
     * The shape tokens, uppercase and in alphabetical order.
     *
     * <p>Sorted because the set they come from is not: a picker whose rows move
     * between restarts is a picker nobody learns the shape of.
     */
    static @NotNull List<String> shapeTokens(@NotNull Set<String> shapeNames) {
        List<String> tokens = new ArrayList<>(shapeNames.size());
        for (String name : shapeNames) {
            tokens.add(name.toUpperCase(Locale.ROOT));
        }
        tokens.sort(String::compareTo);
        return List.copyOf(tokens);
    }

    /**
     * What the picker offers, in the order it offers them.
     *
     * <p>The effects an admin reaches for first, then the shapes, then the
     * plumbing. Not alphabetical: a list that opens on {@code ACTION_BAR} is a
     * list that buries {@code PARTICLE}.
     */
    static @NotNull List<String> tokens(@NotNull Set<String> shapeNames) {
        List<String> tokens = new ArrayList<>();
        tokens.add("PARTICLE");
        tokens.add("SOUND");
        tokens.addAll(shapeTokens(shapeNames));
        tokens.add("FIREWORK");
        tokens.add("POTION");
        tokens.add("LIGHTNING");
        tokens.add("EXPLOSION");
        tokens.add("BLOCK_BREAK");
        tokens.add("TITLE");
        tokens.add("ACTION_BAR");
        tokens.add("MESSAGE");
        tokens.add("COMMAND");
        tokens.add("NPC");
        tokens.add("DELAY");
        return List.copyOf(tokens);
    }

    /**
     * The questions one token is worth asking.
     *
     * <p>A token nobody recognises — a shape a plugin registered and then
     * removed, a line typed by hand — is described as free text rather than
     * refused, so an admin can still read it, fix it or delete it.
     *
     * @param token      the {@code [TOKEN]}
     * @param shapeNames the shapes this plugin knows, lowercase
     * @return what to ask for
     */
    static @NotNull Spec spec(@NotNull String token, @NotNull Set<String> shapeNames) {
        if (token.equals("DISPLAY")) {
            // Its head is an item, a block or a texture rather than a particle,
            // so the picker that opens behind it has to be a different one.
            return new Spec(token, Head.MATERIAL, Form.NAMED, displayFields());
        }
        if (shapeNames.contains(token.toLowerCase(Locale.ROOT))) {
            return new Spec(token, Head.PARTICLE, Form.NAMED, shapeFields(token));
        }
        return switch (token) {
            case "PARTICLE" -> new Spec(token, Head.PARTICLE, Form.NAMED, List.of(
                    new Field("count", "How many", "1"),
                    new Field("speed", "Speed", "0 stays put"),
                    new Field("y", "Height above the anchor", "0"),
                    new Field("color", "Colour", "a name or #rrggbb; dust particles only"),
                    new Field("size", "Size", "1; dust particles only"),
                    new Field("offset", "Spread, as x,y,z", "0,0,0"),
                    new Field("block", "Block it is made of", "for block and item particles")));
            case "SOUND" -> new Spec(token, Head.SOUND, Form.NAMED, List.of(
                    new Field("volume", "Volume", "1; also how far it carries"),
                    new Field("pitch", "Pitch", "1, from 0.5 to 2")));
            case "POTION" -> new Spec(token, Head.POTION, Form.NAMED, List.of(
                    new Field("duration", "How long, in ticks", "100, which is 5 seconds"),
                    new Field("amplifier", "Strength", "0 is level I")));
            case "BLOCK_BREAK" -> new Spec(token, Head.MATERIAL, Form.NAMED, List.of(
                    new Field("count", "How many", "20"),
                    new Field("y", "Height above the anchor", "0"),
                    new Field("offset", "Spread, as x,y,z", "0.3,0.3,0.3")));
            case "FIREWORK" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("color", "Colour", "a name or #rrggbb"),
                    new Field("fade", "Colour it fades to", "orange"),
                    new Field("type", "Shape",
                            "BALL, BALL_LARGE, STAR, BURST or CREEPER"),
                    new Field("trail", "Leaves a trail", "true or false"),
                    new Field("flicker", "Twinkles", "true or false"),
                    new Field("power", "Flight time", "0 detonates at once")));
            case "LIGHTNING" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("volume", "Volume", "2"),
                    new Field("pitch", "Pitch", "1")));
            case "EXPLOSION" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("count", "How many", "1"),
                    new Field("y", "Height above the anchor", "0")));
            case "TITLE" -> new Spec(token, Head.NONE, Form.POSITIONAL, List.of(
                    new Field("title", "Title", null),
                    new Field("subtitle", "Subtitle", null),
                    new Field("fade_in", "Fade in, in seconds", "0.5"),
                    new Field("stay", "Stays for, in seconds", "3.5"),
                    new Field("fade_out", "Fade out, in seconds", "1")));
            case "NPC" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("pose", "How it lies",
                            "lying, standing, crawling, sneaking or spinning"),
                    new Field("life", "Seconds it stays", "5"),
                    new Field("equip", "Wears what they died in", "true or false"),
                    new Field("glow", "Outline colour", "a name, #rrggbb or a {palette} token"),
                    new Field("y", "Height above the anchor", "0"),
                    new Field("face", "Turns to face whoever did it", "true or false"),
                    new Field("from", "Appears at, as x,y,z", "0,0,0"),
                    new Field("to", "Ends up at, as x,y,z", "0,0,0"),
                    new Field("over", "Seconds the movement takes", "0.7"),
                    new Field("ease", "How the movement is spread", "out, in, in_out or linear"),
                    new Field("gravity", "Falls at, in blocks per second squared", "0"),
                    new Field("turn", "Degrees it turns on the spot", "0"),
                    new Field("pose_to", "A second pose, so it goes down while you watch", null),
                    new Field("after", "Seconds before that second pose", "0.4"),
                    new Field("hurt", "Flinches when it is struck", "true or false"),
                    new Field("move_after", "Seconds before any of that happens", "0")));
            case "ACTION_BAR" -> free(token, "The line above the hotbar", null);
            case "MESSAGE" -> free(token, "The message", "One line; add another for a second.");
            case "COMMAND" -> free(token, "Command the console runs",
                    "%player_name% is the player. No leading slash.");
            case "DELAY" -> free(token, "Seconds to wait", "0.2 is four ticks");
            default -> free(token, "The whole line, after the token", null);
        };
    }

    /** The key a free-form line's single answer is read from. */
    static @NotNull String freeKey() {
        return FREE_KEY;
    }

    private static Spec free(String token, String label, String hint) {
        return new Spec(token, Head.NONE, Form.FREE, List.of(new Field(FREE_KEY, label, hint)));
    }

    /**
     * A shape's own parameters, then the ones every shape shares.
     *
     * <p>Its own first: somebody drawing a circle wants its radius, not its
     * rotation, and a form is read from the top.
     */
    private static List<Field> shapeFields(String token) {
        List<Field> fields = new ArrayList<>();
        for (String parameter : Shapes.parametersOf(token)) {
            fields.add(new Field(parameter, pretty(parameter), null));
        }
        fields.add(new Field("y", "Height above the anchor", null));
        fields.add(new Field("scale", "Scale", "1"));
        fields.add(new Field("color", "Colour", "a name or #rrggbb; dust particles only"));
        fields.add(new Field("size", "Size", "1; dust particles only"));
        fields.add(new Field("count", "Particles per point", "1"));
        fields.add(new Field("ticks", "Frames it is drawn over", "1 draws it at once"));
        fields.add(new Field("interval", "Seconds between frames", "0.05"));
        fields.add(new Field("face", "Turns to face the player", "true or false"));
        fields.add(new Field("rotate", "Rotation, in degrees", "0"));
        fields.add(new Field("as", "Draw it with",
                "item, block, head or text; leave empty for particles"));
        fields.add(new Field("repeat", "Times it plays", "1"));
        fields.add(new Field("every", "Seconds between beats", "0.15"));
        fields.add(new Field("turn_each", "Degrees further round each beat", "0"));
        return List.copyOf(fields);
    }

    /**
     * What a display line is worth asking about.
     *
     * <p>Movement first, because that is what somebody adding a display is
     * there for, and looks after it: an effect is decided by where the thing
     * goes, not by how brightly it is lit.
     */
    private static List<Field> displayFields() {
        return List.of(
                new Field("as", "Draw it with", "item, block, head or text"),
                new Field("life", "Seconds it lasts", "1"),
                new Field("from", "Starts at, as x,y,z", "0,0,0"),
                new Field("to", "Ends at, as x,y,z", "0,0,0"),
                new Field("rise", "Goes up by", "shorthand for to:0,n,0"),
                new Field("gravity", "Falls at, in blocks per second squared", "0; vanilla is 32"),
                new Field("ease", "How the movement is spread", "in, out, in_out or linear"),
                new Field("spin", "Turns over its life", "0, or x,y,z for a tumble"),
                new Field("axis", "Turns around", "x, y or z"),
                new Field("orbit", "Turns it carries round the anchor", "0"),
                new Field("vary", "How much the pieces differ in size", "0 to 1"),
                new Field("size", "Size it starts at", "1"),
                new Field("size_to", "Size it ends at", "same as size"),
                new Field("tilt", "Fixed tilt, in degrees", "0"),
                new Field("roll", "Fixed roll, in degrees", "0"),
                new Field("turn", "Fixed turn, in degrees", "0"),
                new Field("face_out", "Points away from the centre", "true or false"),
                new Field("pull", "Travels towards the centre", "1 reaches it"),
                new Field("glow", "Outline colour", "a name, #rrggbb or a {palette} token"),
                new Field("light", "Fixed light level", "0 to 15"),
                new Field("model", "Custom model data", "for a resource pack model"),
                new Field("billboard", "Turns to face the viewer",
                        "FIXED, VERTICAL, HORIZONTAL or CENTER"),
                new Field("hold", "How an item is held",
                        "0 the model itself, 5 head, 7 dropped, 8 item frame"),
                new Field("repeat", "Times it plays", "1"),
                new Field("every", "Seconds between beats", "0.15"));
    }
}
