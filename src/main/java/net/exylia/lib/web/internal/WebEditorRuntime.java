package net.exylia.lib.web.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.exylia.lib.ExyliaLib;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.reload.Reloads;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.web.EntryStore;
import net.exylia.lib.web.Subject;
import net.exylia.lib.web.YamlSubject;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The registrations, the open sessions, and the three things an admin can do: open, apply,
 * trust. One per library load.
 *
 * <p>A session is the public code in the link, the secret that goes with it, and which
 * subjects it covers. It lives in memory for a day: a restart forgets it, and that is the
 * cheapest way to make sure a secret never outlives the process that made it.
 *
 * <p>Threads: registrations and lookups are safe from anywhere. Talking to the editor happens
 * on the async scheduler; writing a file and running a subject's listener happen on the
 * global thread, where a plugin's own reload command would run them.
 *
 * @since 1.304.0
 */
public final class WebEditorRuntime {

    static final Duration SESSION_LIFETIME = Duration.ofHours(24);
    static final int MAX_SESSIONS = 32;
    /** Reasons shown before the preview says "…and N more". */
    static final int MAX_REASONS = 8;
    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile WebEditorRuntime instance;

    /**
     * One open session, as the server remembers it.
     *
     * @param plugins     the plugins uploaded, in order
     * @param subjectKeys every subject the session covers
     * @param discovered  the subjects discovery found for it, by key; registered ones are looked
     *                    up in the registry, so a re-registration is what applies
     */
    record Session(String code, String url, String secret, @Nullable String scope, List<String> plugins,
                   Set<String> subjectKeys, Map<String, SubjectImpl> discovered) {
    }

    /** A plugin's opt-outs from discovery. */
    private record Options(List<String> excluded, boolean discover) {
        static final Options DEFAULT = new Options(List.of(), true);
    }

    private final Map<String, Map<String, SubjectImpl>> registry = new ConcurrentHashMap<>();
    private final Map<String, Options> options = new ConcurrentHashMap<>();
    private final Cache<String, Session> sessions = Caffeine.newBuilder()
            .expireAfterWrite(SESSION_LIFETIME)
            .maximumSize(MAX_SESSIONS)
            .build();
    private final Supplier<LibrarySettings> settings;
    private final Supplier<String> serverId;
    private final String version;
    private final Function<String, EditorClient> clients;
    private final Consumer<Runnable> async;
    private final Consumer<Runnable> sync;
    private final Debug debug;
    private volatile String clientBase;
    private volatile EditorClient client;

    WebEditorRuntime(Supplier<LibrarySettings> settings, Supplier<String> serverId, String version,
                     Function<String, EditorClient> clients, Consumer<Runnable> async, Consumer<Runnable> sync,
                     Debug debug) {
        this.settings = settings;
        this.serverId = serverId;
        this.version = version;
        this.clients = clients;
        this.async = async;
        this.sync = sync;
        this.debug = debug;
    }

    /** Starts the runtime for this load of the library. */
    public static void start(@NotNull ExyliaLib lib) {
        Path idFile = lib.getDataFolder().toPath().resolve("editor-id.txt");
        instance = new WebEditorRuntime(LibrarySettings::get, () -> serverId(idFile), lib.version(),
                base -> new HttpEditorClient(base, lib.version()),
                task -> Tasks.of(lib).runAsync(task), task -> Tasks.of(lib).run(task), Debug.of(lib));
    }

    /** Forgets every session and registration. */
    public static void stop() {
        WebEditorRuntime current = instance;
        instance = null;
        if (current != null) {
            current.sessions.invalidateAll();
            current.registry.clear();
            current.options.clear();
        }
    }

    /** Test seam. */
    static void install(@Nullable WebEditorRuntime runtime) {
        instance = runtime;
    }

    public static @NotNull WebEditorRuntime get() {
        WebEditorRuntime current = instance;
        if (current == null) {
            throw new IllegalStateException("ExyliaLib is not enabled; the web editor has no runtime");
        }
        return current;
    }

    /** Forgets a plugin's subjects when it is disabled. */
    public static void release(@NotNull String pluginName) {
        WebEditorRuntime current = instance;
        if (current != null) {
            current.registry.remove(pluginName);
            current.options.remove(pluginName);
        }
    }

    // ------------------------------------------------------------------
    // Registry
    // ------------------------------------------------------------------

    public @NotNull Subject register(Plugin plugin, String id, ConfigFile<?> file, String kind) {
        SubjectImpl subject = new SubjectImpl(plugin, id, file, kind);
        registry.computeIfAbsent(plugin.getName(), name -> new ConcurrentHashMap<>()).put(id, subject);
        return subject;
    }

    public @NotNull YamlSubject registerYaml(Plugin plugin, String id, String path, String kind) {
        YamlSubjectImpl subject = YamlSubjectImpl.of(plugin, id, path, kind);
        registry.computeIfAbsent(plugin.getName(), name -> new ConcurrentHashMap<>()).put(id, subject);
        return subject;
    }

    public <R extends Record> @NotNull Subject registerEntries(Plugin plugin, String id, Class<R> type,
                                                                EntryStore<R> store, String kind) {
        SubjectImpl subject = new SubjectImpl(plugin, id, new EntrySource<>(id, type, store), kind);
        registry.computeIfAbsent(plugin.getName(), name -> new ConcurrentHashMap<>()).put(id, subject);
        return subject;
    }

    public void exclude(String pluginName, String... globs) {
        options.merge(pluginName, new Options(List.of(globs), true), (known, added) -> {
            List<String> all = new ArrayList<>(known.excluded());
            added.excluded().stream().filter(glob -> !all.contains(glob)).forEach(all::add);
            return new Options(List.copyOf(all), known.discover());
        });
    }

    public void discover(String pluginName, boolean discover) {
        options.merge(pluginName, new Options(List.of(), discover),
                (known, added) -> new Options(known.excluded(), discover));
    }

    private Options options(String pluginName) {
        return options.getOrDefault(pluginName, Options.DEFAULT);
    }

    /** Whether discovery may offer this plugin's files. */
    private boolean discoverable(Plugin plugin) {
        return plugin.isEnabled() && Snapshot.EXYLIA.matcher(plugin.getName()).matches()
                && options(plugin.getName()).discover();
    }

    public void unregister(String pluginName, String id) {
        Map<String, SubjectImpl> subjects = registry.get(pluginName);
        if (subjects != null) {
            subjects.remove(id);
        }
    }

    public boolean available(@Nullable String scope) {
        if (!settings.get().webEditor().enabled()) {
            return false;
        }
        if (scope == null) {
            return registry.values().stream().anyMatch(subjects -> !subjects.isEmpty())
                    || Snapshot.exyliaPlugins().stream().anyMatch(this::discoverable);
        }
        String name = resolveScope(scope);
        Map<String, SubjectImpl> subjects = registry.get(name);
        return subjects != null && !subjects.isEmpty()
                || Snapshot.exyliaPlugins().stream().anyMatch(plugin -> plugin.getName().equals(name) && discoverable(plugin));
    }

    /** The plugins with at least one subject or files to discover, for suggestions. */
    public @NotNull Set<String> editablePlugins() {
        Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        registry.forEach((name, subjects) -> {
            if (!subjects.isEmpty()) {
                names.add(name);
            }
        });
        Snapshot.exyliaPlugins().stream().filter(this::discoverable).forEach(plugin -> names.add(plugin.getName()));
        return names;
    }

    private @Nullable SubjectImpl find(String key) {
        int slash = key.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        Map<String, SubjectImpl> subjects = registry.get(key.substring(0, slash));
        return subjects == null ? null : subjects.get(key.substring(slash + 1));
    }

    /** The registered plugin name a typed scope means, case ignored; the scope itself when none matches. */
    private String resolveScope(String scope) {
        for (String name : registry.keySet()) {
            if (name.equalsIgnoreCase(scope)) {
                return name;
            }
        }
        for (Plugin plugin : Snapshot.exyliaPlugins()) {
            if (plugin.getName().equalsIgnoreCase(scope)) {
                return plugin.getName();
            }
        }
        return scope;
    }

    List<SubjectImpl> visible(CommandSender sender, @Nullable String scope) {
        List<SubjectImpl> out = new ArrayList<>();
        String wanted = scope == null ? null : resolveScope(scope);
        registry.forEach((plugin, subjects) -> {
            if (wanted != null && !plugin.equals(wanted)) {
                return;
            }
            for (SubjectImpl subject : subjects.values()) {
                if (subject.visibleTo(sender)) {
                    out.add(subject);
                }
            }
        });
        out.sort(Comparator.comparing((SubjectImpl subject) -> subject.plugin().getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SubjectImpl::id, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ------------------------------------------------------------------
    // Open
    // ------------------------------------------------------------------

    /**
     * Opens a session. Permissions and the plugin list are read here, on the caller's thread;
     * discovery, the index and the uploads run on the async scheduler, and each plugin's
     * entries and placeholders hop to the global thread on the way.
     */
    public void open(@NotNull CommandSender sender, @Nullable String scope) {
        LibrarySettings current = settings.get();
        if (!current.webEditor().enabled()) {
            tell(sender, EditorMessages.disabled());
            return;
        }
        String wanted = scope == null ? null : resolveScope(scope);
        Map<String, List<SubjectImpl>> explicit = new LinkedHashMap<>();
        visible(sender, scope).forEach(subject ->
                explicit.computeIfAbsent(subject.plugin().getName(), name -> new ArrayList<>()).add(subject));
        boolean mayDiscover = sender.hasPermission(Discovery.PERMISSION);
        Map<String, Plugin> listed = new LinkedHashMap<>();
        for (Plugin plugin : Snapshot.exyliaPlugins()) {
            listed.put(plugin.getName(), plugin);
        }
        explicit.values().forEach(subjects -> listed.putIfAbsent(subjects.get(0).plugin().getName(), subjects.get(0).plugin()));
        List<Snapshot.Listed> plugins = new ArrayList<>();
        List<Plugin> toDiscover = new ArrayList<>();
        Map<String, List<SubjectImpl>> registered = new LinkedHashMap<>();
        for (Plugin plugin : listed.values()) {
            plugins.add(Snapshot.listed(plugin));
            if (mayDiscover && (wanted == null || wanted.equals(plugin.getName())) && discoverable(plugin)) {
                toDiscover.add(plugin);
                Map<String, SubjectImpl> all = registry.get(plugin.getName());
                registered.put(plugin.getName(), all == null ? List.of() : List.copyOf(all.values()));
            }
        }
        if (explicit.isEmpty() && toDiscover.isEmpty()) {
            tell(sender, EditorMessages.nothingToEdit(scope));
            return;
        }
        plugins.sort(Comparator.comparing(Snapshot.Listed::name, String.CASE_INSENSITIVE_ORDER));
        String secret = secret();
        async.accept(() -> {
            Map<String, List<SubjectImpl>> subjects = new LinkedHashMap<>();
            Map<String, SubjectImpl> discovered = new LinkedHashMap<>();
            for (Snapshot.Listed plugin : plugins) {
                List<SubjectImpl> own = new ArrayList<>(explicit.getOrDefault(plugin.name(), List.of()));
                Plugin found = listed.get(plugin.name());
                if (toDiscover.contains(found)) {
                    try {
                        for (SubjectImpl subject : Discovery.discover(found, registered.get(plugin.name()),
                                options(plugin.name()).excluded())) {
                            own.add(subject);
                            discovered.put(subject.key(), subject);
                        }
                    } catch (RuntimeException failure) {
                        debug.error("Could not look through the files of " + plugin.name(), failure);
                    }
                }
                if (!own.isEmpty()) {
                    subjects.put(plugin.name(), own);
                }
            }
            if (subjects.isEmpty()) {
                tell(sender, EditorMessages.nothingToEdit(scope));
                return;
            }
            JsonObject index;
            try {
                index = Snapshot.index(serverId.get(), version, current, sender, plugins, subjects);
            } catch (RuntimeException failure) {
                debug.error("Could not describe the editor subjects", failure);
                tell(sender, EditorMessages.failed("describe the subjects", failure.toString()));
                return;
            }
            int bytes = GSON.toJson(index).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > Snapshot.MAX_BYTES) {
                tell(sender, EditorMessages.tooLarge(bytes, Snapshot.MAX_BYTES));
                return;
            }
            Set<String> keys = new LinkedHashSet<>();
            subjects.values().forEach(own -> own.forEach(subject -> keys.add(subject.key())));
            Session session;
            try {
                EditorClient.Opened opened = client(current).open(secret, index);
                session = new Session(opened.code(), opened.url(), secret, wanted, List.copyOf(subjects.keySet()),
                        keys, Map.copyOf(discovered));
                sessions.put(opened.code(), session);
            } catch (IOException | RuntimeException failure) {
                debug.debug("Web editor session not opened: " + failure);
                tell(sender, EditorMessages.failed("open a session", describe(failure)));
                return;
            }
            tell(sender, EditorMessages.opened(session.url(), session.code(), keys.size()));
            upload(sender, session, current, session.plugins());
        });
    }

    /** Uploads plugins one after another; one that fails is told and the next still goes. */
    private CompletableFuture<Void> upload(CommandSender sender, Session session, LibrarySettings current,
                                           Collection<String> plugins) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String plugin : plugins) {
            chain = chain.thenCompose(ignored -> uploadPlugin(sender, session, current, plugin));
        }
        return chain;
    }

    /**
     * Describes and uploads one plugin's subjects. Called on the async scheduler: what is read
     * from a file is read there, entries and placeholders on the global thread, then the upload
     * back off it. The future always completes normally; a failure is the admin's chat line.
     */
    private CompletableFuture<Void> uploadPlugin(CommandSender sender, Session session, LibrarySettings current,
                                                 String plugin) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        List<SubjectImpl> subjects = new ArrayList<>();
        for (String key : session.subjectKeys()) {
            SubjectImpl subject = key.startsWith(plugin + "/") ? subjectOf(session, key) : null;
            if (subject != null && subject.visibleTo(sender)) {
                subjects.add(subject);
            }
        }
        JsonObject[] described = new JsonObject[subjects.size()];
        try {
            for (int i = 0; i < described.length; i++) {
                if (!subjects.get(i).source().global()) {
                    described[i] = Snapshot.subject(subjects.get(i));
                }
            }
        } catch (RuntimeException failure) {
            notUploaded(sender, plugin, failure);
            return CompletableFuture.completedFuture(null);
        }
        sync.accept(() -> {
            JsonObject placeholders;
            try {
                for (int i = 0; i < described.length; i++) {
                    if (described[i] == null) {
                        described[i] = Snapshot.subject(subjects.get(i));
                    }
                }
                placeholders = Snapshot.placeholders(List.of(described), sender);
            } catch (RuntimeException failure) {
                notUploaded(sender, plugin, failure);
                done.complete(null);
                return;
            }
            async.accept(() -> {
                try {
                    JsonObject payload = Snapshot.payload(List.of(described), placeholders);
                    int bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > Snapshot.MAX_BYTES) {
                        tell(sender, EditorMessages.pluginTooLarge(plugin, bytes, Snapshot.MAX_BYTES));
                        return;
                    }
                    client(current).uploadPlugin(session.code(), session.secret(), plugin, payload);
                } catch (IOException | RuntimeException failure) {
                    notUploaded(sender, plugin, failure);
                } finally {
                    done.complete(null);
                }
            });
        });
        return done;
    }

    private void notUploaded(CommandSender sender, String plugin, Throwable failure) {
        debug.debug("Web editor did not upload " + plugin + ": " + failure);
        tell(sender, EditorMessages.pluginNotUploaded(plugin, describe(failure)));
    }

    /** A subject of a session: the one discovery found, or the registered one, which re-registering replaces. */
    private @Nullable SubjectImpl subjectOf(Session session, String key) {
        if (!session.subjectKeys().contains(key)) {
            return null;
        }
        SubjectImpl discovered = session.discovered().get(key);
        return discovered != null ? discovered : find(key);
    }

    // ------------------------------------------------------------------
    // Apply
    // ------------------------------------------------------------------

    public void apply(@NotNull CommandSender sender, @NotNull String code, boolean confirm) {
        Session session = sessions.getIfPresent(code);
        if (session == null) {
            tell(sender, EditorMessages.unknownSession(code));
            return;
        }
        LibrarySettings current = settings.get();
        async.accept(() -> {
            EditorClient.Pending pending;
            try {
                pending = client(current).pending(session.code(), session.secret());
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof EditorClient.Refused refused && (refused.status() == 404 || refused.status() == 410)) {
                    sessions.invalidate(code);
                    tell(sender, EditorMessages.unknownSession(code));
                    return;
                }
                debug.debug("Web editor change not fetched: " + failure);
                tell(sender, EditorMessages.failed("fetch the change", describe(failure)));
                return;
            }
            if (pending == null) {
                tell(sender, EditorMessages.nothingPending(code));
                return;
            }
            sync.accept(() -> applyPending(sender, session, current, pending, confirm));
        });
    }

    /** One subject of a change, decoded and compared, ready to write or to be shown first. */
    private record Planned(SubjectImpl subject, Supplier<CompletableFuture<Void>> write, int changes) {
    }

    private void applyPending(CommandSender sender, Session session, LibrarySettings current,
                              EditorClient.Pending pending, boolean confirm) {
        List<String> reasons = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<Planned> planned = new ArrayList<>();
        JsonObject rejected = new JsonObject();
        int totalChanges = 0;

        for (Map.Entry<String, JsonElement> entry : pending.subjects().entrySet()) {
            String key = entry.getKey();
            SubjectImpl subject = subjectOf(session, key);
            if (subject == null) {
                String reason = EditorMessages.unknownSubject(key);
                lines.add(EditorMessages.rejectedLine(key, reason));
                rejected.addProperty(key, reason);
                continue;
            }
            if (!subject.visibleTo(sender)) {
                String reason = EditorMessages.noPermission();
                lines.add(EditorMessages.rejectedLine(subject.title(), reason));
                rejected.addProperty(key, reason);
                continue;
            }
            if (!entry.getValue().isJsonObject()) {
                String reason = EditorMessages.invalid("not a block of settings");
                lines.add(EditorMessages.rejectedLine(subject.title(), reason));
                rejected.addProperty(key, reason);
                continue;
            }
            Snapshot.Described described;
            Source.Prepared prepared;
            try {
                described = Snapshot.Described.of(subject);
                prepared = subject.source().prepare(subject.plugin().getName(), entry.getValue().getAsJsonObject());
            } catch (RuntimeException failure) {
                String reason = EditorMessages.invalid(describe(failure));
                lines.add(EditorMessages.rejectedLine(subject.title(), reason));
                rejected.addProperty(key, reason);
                continue;
            }
            if (prepared.problem() != null) {
                String reason = EditorMessages.invalid(prepared.problem());
                lines.add(EditorMessages.rejectedLine(subject.title(), reason));
                rejected.addProperty(key, reason);
                continue;
            }
            JsonObject after = prepared.after();
            List<Diff.Change> changes = Diff.of(described.values(), after);
            JsonElement base = pending.baseHash().get(key);
            if (base != null && base.isJsonPrimitive() && !base.getAsString().equals(described.hash()) && !changes.isEmpty()) {
                reasons.add(EditorMessages.staleReason(subject.title()));
            }
            for (String removed : prepared.holds()) {
                reasons.add(EditorMessages.deleteReason(subject.title(), removed));
            }
            List<String> sensitive = subject.sensitive();
            for (Diff.Change change : changes) {
                if (Diff.matchesAny(change.path(), sensitive)) {
                    reasons.add(EditorMessages.sensitiveReason(subject.title(), change.path()));
                } else if (change.privileged()) {
                    reasons.add(EditorMessages.privilegedReason(subject.title(), change.path()));
                }
            }
            totalChanges += changes.size();
            planned.add(new Planned(subject, prepared.write(), changes.size()));
        }

        if (!reasons.isEmpty() && !confirm) {
            List<String> shown = reasons.size() > MAX_REASONS ? new ArrayList<>(reasons.subList(0, MAX_REASONS)) : reasons;
            if (reasons.size() > MAX_REASONS) {
                shown.add(EditorMessages.moreReasons(reasons.size() - MAX_REASONS));
            }
            tell(sender, EditorMessages.needsConfirm(session.code(), shown, totalChanges));
            return;
        }

        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (Planned item : planned) {
            writes.add(item.changes() > 0 ? start(item) : CompletableFuture.completedFuture(null));
        }
        // A store answers when its database does; the admin hears once every write has.
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).whenComplete((done, ignored) -> sync.accept(() ->
                finish(sender, session, current, pending, planned, writes, lines, rejected)));
    }

    /** Starts one subject's write; a write that throws instead of answering is a failed one. */
    private CompletableFuture<Void> start(Planned item) {
        try {
            CompletableFuture<Void> future = item.write().get();
            return future == null ? CompletableFuture.completedFuture(null) : future;
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Tells the admin how every write went, runs the listeners of what was written, and tells the editor. */
    private void finish(CommandSender sender, Session session, LibrarySettings current, EditorClient.Pending pending,
                        List<Planned> planned, List<CompletableFuture<Void>> writes, List<String> lines, JsonObject rejected) {
        JsonArray appliedKeys = new JsonArray();
        for (int i = 0; i < planned.size(); i++) {
            Planned item = planned.get(i);
            Throwable failure = failureOf(writes.get(i));
            if (failure != null) {
                Debug.of(item.subject().plugin()).error("The web editor could not write " + item.subject().title(), failure);
                String reason = EditorMessages.invalid(describe(failure));
                lines.add(EditorMessages.rejectedLine(item.subject().title(), reason));
                rejected.addProperty(item.subject().key(), reason);
                continue;
            }
            if (item.changes() > 0) {
                try {
                    item.subject().applied().run();
                } catch (Throwable thrown) {
                    Debug.of(item.subject().plugin()).error("The web editor wrote " + item.subject().title()
                            + ", but applying it threw", thrown);
                }
            }
            appliedKeys.add(item.subject().key());
            lines.add(EditorMessages.appliedLine(item.subject().title(), item.changes()));
        }
        runReloads(planned, writes);
        tell(sender, EditorMessages.applied(lines));

        JsonObject outcome = new JsonObject();
        outcome.add("applied", appliedKeys);
        outcome.add("rejected", rejected);
        Set<String> touched = new LinkedHashSet<>();
        for (String key : pending.subjects().keySet()) {
            SubjectImpl subject = subjectOf(session, key);
            if (subject != null) {
                touched.add(subject.plugin().getName());
            }
        }
        async.accept(() -> {
            try {
                client(current).applied(session.code(), session.secret(), pending.id(), outcome);
            } catch (IOException | RuntimeException failure) {
                debug.debug("Web editor change not marked applied: " + failure);
                tell(sender, EditorMessages.failed("tell the editor it was applied", describe(failure)));
                return;
            }
            upload(sender, session, current, touched);
        });
    }

    /**
     * What follows a write to a discovered subject: the plugin's declared {@code Reloads}, once
     * per plugin however many of its files changed. A plugin that declares none still has each
     * written record file re-read, so its {@code onReload} listeners run.
     */
    private void runReloads(List<Planned> planned, List<CompletableFuture<Void>> writes) {
        Map<String, List<SubjectImpl>> written = new LinkedHashMap<>();
        for (int i = 0; i < planned.size(); i++) {
            Planned item = planned.get(i);
            if (item.subject().isDiscovered() && item.changes() > 0 && failureOf(writes.get(i)) == null) {
                written.computeIfAbsent(item.subject().plugin().getName(), name -> new ArrayList<>()).add(item.subject());
            }
        }
        written.forEach((plugin, subjects) -> {
            try {
                Reloads reloads = Reloads.declared(plugin);
                if (reloads != null) {
                    reloads.run();
                    return;
                }
                for (SubjectImpl subject : subjects) {
                    ConfigFile<?> file = subject.file();
                    if (file != null) {
                        file.reload();
                    }
                }
            } catch (Throwable thrown) {
                Debug.of(subjects.get(0).plugin()).error("The web editor wrote " + plugin + "'s files, but reloading it threw", thrown);
            }
        });
    }

    /** The cause a completed future failed with, unwrapped; null when it succeeded. */
    private static @Nullable Throwable failureOf(CompletableFuture<Void> future) {
        try {
            future.join();
            return null;
        } catch (CompletionException | java.util.concurrent.CancellationException failure) {
            return failure.getCause() != null ? failure.getCause() : failure;
        }
    }

    // ------------------------------------------------------------------
    // Trust
    // ------------------------------------------------------------------

    public void trust(@NotNull CommandSender sender, @NotNull String code, @NotNull String challenge) {
        Session session = sessions.getIfPresent(code);
        if (session == null) {
            tell(sender, EditorMessages.unknownSession(code));
            return;
        }
        LibrarySettings current = settings.get();
        async.accept(() -> {
            try {
                EditorClient.Trust answer = client(current).trust(session.code(), session.secret(), challenge.trim());
                tell(sender, answer == EditorClient.Trust.TRUSTED
                        ? EditorMessages.trusted(code) : EditorMessages.unknownChallenge(code));
            } catch (IOException | RuntimeException failure) {
                debug.debug("Web editor trust not sent: " + failure);
                tell(sender, EditorMessages.failed("trust that browser", describe(failure)));
            }
        });
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** Visible for tests: the sessions open right now, by code. */
    @Nullable Session session(String code) {
        return sessions.getIfPresent(code);
    }

    private EditorClient client(LibrarySettings current) {
        String base = current.webEditor().baseUrl();
        EditorClient existing = client;
        if (existing == null || !base.equals(clientBase)) {
            existing = clients.apply(base);
            client = existing;
            clientBase = base;
        }
        return existing;
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /** A centred block is sent line by line, so each line is centred on its own, as the request blocks of every plugin are. */
    private static void tell(CommandSender sender, String text) {
        if (!text.startsWith("[center")) {
            Text.of(text).send(sender);
            return;
        }
        for (String line : text.split("\n")) {
            Text.of(line).send(sender);
        }
    }

    /** Generated once, kept in its own file so no config rewrite can lose it. */
    static synchronized String serverId(Path file) {
        try {
            String stored = Files.exists(file) ? Files.readString(file).trim() : "";
            try {
                return UUID.fromString(stored).toString();
            } catch (IllegalArgumentException unreadable) {
                String fresh = UUID.randomUUID().toString();
                Files.createDirectories(file.getParent());
                Files.writeString(file, fresh);
                return fresh;
            }
        } catch (IOException failure) {
            // A server that cannot keep a file still gets a session; trust just will not persist.
            return UUID.randomUUID().toString();
        }
    }
}
