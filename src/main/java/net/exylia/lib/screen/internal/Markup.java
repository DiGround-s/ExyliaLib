package net.exylia.lib.screen.internal;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A screen's markup read into a tree, with the line and column each tag starts on. Read with the JDK's own SAX
 * parser, with doctypes and external entities refused, after a lenient pass that lets authors write HTML habits:
 * a bare {@code &} (as in {@code &&} or {@code &l}), a bare boolean attribute ({@code <button disabled>}), an
 * unquoted value, and {@code <} inside an attribute ({@code if="a < b"}).
 *
 * <p>Internal: free to change without notice.
 */
public final class Markup {

    /** The entities XML knows; any other {@code &name;} is shown as written. */
    private static final Pattern ENTITY = Pattern.compile("&(?:#\\d{1,7}|#x[0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos);");

    private Markup() {
    }

    /** Something in the tree: an element or a run of text. */
    public sealed interface Part permits El, Words {
    }

    /** A run of text, whitespace collapsed. */
    public record Words(String text) implements Part {
    }

    /** Where a tag starts: the file it was read from, its line and column, from 1. */
    public record Pos(String file, int line, int col) {
        public static final Pos NONE = new Pos("", 0, 0);

        @Override
        public String toString() {
            return file + ":" + line + ":" + col;
        }
    }

    /** One tag. */
    public record El(String tag, Map<String, String> attrs, List<Part> children, Pos pos) implements Part {

        public String attr(String name) {
            return attrs.get(name);
        }

        public int line() {
            return pos.line();
        }

        public List<El> elements() {
            List<El> out = new ArrayList<>();
            for (Part part : children) if (part instanceof El el) out.add(el);
            return out;
        }

        /** The text of this element and its children, joined. */
        public String text() {
            StringBuilder out = new StringBuilder();
            for (Part part : children) {
                if (part instanceof Words words) out.append(words.text());
                else if (part instanceof El el) out.append(el.text());
            }
            return out.toString();
        }

        /** The same element with other children. */
        public El with(List<Part> children) {
            return new El(tag, attrs, children, pos);
        }
    }

    /** A markup that is not well-formed, with where. */
    public static final class Malformed extends Exception {
        public final int line;

        Malformed(String message, int line) {
            super(message);
            this.line = line;
        }
    }

    public static El parse(String source) throws Malformed {
        return parse(source, "");
    }

    /** One root element, as a screen file holds. */
    public static El parse(String source, String file) throws Malformed {
        List<int[]> starts = new ArrayList<>();
        String fixed = lenient(source, starts);
        return sax(fixed, file, starts);
    }

    /**
     * Any number of top-level elements, as a component or an included file holds, under a {@code fragment}
     * whose children are drawn in its place.
     */
    public static El fragment(String source, String file) throws Malformed {
        List<int[]> starts = new ArrayList<>();
        starts.add(new int[]{1, 1});
        // On the first line, so every line number stays the file's own.
        String fixed = "<fragment>" + lenient(source, starts) + "</fragment>";
        return sax(fixed, file, starts);
    }

    private static El sax(String fixed, String file, List<int[]> starts) throws Malformed {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Builder builder = new Builder(file, starts);
            factory.newSAXParser().parse(new InputSource(new StringReader(fixed)), builder);
            return builder.root;
        } catch (SAXParseException failure) {
            throw new Malformed(failure.getMessage(), failure.getLineNumber());
        } catch (Exception failure) {
            throw new Malformed(String.valueOf(failure.getMessage()), 0);
        }
    }

    /**
     * Rewrites HTML habits into XML without moving a line: {@code &} that starts no entity becomes {@code &amp;},
     * {@code <} that starts no tag becomes {@code &lt;}, a bare attribute gets {@code =""}, an unquoted value
     * gets quotes, and {@code <}, {@code &} inside a value are escaped. Doctypes are left alone, so the parser
     * still refuses them.
     *
     * @param starts filled with the line and column of every start tag, in document order
     */
    static String lenient(String in, List<int[]> starts) {
        StringBuilder out = new StringBuilder(in.length() + 64);
        int n = in.length();
        int line = 1;
        int lineStart = 0;
        int i = 0;
        while (i < n) {
            char c = in.charAt(i);
            if (c == '<') {
                String skip = in.startsWith("<!--", i) ? "-->" : in.startsWith("<![CDATA[", i) ? "]]>"
                        : in.startsWith("<?", i) ? "?>" : null;
                if (skip != null) {
                    int end = in.indexOf(skip, i);
                    end = end < 0 ? n : end + skip.length();
                    for (int k = i; k < end; k++) if (in.charAt(k) == '\n') { line++; lineStart = k + 1; }
                    out.append(in, i, end);
                    i = end;
                    continue;
                }
                char next = i + 1 < n ? in.charAt(i + 1) : ' ';
                if (next == '!' || next == '/') {
                    // A doctype or an end tag: copied, up to its '>'.
                    int end = in.indexOf('>', i);
                    end = end < 0 ? n : end + 1;
                    for (int k = i; k < end; k++) if (in.charAt(k) == '\n') { line++; lineStart = k + 1; }
                    out.append(in, i, end);
                    i = end;
                    continue;
                }
                if (!Character.isLetter(next)) {
                    out.append("&lt;");
                    i++;
                    continue;
                }
                starts.add(new int[]{line, i - lineStart + 1});
                // The name, then attributes until '>' or '/>'.
                int k = i + 1;
                while (k < n && !Character.isWhitespace(in.charAt(k)) && in.charAt(k) != '>' && in.charAt(k) != '/') k++;
                out.append(in, i, k);
                while (k < n) {
                    char a = in.charAt(k);
                    if (Character.isWhitespace(a)) {
                        if (a == '\n') { line++; lineStart = k + 1; }
                        out.append(a);
                        k++;
                    } else if (a == '>') {
                        out.append(a);
                        k++;
                        break;
                    } else if (a == '/' && k + 1 < n && in.charAt(k + 1) == '>') {
                        out.append("/>");
                        k += 2;
                        break;
                    } else {
                        int nameStart = k;
                        while (k < n && !Character.isWhitespace(in.charAt(k)) && "=>/".indexOf(in.charAt(k)) < 0) k++;
                        if (k == nameStart) { // a stray '/' or '='
                            k++;
                            continue;
                        }
                        out.append(in, nameStart, k);
                        int look = k;
                        while (look < n && Character.isWhitespace(in.charAt(look)) && in.charAt(look) != '\n') look++;
                        if (look < n && in.charAt(look) == '=') {
                            k = look + 1;
                            while (k < n && Character.isWhitespace(in.charAt(k)) && in.charAt(k) != '\n') k++;
                            out.append('=');
                            char q = k < n ? in.charAt(k) : ' ';
                            int valueStart;
                            int valueEnd;
                            if (q == '"' || q == '\'') {
                                valueStart = k + 1;
                                valueEnd = in.indexOf(q, valueStart);
                                if (valueEnd < 0) valueEnd = n;
                                k = Math.min(n, valueEnd + 1);
                            } else {
                                q = '"';
                                valueStart = k;
                                while (k < n && !Character.isWhitespace(in.charAt(k)) && in.charAt(k) != '>'
                                        && !(in.charAt(k) == '/' && k + 1 < n && in.charAt(k + 1) == '>')) k++;
                                valueEnd = k;
                            }
                            out.append(q);
                            for (int v = valueStart; v < valueEnd; v++) {
                                char ch = in.charAt(v);
                                if (ch == '\n') { line++; lineStart = v + 1; }
                                if (ch == '<') out.append("&lt;");
                                else if (ch == '&' && !entityAt(in, v)) out.append("&amp;");
                                else if (ch == '"' && q == '"') out.append("&quot;");
                                else out.append(ch);
                            }
                            out.append(q);
                        } else {
                            out.append("=\"\"");
                        }
                    }
                }
                i = k;
                continue;
            }
            if (c == '&' && !entityAt(in, i)) {
                out.append("&amp;");
            } else {
                if (c == '\n') { line++; lineStart = i + 1; }
                out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    private static boolean entityAt(String in, int at) {
        int end = in.indexOf(';', at);
        return end > at && end - at <= 10 && ENTITY.matcher(in.substring(at, end + 1)).matches();
    }

    private static final class Builder extends DefaultHandler {
        private final Deque<El> open = new ArrayDeque<>();
        private final StringBuilder words = new StringBuilder();
        private final String file;
        private final List<int[]> starts;
        private int index;
        El root;

        Builder(String file, List<int[]> starts) {
            this.file = file;
            this.starts = starts;
        }

        @Override
        public void startElement(String uri, String local, String name, Attributes attributes) {
            flush();
            Map<String, String> attrs = new LinkedHashMap<>();
            for (int i = 0; i < attributes.getLength(); i++) attrs.put(attributes.getQName(i), attributes.getValue(i));
            int[] at = index < starts.size() ? starts.get(index) : new int[]{0, 0};
            index++;
            El el = new El(name, attrs, new ArrayList<>(), new Pos(file, at[0], at[1]));
            if (open.isEmpty()) root = el;
            else open.peek().children().add(el);
            open.push(el);
        }

        @Override
        public void endElement(String uri, String local, String name) {
            flush();
            open.pop();
        }

        @Override
        public void characters(char[] chars, int start, int length) {
            words.append(chars, start, length);
        }

        private void flush() {
            String text = words.toString().replaceAll("\\s+", " ");
            words.setLength(0);
            if (text.isBlank() || open.isEmpty()) return;
            open.peek().children().add(new Words(text.strip()));
        }
    }
}
