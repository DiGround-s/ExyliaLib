package net.exylia.lib.screen.internal;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
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

/**
 * A screen's markup read into a tree, with the line each tag starts on. Read with the JDK's own SAX parser,
 * with doctypes and external entities refused.
 *
 * <p>Internal: free to change without notice.
 */
public final class Markup {

    private Markup() {
    }

    /** Something in the tree: an element or a run of text. */
    public sealed interface Part permits El, Words {
    }

    /** A run of text, whitespace collapsed. */
    public record Words(String text) implements Part {
    }

    /** One tag. */
    public record El(String tag, Map<String, String> attrs, List<Part> children, int line) implements Part {

        public String attr(String name) {
            return attrs.get(name);
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
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Builder builder = new Builder();
            factory.newSAXParser().parse(new InputSource(new StringReader(source)), builder);
            return builder.root;
        } catch (SAXParseException failure) {
            throw new Malformed(failure.getMessage(), failure.getLineNumber());
        } catch (Exception failure) {
            throw new Malformed(String.valueOf(failure.getMessage()), 0);
        }
    }

    private static final class Builder extends DefaultHandler {
        private final Deque<El> open = new ArrayDeque<>();
        private final StringBuilder words = new StringBuilder();
        private Locator locator;
        El root;

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String local, String name, Attributes attributes) {
            flush();
            Map<String, String> attrs = new LinkedHashMap<>();
            for (int i = 0; i < attributes.getLength(); i++) attrs.put(attributes.getQName(i), attributes.getValue(i));
            El el = new El(name, attrs, new ArrayList<>(), locator == null ? 0 : locator.getLineNumber());
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
