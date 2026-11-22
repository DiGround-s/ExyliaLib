package net.exylia.lib.web.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.exylia.lib.screen.internal.Markup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One screen of a {@code ScreenSet}: {@code <id>.html} and {@code <id>.css}, edited as two strings. The markup is
 * checked with the parser the screens read it with; only what would not read at all (not well-formed, a doctype) is
 * refused, since a subset problem still draws and is listed in the subject's details.
 */
final class ScreenSource implements Source {

    static final String KIND = "lib/screen";

    private final Path html;
    private final Path css;
    private final String name;

    ScreenSource(Path html, Path css, String name) {
        this.html = html;
        this.css = css;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public java.io.File disk() {
        return html.toFile();
    }

    @Override
    public JsonObject schema(String pluginName) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "section");
        out.add("comments", strings("A screen: its markup and its own sheet."));
        JsonArray fields = new JsonArray();
        fields.add(field("html", "The screen's markup, " + html.getFileName() + "."));
        fields.add(field("css", "The screen's own sheet, " + css.getFileName()
                + ", read after the library's base theme and the plugin's theme.css. Empty when it has none."));
        out.add("fields", fields);
        return out;
    }

    @Override
    public JsonObject values(String pluginName) {
        return values(read(html), read(css));
    }

    @Override
    public Prepared prepare(String pluginName, JsonObject values) {
        JsonElement markup = values.get("html");
        JsonElement sheet = values.get("css");
        if (markup == null || !markup.isJsonPrimitive() || !markup.getAsJsonPrimitive().isString()) {
            return Prepared.refused("html must be the screen's markup as text");
        }
        if (sheet != null && !sheet.isJsonNull() && !(sheet.isJsonPrimitive() && sheet.getAsJsonPrimitive().isString())) {
            return Prepared.refused("css must be text");
        }
        String text = markup.getAsString();
        String style = sheet == null || sheet.isJsonNull() ? "" : sheet.getAsString();
        List<String> problems = problems(html.getFileName().toString(), text);
        if (!problems.isEmpty()) {
            return Prepared.refused(String.join("\n", problems),
                    problems.stream().map(net.exylia.lib.web.Problem::of).toList());
        }
        List<String> console = consoleSteps(text);
        List<String> before = consoleSteps(read(html));
        for (String known : before) console.remove(known);
        JsonObject after = values(text, style);
        return new Prepared(null, after, List.of(), () -> {
            if (!style.isEmpty() || Files.exists(css)) write(css, style);
            write(html, text);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }, console.isEmpty() ? List.of() : List.of("html"));
    }

    /**
     * Why a markup would not read, one line per reason; empty when it reads. A doctype (and with it any entity
     * declaration) is refused by the parser itself.
     */
    static List<String> problems(String file, String markup) {
        try {
            Markup.parse(markup, file);
            return List.of();
        } catch (Markup.Malformed failure) {
            return List.of(file + ":" + failure.line + ": " + failure.getMessage());
        }
    }

    /** Every action step in the markup that runs as the console, as written; empty when it does not read. */
    static List<String> consoleSteps(String markup) {
        List<String> out = new ArrayList<>();
        try {
            collect(Markup.parse(markup, ""), out);
        } catch (Markup.Malformed unreadable) {
            // A file that does not read runs nothing.
        }
        return out;
    }

    private static void collect(Markup.El element, List<String> out) {
        for (String value : element.attrs().values()) {
            for (String step : value.split(";")) {
                if (Diff.privileged(new JsonPrimitive(step))) out.add(step.trim());
            }
        }
        for (Markup.El child : element.elements()) collect(child, out);
    }

    private static JsonObject values(String markup, String sheet) {
        JsonObject out = new JsonObject();
        out.addProperty("html", markup);
        out.addProperty("css", sheet);
        return out;
    }

    private static String read(Path file) {
        if (!Files.isRegularFile(file)) return "";
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(file.getFileName() + " could not be read: " + failure.getMessage(), failure);
        }
    }

    private static void write(Path target, String text) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(target.getFileName() + " could not be written: " + failure.getMessage(), failure);
        }
    }

    private static JsonObject field(String key, String comment) {
        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        JsonObject field = new JsonObject();
        field.addProperty("key", key);
        field.add("comments", strings(comment));
        field.add("type", type);
        return field;
    }

    private static JsonArray strings(String line) {
        JsonArray out = new JsonArray();
        out.add(line);
        return out;
    }
}
