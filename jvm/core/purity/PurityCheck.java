import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * The core's purity gate. Fails when a core module's main code uses something rules.txt names (the reason is printed
 * with each finding) and allow.txt does not excuse. Detection: a dependency list for classpath rules, the compiled
 * classes for class, member and concat rules (every instruction, signature and constant-pool reference, so a method
 * reference or a generic type counts too), the arithmetic and conversion instructions for op rules (what the source
 * says does not matter, only what javac made of it: a float {@code +=} and a hidden widening both show), and the source
 * text for source rules (what leaves no trace in bytecode, such as Lombok). Run by Gradle on JDK 25 (the class-file API) as a single-file program:
 *
 * <pre>
 *   java PurityCheck.java --root jvm --module core-base --sources ... --classes ... --rules ... --allow ... \
 *       --report out.txt [--dep group:name:version ...]
 *   java PurityCheck.java --self-test planted-dir --rules rules.txt
 * </pre>
 *
 * <p>
 * What it cannot catch, so a reviewer must (each was measured on the JVM, TeaVM's JavaScript target and its WebAssembly
 * GC target by the probe in the kernel stream's state file; the rules.txt reasons give the numbers):
 * <ul>
 * <li>A float that arrives already wrong. The float-narrow rule flags the instructions that make an unrounded float,
 * so a consumer (a comparison, a store, a call) is only wrong when a producer fed it: the producer is in some core
 * module's main code and is flagged there. A float from outside the core (a host call, a host-built record) is not
 * seen, nor is one made by a library jar.
 * <li>A double converted to int or long (D2I, D2L, Math.round) whose value can leave the range of the type, or is NaN:
 * the JVM and the WebAssembly target saturate, the JavaScript target wraps (and Math.round(NaN) throws there). About a
 * hundred sites convert values that stay in range by construction, so the gate does not flag the instruction; clamp
 * first wherever a value can be unbounded.
 * <li>The sign of a zero from unary minus: the WebAssembly target compiles {@code -x} as {@code 0.0 - x}, so -(+0.0) is
 * +0.0 there and -0.0 on the JVM. It only shows through division, atan2, copySign, Double.compare, a boxed Double used
 * as a key, or text; about forty-five negations exist, nearly all on values that are never zero or are only compared
 * with &lt;. Where a zero can reach one of those, write {@code x * -1.0}.
 * <li>The last place of Math.pow, exp, log, sin, tan, atan, atan2, asin, acos, sqrt, floor, ceil, abs, signum, max and
 * min: they agreed with the JVM on every case probed (27 inputs each), but a probe is not a proof. StrictMath.pow and
 * StrictMath.exp each differed by one place on one input, Math.cos by one place on three inputs where StrictMath.cos
 * agrees: the web targets give fdlibm's answers, as V8 does, so where a result must equal the website's, use
 * StrictMath's trigonometry as the layout code does, and treat every compare of a computed double as needing a
 * tolerance.
 * <li>Anything a rule's member pattern does not name: a new java.lang function, or a call through a method handle or
 * reflection (the reflection rule forbids that for other reasons).
 * </ul>
 */
public final class PurityCheck {

    record Rule(String id, String reason) {}

    record Match(String rule, String kind, String text, Pattern regex) {}

    record Finding(String rule, String path, int line, String what) {}

    static final class Allow {

        final String glob;
        final String rule;
        final String reason;
        final Pattern path;
        final int lineNo;
        boolean used;

        Allow(final String glob, final String rule, final String reason, final int lineNo) {
            this.glob = glob;
            this.rule = rule;
            this.reason = reason;
            this.path = globToRegex(glob);
            this.lineNo = lineNo;
        }
    }

    final Map<String, Rule> rules = new LinkedHashMap<>();
    final List<Match> matches = new ArrayList<>();

    public static void main(final String[] args) throws Exception {
        final Map<String, List<String>> opt = options(args);
        final PurityCheck check = new PurityCheck(Path.of(one(opt, "rules")));
        if (opt.containsKey("self-test")) {
            if (!check.selfTest(Path.of(one(opt, "self-test")))) System.exit(1);
            if (opt.containsKey("report")) {
                final Path out = Path.of(one(opt, "report"));
                Files.createDirectories(out.getParent());
                Files.writeString(out, "PURITY-SELFTEST: PASS\n");
            }
            return;
        }
        final Path root = Path.of(one(opt, "root"));
        final String module = one(opt, "module");
        final Path sources = Path.of(one(opt, "sources"));
        final Path classes = Path.of(one(opt, "classes"));
        final List<String> problems = new ArrayList<>();
        final List<Allow> allows = readAllow(Path.of(one(opt, "allow")), check.rules.keySet(), problems);
        final List<Finding> found = new ArrayList<>();
        final String buildFile = "core/" + module + "/build.gradle.kts";
        found.addAll(check.classpath(opt.getOrDefault("dep", List.of()), buildFile));
        found.addAll(check.scanSources(root, sources));
        found.addAll(check.scanClasses(root, sources, classes));
        final List<Finding> open = new ArrayList<>();
        int excused = 0;
        for (final Finding f : found) {
            final Allow a = allowed(allows, f);
            if (a == null) open.add(f);
            else excused++;
        }
        for (final Allow a : allows) {
            if (!a.used && a.glob.startsWith("core/" + module + "/")) {
                problems.add("allow.txt:" + a.lineNo + ": '" + a.glob + " " + a.rule
                    + "' matches nothing in this module any more; remove it");
            }
        }
        final String report = check.render(open);
        System.out.print(report);
        for (final String p : problems) System.out.println(p);
        final boolean ok = open.isEmpty() && problems.isEmpty();
        final String summary = "PURITY " + module + ": " + (ok ? "PASS" : "FAIL") + " (" + found.size()
            + " findings, " + excused + " allowed, " + open.size() + " open, " + problems.size() + " list problems)";
        System.out.println(summary);
        if (!ok) System.exit(1);
        final Path out = Path.of(one(opt, "report"));
        Files.createDirectories(out.getParent());
        Files.writeString(out, summary + "\n");
    }

    PurityCheck(final Path rulesFile) throws IOException {
        int n = 0;
        for (final String raw : Files.readAllLines(rulesFile, StandardCharsets.UTF_8)) {
            n++;
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            final String[] f = line.split("\\s+", 3);
            if (f.length < 3) throw new IllegalArgumentException("rules.txt:" + n + ": too few fields");
            if (f[0].equals("rule")) {
                final String[] r = line.split("\\s+", 3);
                rules.put(r[1], new Rule(r[1], r[2]));
                continue;
            }
            if (!rules.containsKey(f[0])) throw new IllegalArgumentException("rules.txt:" + n + ": no rule " + f[0]);
            final Pattern regex = switch (f[1]) {
                case "classpath", "member" -> anyGlob(f[2]);
                case "source" -> Pattern.compile(f[2]);
                case "class", "concat" -> null;
                case "op" -> {
                    if (!OPS.containsKey(f[2].strip())) {
                        throw new IllegalArgumentException("rules.txt:" + n + ": no operation " + f[2]);
                    }
                    yield null;
                }
                default -> throw new IllegalArgumentException("rules.txt:" + n + ": no kind " + f[1]);
            };
            matches.add(new Match(f[0], f[1], f[2], regex));
        }
    }

    // --- classpath ---

    List<Finding> classpath(final List<String> deps, final String buildFile) {
        final List<Finding> out = new ArrayList<>();
        for (final String dep : deps) {
            for (final Match m : matches) {
                if (m.kind.equals("classpath") && m.regex.matcher(dep)
                    .matches()) out.add(new Finding(m.rule, buildFile, 0, "dependency " + dep));
            }
        }
        return out;
    }

    // --- source ---

    List<Finding> scanSources(final Path root, final Path sources) throws IOException {
        final List<Finding> out = new ArrayList<>();
        if (!Files.isDirectory(sources)) return out;
        try (Stream<Path> files = Files.walk(sources)) {
            for (final Path p : files.filter(x -> x.toString()
                .endsWith(".java"))
                .sorted()
                .toList()) {
                final String[] lines = stripComments(Files.readString(p, StandardCharsets.UTF_8)).split("\n", -1);
                final String rel = rel(root, p);
                for (final Match m : matches) {
                    if (!m.kind.equals("source")) continue;
                    for (int i = 0; i < lines.length; i++) {
                        final Matcher x = m.regex.matcher(lines[i]);
                        if (x.find()) out.add(new Finding(m.rule, rel, i + 1, "source '" + x.group() + "'"));
                    }
                }
            }
        }
        return out;
    }

    /** Comments and string, text-block and char literals blanked out; line breaks kept, so line numbers hold. */
    static String stripComments(final String s) {
        final StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        final int n = s.length();
        while (i < n) {
            final char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                    if (s.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i += 2;
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < n && !s.startsWith("\"\"\"", i)) {
                    if (s.charAt(i) == '\\') i++;
                    else if (s.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i += 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && s.charAt(i) != c && s.charAt(i) != '\n') {
                    if (s.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
                out.append(c)
                    .append(c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // --- bytecode ---

    List<Finding> scanClasses(final Path root, final Path sources, final Path classes) throws IOException {
        final List<Finding> out = new ArrayList<>();
        if (!Files.isDirectory(classes)) return out;
        final String srcRel = rel(root, sources);
        try (Stream<Path> files = Files.walk(classes)) {
            for (final Path p : files.filter(x -> x.toString()
                .endsWith(".class"))
                .sorted()
                .toList()) {
                out.addAll(scanClass(srcRel, Files.readAllBytes(p)));
            }
        }
        return out;
    }

    List<Finding> scanClass(final String srcRel, final byte[] bytes) {
        final ClassModel cm = ClassFile.of()
            .parse(bytes);
        final String cls = cm.thisClass()
            .asInternalName();
        final int slash = cls.lastIndexOf('/');
        final String pkg = slash < 0 ? "" : cls.substring(0, slash + 1);
        final String outer = cls.substring(slash + 1)
            .split("\\$")[0];
        final String source = cm.findAttribute(Attributes.sourceFile())
            .map(a -> a.sourceFile()
                .stringValue())
            .orElse(outer + ".java");
        final String path = srcRel + "/" + pkg + source;
        final Map<String, Finding> found = new TreeMap<>();
        final Hit hit = (rule, line, what) -> {
            final String key = rule + "|" + what + "|" + String.format("%08d", line);
            found.putIfAbsent(key, new Finding(rule, path, line, what));
        };
        cm.superclass()
            .ifPresent(c -> classRef(c.asInternalName(), 0, hit));
        for (final ClassEntry i : cm.interfaces()) classRef(i.asInternalName(), 0, hit);
        for (final FieldModel f : cm.fields()) descriptor(f.fieldType()
            .stringValue(), 0, hit);
        for (final MethodModel m : cm.methods()) {
            descriptor(m.methodType()
                .stringValue(), 0, hit);
            m.code()
                .ifPresent(code -> {
                    int line = 0;
                    for (final CodeElement e : code) {
                        if (e instanceof LineNumber ln) line = ln.line();
                        else if (e instanceof InvokeInstruction i) member(i.owner()
                            .asInternalName(),
                            i.name()
                                .stringValue(),
                            i.type()
                                .stringValue(),
                            line,
                            hit);
                        else if (e instanceof FieldInstruction f) member(f.owner()
                            .asInternalName(),
                            f.name()
                                .stringValue(),
                            f.type()
                                .stringValue(),
                            line,
                            hit);
                        else if (e instanceof InvokeDynamicInstruction d) indy(d, line, hit);
                        else if (e instanceof OperatorInstruction || e instanceof ConvertInstruction) op(((Instruction) e)
                            .opcode()
                            .name(), line, hit);
                        else if (e instanceof TypeCheckInstruction t) classRef(t.type()
                            .asInternalName(), line, hit);
                        else if (e instanceof NewObjectInstruction o) classRef(o.className()
                            .asInternalName(), line, hit);
                        else if (e instanceof NewReferenceArrayInstruction a) classRef(a.componentType()
                            .asInternalName(), line, hit);
                        else if (e instanceof NewMultiArrayInstruction a) classRef(a.arrayType()
                            .asInternalName(), line, hit);
                        else if (e instanceof ExceptionCatch x) x.catchType()
                            .ifPresent(c -> classRef(c.asInternalName(), 0, hit));
                        else if (e instanceof ConstantInstruction c) {
                            final ConstantDesc v = c.constantValue();
                            if (v instanceof ClassDesc d) descriptor(d.descriptorString(), line, hit);
                        }
                    }
                });
        }
        // Everything else the class names: generic signatures, annotations, method handles of method references.
        for (final PoolEntry e : cm.constantPool()) {
            if (e instanceof ClassEntry c) classRef(c.asInternalName(), 0, hit);
            else if (e instanceof MemberRefEntry m) member(m.owner()
                .asInternalName(),
                m.name()
                    .stringValue(),
                m.type()
                    .stringValue(),
                0,
                hit);
            else if (e instanceof Utf8Entry u) descriptor(u.stringValue(), 0, hit);
        }
        // A use found on a line makes the same use without one (from the pool sweep) redundant.
        final Set<String> located = new TreeSet<>();
        for (final Finding f : found.values()) if (f.line > 0) located.add(f.rule + "|" + f.what);
        final List<Finding> out = new ArrayList<>();
        for (final Finding f : found.values()) {
            if (f.line == 0 && located.contains(f.rule + "|" + f.what)) continue;
            out.add(f);
        }
        return out;
    }

    interface Hit {

        void add(String rule, int line, String what);
    }

    void classRef(final String internal, final int line, final Hit hit) {
        String name = internal;
        if (name.startsWith("[")) {
            descriptor(name, line, hit);
            return;
        }
        for (final Match m : matches) {
            if (!m.kind.equals("class")) continue;
            if (classMatches(m.text, name)) hit.add(m.rule, line, "class " + name);
        }
    }

    static boolean classMatches(final String pattern, final String name) {
        if (pattern.endsWith("/")) return name.startsWith(pattern);
        return name.equals(pattern) || name.startsWith(pattern + "$");
    }

    private static final Pattern REF = Pattern.compile("L([A-Za-z0-9_$/]+)[;<]");

    /** Every class a descriptor or signature names. */
    void descriptor(final String d, final int line, final Hit hit) {
        if (d.indexOf('L') < 0) return;
        final Matcher m = REF.matcher(d);
        while (m.find()) classRef(m.group(1), line, hit);
    }

    void member(final String owner, final String name, final String type, final int line, final Hit hit) {
        classRef(owner, line, hit);
        descriptor(type, line, hit);
        final String text = owner + "." + name + type;
        for (final Match m : matches) {
            if (m.kind.equals("member") && m.regex.matcher(text)
                .matches()) hit.add(m.rule, line, "call " + owner + "." + name + type);
        }
    }

    /** The arithmetic and conversion instructions an {@code op} rule can name, and how a finding spells them. */
    static final Map<String, String> OPS = new LinkedHashMap<>();
    static {
        OPS.put("D2F", "cast of a double to float");
        OPS.put("I2F", "int converted to float");
        OPS.put("L2F", "long converted to float");
        OPS.put("FADD", "float addition");
        OPS.put("FSUB", "float subtraction");
        OPS.put("FMUL", "float multiplication");
        OPS.put("FDIV", "float division");
        OPS.put("FREM", "float remainder (%)");
        OPS.put("DREM", "double remainder (%)");
    }

    void op(final String opcode, final int line, final Hit hit) {
        for (final Match m : matches) {
            if (m.kind.equals("op") && m.text.strip()
                .equals(opcode)) hit.add(m.rule, line, OPS.get(opcode));
        }
    }

    void indy(final InvokeDynamicInstruction d, final int line, final Hit hit) {
        final MethodTypeDesc type = d.typeSymbol();
        descriptor(type.descriptorString(), line, hit);
        final String bootstrap = d.bootstrapMethod()
            .owner()
            .descriptorString();
        if (!bootstrap.equals("Ljava/lang/invoke/StringConcatFactory;")) return;
        for (final ClassDesc p : type.parameterList()) {
            for (final Match m : matches) {
                if (m.kind.equals("concat") && m.text.equals(p.descriptorString())) {
                    hit.add(m.rule, line, "string concatenation of " + p.displayName());
                }
            }
        }
    }

    // --- allow list ---

    static List<Allow> readAllow(final Path file, final Set<String> ruleIds, final List<String> problems)
        throws IOException {
        final List<Allow> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        int n = 0;
        for (final String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            n++;
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            final String[] f = line.split("\\s+", 3);
            if (f.length < 3 || f[2].strip()
                .length() < 12) {
                problems.add("allow.txt:" + n + ": needs a path, a rule and a reason in words");
                continue;
            }
            if (!f[1].equals("*") && !ruleIds.contains(f[1])) {
                problems.add("allow.txt:" + n + ": no rule '" + f[1] + "' in rules.txt");
                continue;
            }
            out.add(new Allow(f[0], f[1], f[2], n));
        }
        return out;
    }

    static Allow allowed(final List<Allow> allows, final Finding f) {
        for (final Allow a : allows) {
            if ((a.rule.equals("*") || a.rule.equals(f.rule)) && a.path.matcher(f.path)
                .matches()) {
                a.used = true;
                return a;
            }
        }
        return null;
    }

    // --- output ---

    String render(final List<Finding> open) {
        final StringBuilder out = new StringBuilder();
        final Set<String> named = new TreeSet<>();
        for (final Finding f : open) {
            out.append(f.path)
                .append(f.line > 0 ? ":" + f.line : "")
                .append(": [")
                .append(f.rule)
                .append("] ")
                .append(f.what)
                .append('\n');
            named.add(f.rule);
        }
        for (final String r : named) out.append("  ")
            .append(r)
            .append(": ")
            .append(rules.get(r).reason)
            .append('\n');
        return out.toString();
    }

    // --- self-test ---

    private static final Pattern EXPECT = Pattern.compile("//\\s*expect(-class)?:\\s*([\\w\\- ]+)");

    /**
     * Compiles the planted sources (all but those marked {@code // source-only}), scans them as a module, and checks
     * that every finding is the one its line's {@code // expect: <rules>} comment names (a class-level finding:
     * {@code // expect-class:}), that every rule in rules.txt fires at least once, and that the allow list excuses,
     * reports stale entries and rejects an entry without a reason.
     */
    boolean selfTest(final Path planted) throws Exception {
        final Path root = planted.getParent();
        final Path classes = Files.createTempDirectory("purity-planted");
        final List<String> compile = new ArrayList<>();
        final Map<String, Map<Integer, Set<String>>> expected = new TreeMap<>();
        try (Stream<Path> files = Files.walk(planted)) {
            for (final Path p : files.filter(x -> x.toString()
                .endsWith(".java"))
                .sorted()
                .toList()) {
                final String text = Files.readString(p, StandardCharsets.UTF_8);
                if (!text.startsWith("// source-only")) compile.add(p.toString());
                final String[] lines = text.split("\n", -1);
                final Map<Integer, Set<String>> byLine = new TreeMap<>();
                for (int i = 0; i < lines.length; i++) {
                    final Matcher m = EXPECT.matcher(lines[i]);
                    if (!m.find()) continue;
                    final int at = m.group(1) != null ? 0 : i + 1;
                    for (final String r : m.group(2)
                        .strip()
                        .split("\\s+")) byLine.computeIfAbsent(at, k -> new TreeSet<>())
                            .add(r);
                }
                expected.put(rel(root, p), byLine);
            }
        }
        final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        final List<String> args = new ArrayList<>(List.of("--release", "21", "-g", "-proc:none", "-nowarn", "-d",
            classes.toString()));
        args.addAll(compile);
        if (javac.run(null, null, null, args.toArray(new String[0])) != 0) {
            System.out.println("PURITY-SELFTEST: FAIL (planted sources do not compile)");
            return false;
        }
        final List<Finding> found = new ArrayList<>();
        found.addAll(scanSources(root, planted));
        found.addAll(scanClasses(root, planted, classes));
        final String buildFile = "core/planted/build.gradle.kts";
        found.addAll(classpath(List.of("net.minecraft:minecraft:1.7.10", "project :mod",
            "org.projectlombok:lombok:1.18.46", "org.apache.logging.log4j:log4j-api:2.0-beta9",
            "com.google.code.gson:gson:2.2.4", "org.ojalgo:ojalgo:57.4.0", "project :core-base"), buildFile));
        final Map<String, Map<Integer, Set<String>>> actual = new TreeMap<>();
        for (final Finding f : found) actual.computeIfAbsent(f.path, k -> new TreeMap<>())
            .computeIfAbsent(f.line, k -> new TreeSet<>())
            .add(f.rule);
        expected.put(buildFile, Map.of(0, new TreeSet<>(List.of("game", "lombok", "log4j"))));
        final List<String> wrong = new ArrayList<>();
        final Set<String> files = new TreeSet<>(expected.keySet());
        files.addAll(actual.keySet());
        for (final String file : files) {
            final Map<Integer, Set<String>> e = expected.getOrDefault(file, Map.of());
            final Map<Integer, Set<String>> a = actual.getOrDefault(file, Map.of());
            final Set<Integer> lines = new TreeSet<>(e.keySet());
            lines.addAll(a.keySet());
            for (final int line : lines) {
                final Set<String> want = e.getOrDefault(line, Set.of());
                final Set<String> got = a.getOrDefault(line, Set.of());
                if (!want.equals(got)) wrong.add(file + ":" + line + ": expected " + want + ", found " + got);
            }
        }
        final Set<String> fired = new TreeSet<>();
        for (final Finding f : found) fired.add(f.rule);
        for (final String r : rules.keySet()) if (!fired.contains(r)) wrong.add("rule '" + r + "' never fired");
        // The allow list: an entry excuses its finding, an entry that matches nothing is reported, and an entry with
        // no reason is refused.
        final Path allowFile = classes.resolve("allow.txt");
        final Finding sample = found.get(0);
        Files.writeString(allowFile, sample.path + " " + sample.rule + " a planted exception, argued here\n"
            + "core/planted/src/main/java/Nowhere.java uuid this entry matches nothing at all\n"
            + "core/planted/src/main/java/Short.java uuid short\n");
        final List<String> problems = new ArrayList<>();
        final List<Allow> allows = readAllow(allowFile, rules.keySet(), problems);
        if (problems.size() != 1) wrong.add("allow: an entry without a reason was not refused: " + problems);
        if (allowed(allows, sample) == null) wrong.add("allow: the entry did not excuse " + sample);
        if (allows.size() != 2 || allows.get(1).used) wrong.add("allow: a stale entry was not left unused");
        for (final String w : wrong) System.out.println("  " + w);
        final boolean ok = wrong.isEmpty();
        System.out.println("PURITY-SELFTEST: " + (ok ? "PASS" : "FAIL") + " (" + rules.size() + " rules, "
            + found.size() + " planted findings, " + fired.size() + " rules fired)");
        return ok;
    }

    // --- helpers ---

    /** A rule's glob: {@code *} matches anything, slashes and all. */
    static Pattern anyGlob(final String glob) {
        final StringBuilder r = new StringBuilder();
        for (final String part : glob.split("\\*", -1)) {
            if (r.length() > 0 || glob.startsWith("*")) r.append(".*");
            r.append(Pattern.quote(part));
        }
        return Pattern.compile(r.toString());
    }

    /** A path glob: {@code *} matches within a name, {@code **} across directories. */
    static Pattern globToRegex(final String glob) {
        final StringBuilder r = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            final char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                r.append(".*");
                i++;
            } else if (c == '*') {
                r.append("[^/]*");
            } else {
                r.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(r.toString());
    }

    static String rel(final Path root, final Path p) {
        return root.toAbsolutePath()
            .normalize()
            .relativize(p.toAbsolutePath()
                .normalize())
            .toString()
            .replace('\\', '/');
    }

    static Map<String, List<String>> options(final String[] args) {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("expected an option at " + args[i]);
            out.computeIfAbsent(args[i].substring(2), k -> new ArrayList<>())
                .add(args[i + 1]);
        }
        return out;
    }

    static String one(final Map<String, List<String>> opt, final String name) {
        final List<String> v = opt.get(name);
        if (v == null || v.size() != 1) throw new IllegalArgumentException("--" + name + " is needed once");
        return v.get(0);
    }
}
