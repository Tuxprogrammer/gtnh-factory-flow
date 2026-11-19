import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodHandleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites a library jar (ojAlgo) so its calls of the java.lang.Math and StrictMath functions that core-base's
 * ExactMath has go to ExactMath, and its BigDecimal.valueOf(double) to core-base's NumberText.javaDecimal: on the JVM
 * those functions are processor-specific code that can differ from StrictMath in the last place (the web build sends
 * the same calls to ExactMath), and BigDecimal.valueOf reads Double.toString, which Java 8 and 17 and the web compile
 * spell differently from JDK 19+. Without this the solver's scaling, tolerances and coefficients (ojAlgo works in
 * BigDecimal) would differ between the mod, a player's JDK and the browser. Direct calls,
 * method references and method handles in the constant pool are all rewritten; nothing else in the jar changes (entries
 * keep their order, names and bytes). Run by Gradle (jvm/build.gradle.kts, an artifact transform) on the JDK 25
 * toolchain for the class-file API, as a single-file program:
 *
 * <pre>
 *   java RewriteMath.java IN.jar OUT.jar
 * </pre>
 */
public final class RewriteMath {

    static final ClassDesc EXACT = ClassDesc.of("com.gtnhplanner.core.base.ExactMath");
    static final Set<String> OWNERS = Set.of("java/lang/Math", "java/lang/StrictMath");
    /** name + descriptor of each function ExactMath has, as java.lang.Math declares it. */
    static final Set<String> FUNCTIONS = Set.of("sin(D)D", "cos(D)D", "tan(D)D", "asin(D)D", "acos(D)D", "atan(D)D",
        "atan2(DD)D", "exp(D)D", "log(D)D", "log10(D)D", "pow(DD)D", "cbrt(D)D", "sinh(D)D", "cosh(D)D", "tanh(D)D",
        "expm1(D)D", "log1p(D)D", "hypot(DD)D", "rint(D)D", "round(D)J", "toRadians(D)D", "toDegrees(D)D", "ulp(D)D");

    private RewriteMath() {}

    static final ClassDesc NUMBER_TEXT = ClassDesc.of("com.gtnhplanner.core.base.NumberText");

    /** Where a static call goes instead, or null to keep it: ExactMath by the same name, or NumberText.javaDecimal. */
    static Target target(final String owner, final String name, final String descriptor) {
        if (OWNERS.contains(owner) && FUNCTIONS.contains(name + descriptor)) return new Target(EXACT, name);
        if (owner.equals("java/math/BigDecimal") && name.equals("valueOf") && descriptor.equals("(D)Ljava/math/BigDecimal;")) {
            return new Target(NUMBER_TEXT, "javaDecimal");
        }
        return null;
    }

    record Target(ClassDesc owner, String name) {}

    static ConstantDesc handle(final ConstantDesc c, final int[] count) {
        if (c instanceof DirectMethodHandleDesc h && h.kind() == DirectMethodHandleDesc.Kind.STATIC) {
            final Target t = target(h.owner().descriptorString().replaceAll("^L|;$", ""), h.methodName(),
                h.lookupDescriptor());
            if (t != null) {
                count[0]++;
                return MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, t.owner(), t.name(),
                    h.invocationType());
            }
        }
        return c;
    }

    static byte[] rewrite(final ClassFile cf, final byte[] bytes, final int[] count) {
        final ClassModel model = cf.parse(bytes);
        final int before = count[0];
        final CodeTransform code = (b, e) -> {
            final Target t = e instanceof InvokeInstruction i && i.opcode() == java.lang.classfile.Opcode.INVOKESTATIC
                ? target(i.owner().asInternalName(), i.name().stringValue(), i.type().stringValue()) : null;
            if (t != null) {
                count[0]++;
                b.invokestatic(t.owner(), t.name(), ((InvokeInstruction) e).typeSymbol());
            } else if (e instanceof InvokeDynamicInstruction d) {
                final int seen = count[0];
                final var args = d.bootstrapArgs().stream().map(a -> handle(a, count)).toList();
                if (count[0] == seen) {
                    b.with(e);
                } else {
                    b.invokedynamic(java.lang.constant.DynamicCallSiteDesc.of(d.bootstrapMethod(), d.name().stringValue(),
                        d.typeSymbol(), args.toArray(ConstantDesc[]::new)));
                }
            } else if (e instanceof ConstantInstruction.LoadConstantInstruction l) {
                final ConstantDesc v = handle(l.constantValue(), count);
                if (v == l.constantValue()) b.with(e);
                else b.ldc(v);
            } else {
                b.with((CodeElement) e);
            }
        };
        final byte[] out = cf.transformClass(model, ClassTransform.transformingMethodBodies(code));
        return count[0] == before ? bytes : out;
    }

    public static void main(final String[] args) throws IOException {
        final Path in = Path.of(args[0]), out = Path.of(args[1]);
        final int[] count = {0};
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        final Map<String, Long> times = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(in))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null;) {
                entries.put(e.getName(), readAll(zin));
                times.put(e.getName(), e.getTime());
            }
        }
        // Rewritten methods get new stack maps, which need the jar's own class hierarchy: read it from the jar.
        final ClassHierarchyResolver jar = ClassHierarchyResolver.ofResourceParsing(d -> {
            final byte[] b = entries.get(d.descriptorString().replaceAll("^L|;$", "") + ".class");
            return b == null ? null : new ByteArrayInputStream(b);
        });
        final ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
            jar.orElse(ClassHierarchyResolver.defaultResolver())));
        try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out))) {
            for (final Map.Entry<String, byte[]> e : entries.entrySet()) {
                final ZipEntry copy = new ZipEntry(e.getKey());
                copy.setTime(times.get(e.getKey()));
                zout.putNextEntry(copy);
                zout.write(e.getKey().endsWith(".class") ? rewrite(cf, e.getValue(), count) : e.getValue());
                zout.closeEntry();
            }
        }
        System.out.println("RewriteMath: " + count[0] + " calls sent to ExactMath and NumberText in " + in.getFileName());
        if (count[0] == 0) throw new IllegalStateException("no Math call rewritten in " + in + ": wrong jar?");
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        in.transferTo(b);
        return b.toByteArray();
    }
}
