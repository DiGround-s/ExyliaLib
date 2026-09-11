package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.util.sequence.Shape;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pictures and words, drawn as a grid of solid pieces.
 *
 * <pre>
 * [PIXELS] RED_CONCRETE;as:block;art:heart;pick:#;pixel:0.18;size:0.18;face:true
 * [PIXELS] WHITE_CONCRETE;as:block;art:heart;pick:w;pixel:0.18;size:0.18;face:true
 * [PIXELS] LIME_CONCRETE;as:block;word:EZ;pixel:0.2;size:0.2;face:true
 * </pre>
 *
 * <h2>Why blocks and not a line of text</h2>
 * A text display is a flat label that turns to face everyone at once, and next
 * to a body built out of blocks it reads as a sticker. The same heart laid out
 * in blocks has depth, catches the light and can be thrown, spun and shattered
 * like everything else in the effect.
 *
 * <h2>Colours are separate lines</h2>
 * A line draws one material. A picture is drawn with a character per colour,
 * and {@code pick:} chooses which characters a line draws, so a heart with a
 * shine on it is two lines over the same picture. Without {@code pick:} a line
 * draws every filled cell.
 *
 * <p>The picture stands upright, across the shape's own east-west axis and one
 * {@code pixel:} per cell, bottom row on the anchor. {@code face:true} turns it
 * to whoever did it, which is what keeps a word the right way round.
 */
@ApiStatus.Internal
public final class PixelArt {

    private PixelArt() {
    }

    /**
     * The points of a picture or a word.
     *
     * @param args {@code art} or {@code word}, {@code pixel} and {@code pick}
     * @return one point per drawn cell, top row first
     */
    public static List<Vector> points(Shape.ShapeArgs args) {
        String word = args.text("word", "");
        String[] rows = word.isBlank() ? art(args.text("art", "heart")) : typeset(word);
        double pixel = args.number("pixel", 0.2);
        // Towards whoever the picture faces, or behind it when negative. Turned
        // with the picture, which from:/to: are not: a heart that has to stay
        // behind a body stays behind it from the side it is being watched.
        double depth = args.number("depth", 0.0);
        String pick = args.text("pick", "");
        int width = 0;
        for (String row : rows) {
            width = Math.max(width, row.length());
        }
        List<Vector> points = new ArrayList<>();
        for (int row = 0; row < rows.length; row++) {
            String cells = rows[row];
            for (int column = 0; column < cells.length(); column++) {
                char cell = cells.charAt(column);
                if (cell == '.' || cell == ' ' || (!pick.isEmpty() && pick.indexOf(cell) < 0)) {
                    continue;
                }
                points.add(new Vector(
                        (column - (width - 1) / 2.0) * pixel,
                        (rows.length - 1 - row) * pixel,
                        depth));
            }
        }
        return points;
    }

    /** A named picture, or the heart when there is no picture by that name. */
    static String[] art(String name) {
        String[] rows = ARTS.get(name.trim().toLowerCase(Locale.ROOT));
        return rows == null ? ARTS.get("heart") : rows;
    }

    /**
     * A word, set in the block font, one empty column between letters.
     *
     * <p>A character the font does not have is left out rather than refused: a
     * word that loses a comma still spells the word.
     */
    static String[] typeset(String word) {
        StringBuilder[] lines = new StringBuilder[5];
        for (int line = 0; line < lines.length; line++) {
            lines[line] = new StringBuilder();
        }
        boolean first = true;
        for (char letter : word.toUpperCase(Locale.ROOT).toCharArray()) {
            String[] glyph = letter == ' ' ? new String[]{"..", "..", "..", "..", ".."} : FONT.get(letter);
            if (glyph == null) {
                continue;
            }
            for (int line = 0; line < lines.length; line++) {
                if (!first) {
                    lines[line].append('.');
                }
                lines[line].append(glyph[line]);
            }
            first = false;
        }
        String[] rows = new String[lines.length];
        for (int line = 0; line < lines.length; line++) {
            rows[line] = lines[line].toString();
        }
        return rows;
    }

    /**
     * The pictures.
     *
     * <p>{@code #} is the body of each, and the other characters are the
     * colours worth drawing apart: {@code o} an outline, {@code w} a shine,
     * {@code y} a gem, {@code b} an eye.
     */
    private static final Map<String, String[]> ARTS = Map.ofEntries(
            Map.entry("heart", new String[]{
                    ".##...##.",
                    "#ww#.####",
                    "#w#######",
                    "#########",
                    ".#######.",
                    "..#####..",
                    "...###...",
                    "....#...."}),
            Map.entry("heart_big", new String[]{
                    "..ooo...ooo..",
                    ".o###o.o###o.",
                    "o#ww##o#####o",
                    "o#w#########o",
                    "o###########o",
                    ".o#########o.",
                    "..o#######o..",
                    "...o#####o...",
                    "....o###o....",
                    ".....o#o.....",
                    "......o......"}),
            Map.entry("broken_heart", new String[]{
                    ".##...##.",
                    "####.o###",
                    "####o####",
                    "###o#####",
                    ".###o###.",
                    "..#o###..",
                    "...#o#...",
                    "....#...."}),
            Map.entry("star", new String[]{
                    "....#....",
                    "....#....",
                    "...###...",
                    "#########",
                    ".#######.",
                    "..#####..",
                    "..##.##..",
                    ".##...##.",
                    "#.......#"}),
            Map.entry("crown", new String[]{
                    "#...#...#",
                    "##.###.##",
                    "#########",
                    "#y##y##y#",
                    "#########"}),
            Map.entry("skull", new String[]{
                    ".#####.",
                    "#######",
                    "#b##b##",
                    "#######",
                    ".##.##.",
                    "..###..",
                    "..#.#.."}),
            Map.entry("bolt", new String[]{
                    "..####",
                    ".####.",
                    ".###..",
                    "#####.",
                    "..###.",
                    "..##..",
                    ".##...",
                    ".#....",
                    "#....."}),
            Map.entry("note", new String[]{
                    "..#####",
                    "..#...#",
                    "..#...#",
                    "..#...#",
                    "###.###",
                    "###.###"}),
            Map.entry("cloud", new String[]{
                    "....###....",
                    "..#######..",
                    ".#########.",
                    "###########",
                    ".#########."}),
            Map.entry("trophy", new String[]{
                    "#######",
                    "#######",
                    ".#####.",
                    "..###..",
                    "...#...",
                    "...#...",
                    "..###..",
                    ".#####."}),
            Map.entry("ufo", new String[]{
                    "....www....",
                    "...#####...",
                    "###########",
                    ".y.y.y.y.y.",
                    "..#######.."}),
            Map.entry("guillotine", new String[]{
                    "#########", "##.....##", "#.......#", "#.......#", "#.......#", "#.......#",
                    "#.......#", "#.......#", "#.......#", "#.......#", "#.......#", "####.####",
                    "#.......#", "##.....##"}),
            Map.entry("blade", new String[]{"#######", "######.", "#####.."}),
            Map.entry("grave", new String[]{".###.", "##.##", "#...#", "##.##", "##.##", "#####", "#####"}),
            Map.entry("moon", new String[]{
                    "..###.",
                    ".###..",
                    "###...",
                    "###...",
                    "###...",
                    ".###..",
                    "..###."}));

    /** The block font: five rows high, capitals, digits and a little punctuation. */
    private static final Map<Character, String[]> FONT = Map.ofEntries(
            Map.entry('A', new String[]{".###.", "#...#", "#####", "#...#", "#...#"}),
            Map.entry('B', new String[]{"####.", "#...#", "####.", "#...#", "####."}),
            Map.entry('C', new String[]{".####", "#....", "#....", "#....", ".####"}),
            Map.entry('D', new String[]{"####.", "#...#", "#...#", "#...#", "####."}),
            Map.entry('E', new String[]{"#####", "#....", "####.", "#....", "#####"}),
            Map.entry('F', new String[]{"#####", "#....", "####.", "#....", "#...."}),
            Map.entry('G', new String[]{".####", "#....", "#..##", "#...#", ".####"}),
            Map.entry('H', new String[]{"#...#", "#...#", "#####", "#...#", "#...#"}),
            Map.entry('I', new String[]{"###", ".#.", ".#.", ".#.", "###"}),
            Map.entry('J', new String[]{"..###", "...#.", "...#.", "#..#.", ".##.."}),
            Map.entry('K', new String[]{"#...#", "#..#.", "###..", "#..#.", "#...#"}),
            Map.entry('L', new String[]{"#....", "#....", "#....", "#....", "#####"}),
            Map.entry('M', new String[]{"#...#", "##.##", "#.#.#", "#...#", "#...#"}),
            Map.entry('N', new String[]{"#...#", "##..#", "#.#.#", "#..##", "#...#"}),
            Map.entry('O', new String[]{".###.", "#...#", "#...#", "#...#", ".###."}),
            Map.entry('P', new String[]{"####.", "#...#", "####.", "#....", "#...."}),
            Map.entry('Q', new String[]{".###.", "#...#", "#.#.#", "#..#.", ".##.#"}),
            Map.entry('R', new String[]{"####.", "#...#", "####.", "#..#.", "#...#"}),
            Map.entry('S', new String[]{".####", "#....", ".###.", "....#", "####."}),
            Map.entry('T', new String[]{"#####", "..#..", "..#..", "..#..", "..#.."}),
            Map.entry('U', new String[]{"#...#", "#...#", "#...#", "#...#", ".###."}),
            Map.entry('V', new String[]{"#...#", "#...#", "#...#", ".#.#.", "..#.."}),
            Map.entry('W', new String[]{"#...#", "#...#", "#.#.#", "##.##", "#...#"}),
            Map.entry('X', new String[]{"#...#", ".#.#.", "..#..", ".#.#.", "#...#"}),
            Map.entry('Y', new String[]{"#...#", ".#.#.", "..#..", "..#..", "..#.."}),
            Map.entry('Z', new String[]{"#####", "...#.", "..#..", ".#...", "#####"}),
            Map.entry('0', new String[]{".###.", "#..##", "#.#.#", "##..#", ".###."}),
            Map.entry('1', new String[]{".#.", "##.", ".#.", ".#.", "###"}),
            Map.entry('2', new String[]{"####.", "....#", ".###.", "#....", "#####"}),
            Map.entry('3', new String[]{"####.", "....#", ".###.", "....#", "####."}),
            Map.entry('4', new String[]{"#..#.", "#..#.", "#####", "...#.", "...#."}),
            Map.entry('5', new String[]{"#####", "#....", "####.", "....#", "####."}),
            Map.entry('6', new String[]{".###.", "#....", "####.", "#...#", ".###."}),
            Map.entry('7', new String[]{"#####", "...#.", "..#..", ".#...", ".#..."}),
            Map.entry('8', new String[]{".###.", "#...#", ".###.", "#...#", ".###."}),
            Map.entry('9', new String[]{".###.", "#...#", ".####", "....#", ".###."}),
            Map.entry('!', new String[]{"#", "#", "#", ".", "#"}),
            Map.entry('?', new String[]{"###.", "...#", ".##.", "....", ".#.."}),
            Map.entry('.', new String[]{".", ".", ".", ".", "#"}),
            Map.entry('-', new String[]{"...", "...", "###", "...", "..."}),
            Map.entry(':', new String[]{".", "#", ".", "#", "."}));
}
