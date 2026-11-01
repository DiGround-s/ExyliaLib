import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks compiled classes against an older server API.
 *
 * <p>Exylia plugins compile against the newest API they use and switch newer
 * features off at runtime, so javac never says when code reaches for
 * something the oldest supported server lacks. This reads the bytecode and
 * reports every reference into the server API (Bukkit, Paper, Adventure, gson)
 * that the older API cannot satisfy:
 *
 * <ul>
 *   <li>a class, field or method that does not exist there, or exists but is
 *       private or package-private there;</li>
 *   <li>a call that treats a type as an interface when it is a class there, or
 *       the reverse ({@code Sound} and {@code Attribute} became interfaces in
 *       1.21.3, so a call compiled against 1.21.4 dies on 1.21.1 with
 *       {@code IncompatibleClassChangeError});</li>
 *   <li>a field or method signature of the checked class itself that names a
 *       missing type (an event listener for an event the server lacks fails
 *       when it registers).</li>
 * </ul>
 *
 * <p>Classes annotated {@code net.exylia.lib.platform.RequiresServer}, and
 * their nested classes, are the gated code that only loads on a newer server,
 * and are skipped.
 *
 * <p>A class that names newer API but is known to be safe where it loads (it
 * checks for the feature itself, the way a gated class's caller would) can be
 * listed in an allow file, one binary class name per line, with {@code #}
 * comments saying why. Its nested classes are allowed with it.
 *
 * <p>Usage: {@code java CompatCheck.java [--allow <file>] <api-jars-dir> <classes-dir-or-jar>...}.
 * Prints one line per finding and exits 1 when there is any.
 */
public final class CompatCheck {

    /** Packages that belong to the server API; references elsewhere are not checked. */
    private static final String[] API_ROOTS = {
            "org/bukkit/", "io/papermc/paper/", "com/destroystokyo/paper/", "org/spigotmc/",
            "net/kyori/", "net/md_5/bungee/", "com/google/gson/"
    };

    private static final String GATE = "Lnet/exylia/lib/platform/RequiresServer;";

    private final Map<String, Info> api = new HashMap<>();
    private final Set<String> allowed = new HashSet<>();

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: java CompatCheck.java <api-jars-dir> <classes-dir-or-jar>...");
            System.exit(2);
        }
        CompatCheck check = new CompatCheck();
        int first = 0;
        if (args[0].equals("--allow")) {
            for (String line : Files.readAllLines(Path.of(args[1]))) {
                String name = line.replaceAll("#.*", "").trim();
                if (!name.isEmpty()) check.allowed.add(name.replace('.', '/'));
            }
            first = 2;
        }
        try (Stream<Path> jars = Files.list(Path.of(args[first]))) {
            for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).toList()) check.loadApi(jar);
        }
        Set<String> findings = new TreeSet<>();
        for (int i = first + 1; i < args.length; i++) check.scan(Path.of(args[i]), findings);
        findings.forEach(System.out::println);
        System.exit(findings.isEmpty() ? 0 : 1);
    }

    private void loadApi(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                if (!entry.getName().endsWith(".class") || entry.getName().contains("META-INF/")) continue;
                if (!inApi(entry.getName())) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    Info info = Info.read(in.readAllBytes());
                    api.putIfAbsent(info.name, info);
                }
            }
        }
    }

    private void scan(Path target, Set<String> findings) throws IOException {
        Map<String, Info> classes = new HashMap<>();
        if (Files.isDirectory(target)) {
            try (Stream<Path> files = Files.walk(target)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    Info info = Info.read(Files.readAllBytes(file));
                    classes.put(info.name, info);
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(target.toFile())) {
                for (ZipEntry entry : zip.stream().toList()) {
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                    try (InputStream in = zip.getInputStream(entry)) {
                        Info info = Info.read(in.readAllBytes());
                        classes.put(info.name, info);
                    }
                }
            }
        }
        for (Info info : classes.values()) {
            if (gated(info, classes) || allowed(info.name)) continue;
            String where = info.name.replace('/', '.');
            for (String type : info.classRefs) {
                if (inApi(type) && !api.containsKey(type)) findings.add(where + ": missing class " + dots(type));
            }
            for (String descriptor : info.ownDescriptors) {
                for (String type : types(descriptor)) {
                    if (inApi(type) && !api.containsKey(type)) {
                        findings.add(where + ": signature names missing class " + dots(type));
                    }
                }
            }
            for (Ref ref : info.refs) {
                if (!inApi(ref.owner)) continue;
                Info owner = api.get(ref.owner);
                if (owner == null) continue; // reported as a missing class
                if (ref.kind == Ref.Kind.FIELD) {
                    if (!hasField(owner, ref.name, ref.descriptor)) {
                        findings.add(where + ": missing field " + dots(ref.owner) + "." + ref.name);
                    } else {
                        String hidden = inaccessible(owner, ref.name + ":" + ref.descriptor);
                        if (hidden != null) findings.add(where + ": field " + dots(ref.owner) + "." + ref.name
                                + " is " + hidden + " on the old server");
                    }
                    continue;
                }
                boolean asInterface = ref.kind == Ref.Kind.INTERFACE_METHOD;
                if (asInterface != owner.isInterface) {
                    findings.add(where + ": " + dots(ref.owner) + "." + ref.name + " is called as "
                            + (asInterface ? "an interface" : "a class") + " but is "
                            + (owner.isInterface ? "an interface" : "a class") + " on the old server");
                } else if (!hasMethod(owner, ref.name, ref.descriptor)) {
                    findings.add(where + ": missing method " + dots(ref.owner) + "." + ref.name + ref.descriptor);
                } else {
                    String hidden = inaccessible(owner, ref.name + ref.descriptor);
                    if (hidden != null) findings.add(where + ": method " + dots(ref.owner) + "." + ref.name
                            + ref.descriptor + " is " + hidden + " on the old server");
                }
            }
        }
    }

    private boolean allowed(String name) {
        int dollar = name.indexOf('$');
        return allowed.contains(dollar < 0 ? name : name.substring(0, dollar));
    }

    private static boolean gated(Info info, Map<String, Info> classes) {
        String name = info.name;
        while (true) {
            Info current = classes.get(name);
            if (current != null && current.annotations.contains(GATE)) return true;
            int dollar = name.lastIndexOf('$');
            if (dollar < 0) return false;
            name = name.substring(0, dollar);
        }
    }

    private boolean hasField(Info owner, String name, String descriptor) {
        return find(owner, new HashSet<>(), info -> info.fields.contains(name + ":" + descriptor),
                type -> jdkHas(type, name, true));
    }

    private boolean hasMethod(Info owner, String name, String descriptor) {
        return find(owner, new HashSet<>(), info -> info.methods.contains(name + descriptor),
                type -> jdkHas(type, name, false));
    }

    /**
     * Why the member cannot be reached from outside its package, or null.
     *
     * <p>Only the declaring class is asked: an API member that exists but is
     * private (or package-private) on the old server is an
     * {@code IllegalAccessError} there, as {@code EnchantmentKeys.create} is on
     * 1.21.1.
     */
    private String inaccessible(Info owner, String member) {
        Info declaring = declaring(owner, member, new HashSet<>());
        if (declaring == null) return null;
        int flags = declaring.access.get(member);
        if ((flags & 0x0002) != 0) return "private";
        if ((flags & 0x0001) == 0 && (flags & 0x0004) == 0) return "package-private";
        return null;
    }

    private Info declaring(Info info, String member, Set<String> seen) {
        if (!seen.add(info.name)) return null;
        if (info.access.containsKey(member)) return info;
        List<String> parents = new ArrayList<>(info.interfaces);
        if (info.superName != null) parents.add(info.superName);
        for (String parent : parents) {
            Info next = api.get(parent);
            Info found = next == null ? null : declaring(next, member, seen);
            if (found != null) return found;
        }
        return null;
    }

    private boolean find(Info info, Set<String> seen, java.util.function.Predicate<Info> test,
                         java.util.function.Predicate<String> outside) {
        if (!seen.add(info.name)) return false;
        if (test.test(info)) return true;
        List<String> parents = new ArrayList<>(info.interfaces);
        if (info.superName != null) parents.add(info.superName);
        if (info.isInterface) parents.add("java/lang/Object");
        for (String parent : parents) {
            Info next = api.get(parent);
            if (next != null ? find(next, seen, test, outside) : outside.test(parent)) return true;
        }
        return false;
    }

    /** A JDK or third-party ancestor: accepted when it declares a member of that name. */
    private static boolean jdkHas(String type, String name, boolean field) {
        try {
            Class<?> c = Class.forName(type.replace('/', '.'), false, CompatCheck.class.getClassLoader());
            if (field) {
                for (var f : c.getFields()) if (f.getName().equals(name)) return true;
                return false;
            }
            for (Method m : c.getMethods()) if (m.getName().equals(name)) return true;
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name)) return true;
            return false;
        } catch (ClassNotFoundException | LinkageError unknown) {
            return true; // not ours to judge
        }
    }

    private static boolean inApi(String type) {
        for (String root : API_ROOTS) if (type.startsWith(root)) return true;
        return false;
    }

    private static String dots(String type) {
        return type.replace('/', '.');
    }

    /** Every object type a field or method descriptor names. */
    private static List<String> types(String descriptor) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < descriptor.length(); i++) {
            if (descriptor.charAt(i) == 'L') {
                int end = descriptor.indexOf(';', i);
                out.add(descriptor.substring(i + 1, end));
                i = end;
            }
        }
        return out;
    }

    private record Ref(Kind kind, String owner, String name, String descriptor) {
        enum Kind { FIELD, METHOD, INTERFACE_METHOD }
    }

    /** The parts of a class file this check needs. */
    private static final class Info {
        String name;
        String superName;
        boolean isInterface;
        final List<String> interfaces = new ArrayList<>();
        final Set<String> fields = new HashSet<>();
        final Set<String> methods = new HashSet<>();
        /** Access flags of each field ({@code name:descriptor}) and method ({@code name+descriptor}). */
        final Map<String, Integer> access = new HashMap<>();
        final Set<String> annotations = new HashSet<>();
        final Set<String> classRefs = new HashSet<>();
        final List<String> ownDescriptors = new ArrayList<>();
        final List<Ref> refs = new ArrayList<>();

        static Info read(byte[] bytes) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            in.readInt();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            Object[] pool = new Object[count];
            int[][] links = new int[count][];
            int[] tags = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case 1 -> pool[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> { in.readLong(); i++; }
                    case 7, 8, 16, 19, 20 -> links[i] = new int[]{in.readUnsignedShort()};
                    case 9, 10, 11, 12, 17, 18 -> links[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                    case 15 -> { in.readUnsignedByte(); links[i] = new int[]{in.readUnsignedShort()}; }
                    default -> throw new IOException("unknown constant pool tag " + tag);
                }
            }
            Info info = new Info();
            int access = in.readUnsignedShort();
            info.isInterface = (access & 0x0200) != 0;
            info.name = className(pool, links, in.readUnsignedShort());
            int superIndex = in.readUnsignedShort();
            info.superName = superIndex == 0 ? null : className(pool, links, superIndex);
            int interfaceCount = in.readUnsignedShort();
            for (int i = 0; i < interfaceCount; i++) info.interfaces.add(className(pool, links, in.readUnsignedShort()));
            for (int kind = 0; kind < 2; kind++) {
                int members = in.readUnsignedShort();
                for (int i = 0; i < members; i++) {
                    int flags = in.readUnsignedShort();
                    String name = (String) pool[in.readUnsignedShort()];
                    String descriptor = (String) pool[in.readUnsignedShort()];
                    String member = kind == 0 ? name + ":" + descriptor : name + descriptor;
                    if (kind == 0) info.fields.add(member);
                    else info.methods.add(member);
                    info.access.put(member, flags);
                    info.ownDescriptors.add(descriptor);
                    skipAttributes(in);
                }
            }
            int attributes = in.readUnsignedShort();
            for (int i = 0; i < attributes; i++) {
                String name = (String) pool[in.readUnsignedShort()];
                byte[] body = in.readNBytes(in.readInt());
                if (name.equals("RuntimeInvisibleAnnotations") || name.equals("RuntimeVisibleAnnotations")) {
                    DataInputStream a = new DataInputStream(new ByteArrayInputStream(body));
                    int n = a.readUnsignedShort();
                    for (int j = 0; j < n; j++) info.annotations.add(annotation(a, pool));
                }
            }
            for (int i = 1; i < count; i++) {
                switch (tags[i]) {
                    case 7 -> {
                        String type = (String) pool[links[i][0]];
                        if (type.startsWith("[")) {
                            info.classRefs.addAll(types(type));
                        } else {
                            info.classRefs.add(type);
                        }
                    }
                    case 9, 10, 11 -> {
                        String owner = className(pool, links, links[i][0]);
                        if (owner.startsWith("[")) continue;
                        int[] nameAndType = links[links[i][1]];
                        Ref.Kind kind = tags[i] == 9 ? Ref.Kind.FIELD
                                : tags[i] == 10 ? Ref.Kind.METHOD : Ref.Kind.INTERFACE_METHOD;
                        info.refs.add(new Ref(kind, owner, (String) pool[nameAndType[0]], (String) pool[nameAndType[1]]));
                    }
                    default -> { }
                }
            }
            return info;
        }

        private static String className(Object[] pool, int[][] links, int index) {
            return (String) pool[links[index][0]];
        }

        private static void skipAttributes(DataInputStream in) throws IOException {
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                in.readUnsignedShort();
                in.skipNBytes(in.readInt());
            }
        }

        private static String annotation(DataInputStream in, Object[] pool) throws IOException {
            String type = (String) pool[in.readUnsignedShort()];
            int pairs = in.readUnsignedShort();
            for (int i = 0; i < pairs; i++) {
                in.readUnsignedShort();
                skipValue(in, pool);
            }
            return type;
        }

        private static void skipValue(DataInputStream in, Object[] pool) throws IOException {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 'e' -> { in.readUnsignedShort(); in.readUnsignedShort(); }
                case '@' -> annotation(in, pool);
                case '[' -> {
                    int n = in.readUnsignedShort();
                    for (int i = 0; i < n; i++) skipValue(in, pool);
                }
                default -> in.readUnsignedShort();
            }
        }
    }
}
