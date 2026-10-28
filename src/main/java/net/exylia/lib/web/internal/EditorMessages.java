package net.exylia.lib.web.internal;

import net.exylia.lib.text.Phrases;

import java.util.List;

/** What the editor says in chat. Pure: strings in, strings out, so every line is testable. */
final class EditorMessages {

    private EditorMessages() {
    }

    static String header() {
        return Phrases.tr("{primary}&lWEB EDITOR&r");
    }

    private static String line(String body) {
        return "\n{letters_black}▎ " + body;
    }

    static String disabled() {
        return header()
                + line(Phrases.tr("{error}The web editor is turned off in plugins/ExyliaLib/config.yml."))
                + line(Phrases.tr("{muted}Set web-editor.enabled to true and run /exylialib reload."));
    }

    static String nothingToEdit(String scope) {
        return header()
                + line(scope == null
                        ? Phrases.tr("{error}No installed plugin offers anything to edit, or you lack the permission for it.")
                        : Phrases.tr("{error}{0} offers nothing to edit here, or you lack the permission for it.", scope));
    }

    static String tooLarge(int bytes, int max) {
        return header()
                + line(Phrases.tr("{error}The snapshot is {0} KB; the editor takes at most {1} KB.", bytes / 1024, max / 1024))
                + line(Phrases.tr("{muted}Open one plugin at a time: /exylialib editor <plugin>"));
    }

    /**
     * The link and the two commands as buttons, never as text: an admin sharing their screen
     * would otherwise show the session code to everybody watching. Laid out as every request
     * block in the ecosystem is: a blank line, a centred title, one line of context, the button.
     */
    static String opened(String url, String code, int subjects) {
        return "[center; sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2]{letters}"
                + "\n[center]" + Phrases.tr("{primary}&lWEB EDITOR&r")
                + "\n[center]" + Phrases.tr("{letters}{highlight}{0} {letters}subjects, ready to edit in your browser.", subjects)
                + "\n[center]{letters}"
                + "\n[center]<click:open_url:'" + url + "'><hover:show_text:'" + Phrases.tr("{letters}Opens the editor in your browser") + "'>"
                + Phrases.tr("{success}&l[✔ OPEN THE EDITOR]") + "</hover></click>"
                + "\n[center]{letters}"
                + "\n[center]" + Phrases.tr("{letters_black}Saved there? ") + applyButton(code, false)
                + "  " + Phrases.tr("{letters_black}Another browser? ") + trustButton(code)
                + "\n[center]{letters}";
    }

    private static String applyButton(String code, boolean confirm) {
        String command = "/exylialib editor apply " + code + (confirm ? " " + "confirm" : "");
        return "<click:suggest_command:'" + command + "'><hover:show_text:'" + Phrases.tr("{letters}Puts the command in your chat box") + "'>"
                + (confirm ? Phrases.tr("{warning}&l[✔ APPLY ANYWAY]") : Phrases.tr("{info}&l[APPLY]")) + "</hover></click>";
    }

    private static String trustButton(String code) {
        return "<click:suggest_command:'/exylialib editor trust " + code + " '><hover:show_text:'"
                + Phrases.tr("{letters}Puts the command in your chat box; add the code the browser shows") + "'>"
                + Phrases.tr("{info}&l[TRUST]") + "</hover></click>";
    }

    static String pluginTooLarge(String plugin, int bytes, int max) {
        return header()
                + line(Phrases.tr("{error}{0} is {1} MB, over the {2} MB the editor takes for one plugin; it was left out.",
                        plugin, bytes / (1024 * 1024), max / (1024 * 1024)))
                + line(Phrases.tr("{muted}The other plugins are in the editor as usual."));
    }

    static String pluginNotUploaded(String plugin, String reason) {
        return header()
                + line(Phrases.tr("{error}{0} could not be sent to the editor: {1}", plugin, reason))
                + line(Phrases.tr("{muted}The other plugins are in the editor as usual."));
    }

    static String failed(String what, String reason) {
        return header()
                + line(Phrases.tr("{error}Could not {0}: {1}", what, reason))
                + line(Phrases.tr("{muted}The editor was not reached, or refused. Try again in a moment."));
    }

    static String unknownSession(String code) {
        return header()
                + line(Phrases.tr("{error}No session with that code is open on this server."))
                + line(Phrases.tr("{muted}A session lives a day and does not survive a restart. Open another with /exylialib editor."));
    }

    static String nothingPending(String code) {
        return header()
                + line(Phrases.tr("{letters}Nothing has been saved in the browser yet for that session."))
                + line(Phrases.tr("{muted}Save there first, then run this again."));
    }

    /**
     * The preview shown before a change that needs a second look.
     *
     * @param reasons one line per reason, already translated
     */
    static String needsConfirm(String code, List<String> reasons, int changes) {
        StringBuilder text = new StringBuilder(header())
                .append(line(Phrases.tr("{letters}{0} changes are waiting, and some need a second look:", changes)));
        for (String reason : reasons) {
            text.append(line(reason));
        }
        // Not centred: a centring tag works only at the start of a message, and this one opens with the header.
        text.append("\n{letters}").append("\n").append(applyButton(code, true));
        return text.toString();
    }

    static String staleReason(String subject) {
        return Phrases.tr("{warning}{0} {letters}changed on the server after the browser loaded it; applying overwrites that.", subject);
    }

    static String sensitiveReason(String subject, String path) {
        return Phrases.tr("{warning}{0} {letters}changes {info}{1}{letters}, a setting marked sensitive.", subject, path);
    }

    static String deleteReason(String subject, String entry) {
        return Phrases.tr("{warning}{0} {letters}deletes {info}{1}{letters}.", subject, entry);
    }

    static String privilegedReason(String subject, String path) {
        return Phrases.tr("{warning}{0} {letters}sets {info}{1} {letters}to a line that runs as the console.", subject, path);
    }

    static String moreReasons(int hidden) {
        return Phrases.tr("{muted}…and {0} more.", hidden);
    }

    static String appliedLine(String subject, int changes) {
        return changes == 0
                ? Phrases.tr("{success}✔ {letters}{0} {letters_black}» {muted}already up to date", subject)
                : Phrases.tr("{success}✔ {letters}{0} {letters_black}» {info}{1} {letters}changes written", subject, changes);
    }

    static String rejectedLine(String subject, String reason) {
        return Phrases.tr("{error}✘ {letters}{0} {letters_black}» {error}{1}", subject, reason);
    }

    static String applied(List<String> lines) {
        StringBuilder text = new StringBuilder(header());
        for (String entry : lines) {
            text.append(line(entry));
        }
        text.append(line(Phrases.tr("{muted}The browser now shows what the server has; keep editing and apply again.")));
        return text.toString();
    }

    static String trusted(String code) {
        return header()
                + line(Phrases.tr("{success}That browser may now edit the session."));
    }

    static String unknownChallenge(String code) {
        return header()
                + line(Phrases.tr("{error}No browser is waiting with that code on this session."))
                + line(Phrases.tr("{muted}Codes expire in a few minutes; reload the page and read it again."));
    }

    static String unknownSubject(String key) {
        return Phrases.tr("not registered on this server any more ({0})", key);
    }

    static String noPermission() {
        return Phrases.tr("you lack the permission for it");
    }

    static String invalid(String issue) {
        return Phrases.tr("refused by the file's own rules: {0}", issue);
    }
}
