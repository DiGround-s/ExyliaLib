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

    static String opening(int subjects, int plugins) {
        return header()
                + line(Phrases.tr("{letters}Uploading {info}{0} {letters}subjects of {info}{1} {letters}plugins…", subjects, plugins));
    }

    static String tooLarge(int bytes, int max) {
        return header()
                + line(Phrases.tr("{error}The snapshot is {0} KB; the editor takes at most {1} KB.", bytes / 1024, max / 1024))
                + line(Phrases.tr("{muted}Open one plugin at a time: /exylialib editor <plugin>"));
    }

    static String opened(String url, String code, int subjects) {
        return header()
                + line(Phrases.tr("{letters}Editing {info}{0} {letters}subjects. Open the link:", subjects))
                + line("<click:open_url:'" + url + "'><hover:show_text:'" + Phrases.tr("{letters}Open the editor") + "'>{info}<u>" + url + "</u></hover></click>")
                + line(Phrases.tr("{muted}The first browser to open it owns the session. Another browser"))
                + line(Phrases.tr("{muted}sees a challenge code; trust it with /exylialib editor trust {0} <challenge>", code))
                + "\n\n" + Phrases.tr("{warning}➥ After saving in the browser:")
                + line("<click:suggest_command:'/exylialib editor apply " + code + "'>{muted}/exylialib editor apply " + code + "</click>");
    }

    static String failed(String what, String reason) {
        return header()
                + line(Phrases.tr("{error}Could not {0}: {1}", what, reason))
                + line(Phrases.tr("{muted}The editor was not reached, or refused. Try again in a moment."));
    }

    static String unknownSession(String code) {
        return header()
                + line(Phrases.tr("{error}No session {0} is open on this server.", code))
                + line(Phrases.tr("{muted}A session lives a day and does not survive a restart. Open another with /exylialib editor."));
    }

    static String nothingPending(String code) {
        return header()
                + line(Phrases.tr("{letters}Nothing saved in the browser yet for session {info}{0}{letters}.", code))
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
        text.append("\n\n").append(Phrases.tr("{warning}➥ Apply them anyway:"))
                .append(line("<click:suggest_command:'/exylialib editor apply " + code + " confirm'>{muted}/exylialib editor apply "
                        + code + " confirm</click>"));
        return text.toString();
    }

    static String staleReason(String subject) {
        return Phrases.tr("{warning}{0} {letters}changed on the server after the browser loaded it; applying overwrites that.", subject);
    }

    static String sensitiveReason(String subject, String path) {
        return Phrases.tr("{warning}{0} {letters}changes {info}{1}{letters}, a setting marked sensitive.", subject, path);
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
                + line(Phrases.tr("{success}That browser may now edit session {0}.", code));
    }

    static String unknownChallenge(String code) {
        return header()
                + line(Phrases.tr("{error}No browser is waiting with that challenge on session {0}.", code))
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
