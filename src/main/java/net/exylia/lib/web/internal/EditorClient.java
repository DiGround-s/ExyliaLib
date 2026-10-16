package net.exylia.lib.web.internal;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * The five things the server says to the editor (protocol v2). Behind an interface so the runtime is tested
 * without a network; {@link HttpEditorClient} is the one that talks.
 *
 * <p>Every call is a request the server makes, authorised by the session's secret in a header.
 * The editor never calls the server.
 */
interface EditorClient {

    /** A session the editor opened: the public code in the link, and when it dies. */
    record Opened(String code, String url, long expiresAtMillis) {
    }

    /**
     * A change saved in the browser and not yet applied.
     *
     * @param id        the change, so it can be marked applied
     * @param subjects  the full values of every subject the change touches, by key
     * @param baseHash  the hash the browser edited from, by subject key
     * @param author    how the editor describes who saved it; shown, never trusted
     */
    record Pending(String id, JsonObject subjects, JsonObject baseHash, @Nullable String author) {
    }

    /** What the editor answered a trust request with. */
    enum Trust { TRUSTED, UNKNOWN_CHALLENGE }

    /** Thrown when the editor refuses: the status says why, the body what it said. */
    final class Refused extends IOException {
        private final int status;

        Refused(int status, String body) {
            super("HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /** Opens a session with the index: the server and each plugin's subjects, metadata only. */
    Opened open(String secret, JsonObject index) throws IOException;

    /** Replaces what the editor has for one plugin: its subjects whole, their enums and placeholders. */
    void uploadPlugin(String code, String secret, String plugin, JsonObject payload) throws IOException;

    /** The oldest change saved and not applied, or {@code null} when there is none. */
    @Nullable Pending pending(String code, String secret) throws IOException;

    /**
     * Tells the editor a change was applied; the plugins it touched are uploaded again after.
     *
     * @param outcome what was applied and what was refused, per subject key
     */
    void applied(String code, String secret, String changeId, JsonObject outcome) throws IOException;

    Trust trust(String code, String secret, String challenge) throws IOException;
}
