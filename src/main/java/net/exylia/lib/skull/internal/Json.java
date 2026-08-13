package net.exylia.lib.skull.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The two things this module needs to read out of Mojang's answers.
 *
 * <p>Gson ships with the server — paper-api depends on it at compile scope —
 * so it is used rather than hand-rolling a parser. Kept to two methods on
 * purpose: everything Mojang returns here is a flat object or a small array of
 * properties, and a general JSON facade would be more surface than the module
 * has questions.
 *
 * <p>Every failure is {@code null}. A malformed answer is a head that does not
 * render, not an exception in a menu.
 */
final class Json {

    private Json() {
    }

    /**
     * Reads a top-level string field.
     *
     * @param body  the response body
     * @param field the field name
     * @return the value, or {@code null}
     */
    static String string(String body, String field) {
        JsonObject object = object(body);
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(field);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    /**
     * Reads a named entry out of a profile's {@code properties} array.
     *
     * <p>The session server answers with a list of name/value pairs rather
     * than an object, so the texture has to be looked up by name.
     *
     * @param body the response body
     * @param name the property name, in practice {@code textures}
     * @return the property value, or {@code null}
     */
    static String property(String body, String name) {
        JsonObject object = object(body);
        if (object == null) {
            return null;
        }
        JsonElement properties = object.get("properties");
        if (properties == null || !properties.isJsonArray()) {
            return null;
        }
        JsonArray array = properties.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject property = element.getAsJsonObject();
            JsonElement propertyName = property.get("name");
            if (propertyName != null && name.equals(propertyName.getAsString())) {
                JsonElement value = property.get("value");
                return value == null ? null : value.getAsString();
            }
        }
        return null;
    }

    private static JsonObject object(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
