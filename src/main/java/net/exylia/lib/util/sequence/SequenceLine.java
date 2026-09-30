package net.exylia.lib.util.sequence;

import net.exylia.lib.text.Phrases;
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
            case "RAGDOLL" -> "SKELETON_SKULL";
            case "CAMERA" -> "SPYGLASS";
            case "SHAKE" -> "ANVIL";
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

    /**
     * One question a line's form asks.
     *
     * <p>{@code flag} is what makes it a checkbox rather than a box to type in,
     * and holds what the parameter does when nobody writes it. A yes-or-no
     * asked as text is a yes-or-no somebody spells {@code ture}.
     */
    record Field(@NotNull String key, @NotNull String label, @Nullable String hint,
                 @Nullable Boolean flag) {

        Field(@NotNull String key, @NotNull String label, @Nullable String hint) {
            this(key, label, hint, null);
        }

        /** A checkbox, ticked to start with when the parameter defaults to on. */
        static @NotNull Field flag(@NotNull String key, @NotNull String label, boolean fallback) {
            return new Field(key, label, null, fallback);
        }

        /** Whether this is asked as a checkbox. */
        boolean isFlag() {
            return flag != null;
        }
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
     * The picker's one entry for every shape: chosen, it opens the shapes on a
     * page of their own. Not a token any line is written with.
     */
    static final String SHAPES = "PARTICLE_SHAPE";

    /**
     * What the picker offers, in the order it offers them.
     *
     * <p>The effects an admin reaches for first, then the plumbing. Not
     * alphabetical: a list that opens on {@code ACTION_BAR} is a list that
     * buries {@code PARTICLE}. The shapes are one entry, {@link #SHAPES}: there
     * are dozens of them, and listed here they buried everything after them
     * under a page of identical rods.
     */
    static @NotNull List<String> tokens(@NotNull Set<String> shapeNames) {
        List<String> tokens = new ArrayList<>();
        tokens.add("PARTICLE");
        tokens.add("SOUND");
        if (!shapeNames.isEmpty()) {
            tokens.add(SHAPES);
        }
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
        tokens.add("RAGDOLL");
        tokens.add("CAMERA");
        tokens.add("SHAKE");
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
                    new Field("count", Phrases.tr("How many"), "1"),
                    new Field("speed", Phrases.tr("Speed"), Phrases.tr("0 stays put")),
                    new Field("y", Phrases.tr("Height above the anchor"), "0"),
                    new Field("color", Phrases.tr("Colour"), Phrases.tr("a name or #rrggbb; dust particles only")),
                    new Field("size", Phrases.tr("Size"), Phrases.tr("1; dust particles only")),
                    new Field("offset", Phrases.tr("Spread, as x,y,z"), "0,0,0"),
                    new Field("block", Phrases.tr("Block it is made of"), Phrases.tr("for block and item particles"))));
            case "SOUND" -> new Spec(token, Head.SOUND, Form.NAMED, List.of(
                    new Field("volume", Phrases.tr("Volume"), Phrases.tr("1; also how far it carries")),
                    new Field("pitch", Phrases.tr("Pitch"), Phrases.tr("1, from 0.5 to 2"))));
            case "POTION" -> new Spec(token, Head.POTION, Form.NAMED, List.of(
                    new Field("duration", Phrases.tr("How long"), Phrases.tr("ticks, or 5s; 100 by default")),
                    new Field("amplifier", Phrases.tr("Strength"), Phrases.tr("0 is level I")),
                    Field.flag("particles", Phrases.tr("Shows the swirling particles"), true),
                    Field.flag("icon", Phrases.tr("Shows the icon in the corner of the screen"), true),
                    Field.flag("ambient", Phrases.tr("Faint particles, the way a beacon gives them"), false)));
            case "BLOCK_BREAK" -> new Spec(token, Head.MATERIAL, Form.NAMED, List.of(
                    new Field("count", Phrases.tr("How many"), "20"),
                    new Field("y", Phrases.tr("Height above the anchor"), "0"),
                    new Field("offset", Phrases.tr("Spread, as x,y,z"), "0.3,0.3,0.3")));
            case "FIREWORK" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("color", Phrases.tr("Colour"), Phrases.tr("a name or #rrggbb")),
                    new Field("fade", Phrases.tr("Colour it fades to"), "orange"),
                    new Field("type", Phrases.tr("Shape"),
                            Phrases.tr("BALL, BALL_LARGE, STAR, BURST or CREEPER")),
                    Field.flag("trail", Phrases.tr("Leaves a trail"), true),
                    Field.flag("flicker", Phrases.tr("Twinkles"), false),
                    new Field("power", Phrases.tr("Flight time"), Phrases.tr("0 detonates at once"))));
            case "LIGHTNING" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("volume", Phrases.tr("Volume"), "2"),
                    new Field("pitch", Phrases.tr("Pitch"), "1")));
            case "SHAKE" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("radius", Phrases.tr("Shakes players this close, in blocks"), "10"),
                    new Field("times", Phrases.tr("How many tilts"), "1"),
                    new Field("every", Phrases.tr("How long between tilts"), Phrases.tr("0.1, or 100ms"))));
            case "EXPLOSION" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("count", Phrases.tr("How many"), "1"),
                    new Field("y", Phrases.tr("Height above the anchor"), "0")));
            case "TITLE" -> new Spec(token, Head.NONE, Form.POSITIONAL, List.of(
                    new Field("title", Phrases.tr("Title"), null),
                    new Field("subtitle", Phrases.tr("Subtitle"), null),
                    new Field("fade_in", Phrases.tr("Fade in"), Phrases.tr("0.5, or 500ms")),
                    new Field("stay", Phrases.tr("Stays for"), Phrases.tr("3.5, or 1m")),
                    new Field("fade_out", Phrases.tr("Fade out"), Phrases.tr("1, or 1s"))));
            case "NPC" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("pose", Phrases.tr("How it lies"),
                            Phrases.tr("lying, standing, crawling, sneaking or spinning")),
                    new Field("life", Phrases.tr("How long it stays"), Phrases.tr("5, or 1m30s")),
                    Field.flag("equip", Phrases.tr("Wears what they died in"), true),
                    new Field("glow", Phrases.tr("Outline colour"), Phrases.tr("a name, #rrggbb or a {palette} token")),
                    new Field("y", Phrases.tr("Height above the anchor"), "0"),
                    Field.flag("face", Phrases.tr("Turns to face whoever did it"), true),
                    new Field("from", Phrases.tr("Appears at, as x,y,z"), "0,0,0"),
                    new Field("to", Phrases.tr("Ends up at, as x,y,z"), "0,0,0"),
                    new Field("over", Phrases.tr("How long the movement takes"), Phrases.tr("0.7, or 700ms")),
                    new Field("ease", Phrases.tr("How the movement is spread"), Phrases.tr("out, in, in_out or linear")),
                    new Field("gravity", Phrases.tr("Falls at, in blocks per second squared"), "0"),
                    new Field("turn", Phrases.tr("Degrees it turns on the spot"), "0"),
                    new Field("pose_to", Phrases.tr("A second pose, so it goes down while you watch"), null),
                    new Field("after", Phrases.tr("How long before that second pose"), Phrases.tr("0.4, or 400ms")),
                    Field.flag("hurt", Phrases.tr("Flinches when it is struck"), false),
                    new Field("move_after", Phrases.tr("How long before any of that happens"), Phrases.tr("0, or 1s"))));
            case "CAMERA" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("keys", Phrases.tr("Where the camera goes, frame by frame"),
                            "0 close | 3.2 yaw=~360 ease=in_out | 0.6 distance=2.1 ease=out"),
                    new Field("who", Phrases.tr("Whose eyes it takes"),
                            Phrases.tr("source, target or both; source by default")),
                    Field.flag("loop", Phrases.tr("Plays the path again when it ends; it must close"), false)));
            case "RAGDOLL" -> new Spec(token, Head.NONE, Form.NAMED, List.of(
                    new Field("pose", Phrases.tr("What happens to the body"),
                            Phrases.tr("burst, spread, knocked, vortex, balloon, helicopter, plane, flatten, melt, sign, thrown or animate")),
                    new Field("keys", Phrases.tr("The choreography, frame by frame"),
                            "0.3 crouch | 0.4 up=1.5 flip=~-360 ease=out | 0.3 up=0 ease=bounce"),
                    new Field("then", Phrases.tr("What it does after the last frame"),
                            Phrases.tr("hold, burst, collapse, implode, dissolve or spell")),
                    new Field("follow", Phrases.tr("How much the loose joints lag and overshoot"), Phrases.tr("0, 1 or 2")),
                    Field.flag("loop", Phrases.tr("Dances the frames again when they end; the cycle must close"), false),
                    new Field("loop_from", Phrases.tr("How much of it is the entry, played once"), Phrases.tr("0, or 0.4")),
                    new Field("accel", Phrases.tr("What the speed is multiplied by each cycle"), "1"),
                    new Field("max_speed", Phrases.tr("The fastest a looping body may get"), "1"),
                    new Field("tempo", Phrases.tr("How fast it plays, or a range it rolls from each play"),
                            Phrases.tr("1, or 1-1.6")),
                    new Field("hold", Phrases.tr("What the right hand holds"), Phrases.tr("an item, as in POPPY")),
                    new Field("offhand", Phrases.tr("What the left hand holds"), Phrases.tr("an item")),
                    new Field("hat", Phrases.tr("What is worn on the head"), Phrases.tr("an item, as in CARVED_PUMPKIN")),
                    new Field("strings", Phrases.tr("Puppet strings up to this height"), Phrases.tr("0 for none, or blocks")),
                    new Field("chains", Phrases.tr("Chains from the wrists to the floor this far out"), Phrases.tr("0 for none, or blocks")),
                    new Field("snip", Phrases.tr("How long from the start until the strings or chains break"), Phrases.tr("the last frame")),
                    new Field("seat", Phrases.tr("Which spectator a {crowd} body wears"), "0, 1, 2..."),
                    new Field("rig", Phrases.tr("Props tied to its joints, by name from rigs.yml"),
                            Phrases.tr("tophat, or crown,wand for two")),
                    new Field("life", Phrases.tr("How long the pieces last"), Phrases.tr("2.2, or 2s200ms")),
                    new Field("intact", Phrases.tr("How long it stands whole first"), Phrases.tr("0.3, or 300ms")),
                    new Field("speed", Phrases.tr("How fast the pieces leave, outwards"), "3.2"),
                    new Field("up", Phrases.tr("How fast they leave, upwards"), "6.5"),
                    new Field("spread", Phrases.tr("How much the pieces differ, 0 to 1"), "0.45"),
                    new Field("gravity", Phrases.tr("Falls at, in blocks per second squared"), "26"),
                    new Field("bounce", Phrases.tr("Speed kept on landing, 0 to 1"), "0.32"),
                    new Field("spin", Phrases.tr("Turns a second"), "1.8"),
                    new Field("detail", Phrases.tr("How finely a body in blocks is drawn, 1 to 5 (5: the design on every face)"), "1"),
                    new Field("size", Phrases.tr("How big it is; 1 is player-sized"), "1"),
                    new Field("light", Phrases.tr("Light level, 0 to 15"), Phrases.tr("world's own")),
                    new Field("glow", Phrases.tr("Outline colour"), Phrases.tr("a name, #rrggbb or a {palette} token")),
                    Field.flag("fade", Phrases.tr("Shrinks away at the end"), true),
                    Field.flag("settle", Phrases.tr("Stops turning once it lands"), true),
                    new Field("rise", Phrases.tr("How far off the ground it hangs"), "1.1"),
                    new Field("open", Phrases.tr("How far the arms and legs open out"), "0.55"),
                    new Field("lift", Phrases.tr("How long the lift takes"), Phrases.tr("0.45, or 450ms")),
                    new Field("hang", Phrases.tr("How long it hangs there"), Phrases.tr("0.9, or 900ms")),
                    new Field("turns", Phrases.tr("Turns it makes while it hangs"), "0.35"),
                    new Field("hits", Phrases.tr("How many times it is struck"), "3"),
                    new Field("every", Phrases.tr("How long between blows"), Phrases.tr("0.32, or 320ms")),
                    new Field("force", Phrases.tr("How far a blow shoves it, in blocks"), "0.85"),
                    new Field("swell", Phrases.tr("How many times its size a head reaches"), "3"),
                    new Field("squash", Phrases.tr("What is left of a flattened piece's height"), "0.14"),
                    new Field("sign", Phrases.tr("What a sign body spells"), "EZ"),
                    new Field("letters", Phrases.tr("How tall one letter is, in blocks"), "2.4"),
                    new Field("dir", Phrases.tr("Which way it is thrown or flies, in degrees"),
                            Phrases.tr("0 is east, 90 is south")),
                    new Field("y", Phrases.tr("Height above the anchor"), "0"),
                    Field.flag("face", Phrases.tr("Turns to face whoever did it"), true)));
            case "ACTION_BAR" -> free(token, Phrases.tr("The line above the hotbar"), null);
            case "MESSAGE" -> free(token, Phrases.tr("The message"), Phrases.tr("One line; add another for a second."));
            case "COMMAND" -> free(token, Phrases.tr("Command the console runs"),
                    Phrases.tr("%player_name% is the player. No leading slash."));
            case "DELAY" -> free(token, Phrases.tr("How long to wait"), Phrases.tr("0.2 is four ticks; 1m30s works too"));
            default -> free(token, Phrases.tr("The whole line, after the token"), null);
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
        fields.add(new Field("y", Phrases.tr("Height above the anchor"), null));
        fields.add(new Field("scale", Phrases.tr("Scale"), "1"));
        fields.add(new Field("color", Phrases.tr("Colour"), Phrases.tr("a name or #rrggbb; dust particles only")));
        fields.add(new Field("size", Phrases.tr("Size"), Phrases.tr("1; dust particles only")));
        fields.add(new Field("count", Phrases.tr("Particles per point"), "1"));
        fields.add(new Field("ticks", Phrases.tr("Frames it is drawn over"), Phrases.tr("ticks, or 1s; 1 draws it at once")));
        fields.add(new Field("interval", Phrases.tr("How long between frames"), Phrases.tr("0.05, or 50ms")));
        fields.add(Field.flag("face", Phrases.tr("Turns to face the player"), false));
        fields.add(new Field("rotate", Phrases.tr("Rotation, in degrees"), "0"));
        fields.add(new Field("as", Phrases.tr("Draw it with"),
                Phrases.tr("item, block, head or text; leave empty for particles")));
        fields.add(new Field("repeat", Phrases.tr("Times it plays"), "1"));
        fields.add(new Field("every", Phrases.tr("How long between beats"), Phrases.tr("0.15, or 150ms")));
        fields.add(new Field("turn_each", Phrases.tr("Degrees further round each beat"), "0"));
        fields.add(new Field("accel", Phrases.tr("What the gap is divided by after each beat"), "1"));
        fields.add(new Field("max_speed", Phrases.tr("The most the gap may be divided by"), "1"));
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
                new Field("as", Phrases.tr("Draw it with"), Phrases.tr("item, block, head or text")),
                new Field("life", Phrases.tr("How long it lasts"), Phrases.tr("1, or 1m30s")),
                new Field("from", Phrases.tr("Starts at, as x,y,z"), "0,0,0"),
                new Field("to", Phrases.tr("Ends at, as x,y,z"), "0,0,0"),
                new Field("rise", Phrases.tr("Goes up by"), Phrases.tr("shorthand for to:0,n,0")),
                new Field("gravity", Phrases.tr("Falls at, in blocks per second squared"), Phrases.tr("0; vanilla is 32")),
                new Field("ease", Phrases.tr("How the movement is spread"),
                        Phrases.tr("in, out, in_out, back, bounce, elastic or linear")),
                new Field("spin", Phrases.tr("Turns over its life"), Phrases.tr("0, or x,y,z for a tumble")),
                new Field("axis", Phrases.tr("Turns around"), Phrases.tr("x, y or z")),
                new Field("orbit", Phrases.tr("Turns it carries round the anchor"), "0"),
                new Field("vary", Phrases.tr("How much the pieces differ in size"), Phrases.tr("0 to 1")),
                new Field("follow", Phrases.tr("Carried by whoever set it off"), Phrases.tr("true, victim or false")),
                new Field("size", Phrases.tr("Size it starts at"), "1"),
                new Field("size_to", Phrases.tr("Size it ends at"), Phrases.tr("same as size")),
                new Field("tilt", Phrases.tr("Fixed tilt, in degrees"), "0"),
                new Field("roll", Phrases.tr("Fixed roll, in degrees"), "0"),
                new Field("turn", Phrases.tr("Fixed turn, in degrees"), "0"),
                Field.flag("face_out", Phrases.tr("Points away from the centre"), false),
                new Field("pull", Phrases.tr("Travels towards the centre"), Phrases.tr("1 reaches it")),
                new Field("glow", Phrases.tr("Outline colour"), Phrases.tr("a name, #rrggbb or a {palette} token")),
                new Field("light", Phrases.tr("Fixed light level"), Phrases.tr("0 to 15")),
                new Field("model", Phrases.tr("Custom model data"), Phrases.tr("for a resource pack model")),
                new Field("billboard", Phrases.tr("Turns to face the viewer"),
                        Phrases.tr("FIXED, VERTICAL, HORIZONTAL or CENTER")),
                new Field("hold", Phrases.tr("How an item is held"),
                        Phrases.tr("0 the model itself, 5 head, 7 dropped, 8 item frame")),
                new Field("repeat", Phrases.tr("Times it plays"), "1"),
                new Field("every", Phrases.tr("Seconds between beats"), "0.15"),
                new Field("accel", Phrases.tr("What the gap is divided by after each beat"), "1"),
                new Field("max_speed", Phrases.tr("The most the gap may be divided by"), "1"));
    }
}
