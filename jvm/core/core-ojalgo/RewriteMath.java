import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Opcode;
import java.lang.classfile.Signature;
import java.lang.classfile.Superclass;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites a library jar (ojAlgo) so its calls of the java.lang.Math and StrictMath functions that core-base's
 * ExactMath has go to ExactMath, its BigDecimal.valueOf(double) to core-base's NumberText.javaDecimal, and its HashMap
 * and HashSet to core-base's JavaHashMap and JavaHashSet. On the JVM those Math functions are processor-specific code
 * that can differ from StrictMath in the last place (the web build sends the same calls to ExactMath), and
 * BigDecimal.valueOf reads Double.toString, which Java 8 and 17 and the web compile spell differently from JDK 19+.
 * ojAlgo lays out an LP's rows in the iteration order of a HashMap keyed by row name, and a row's terms in that of a
 * HashMap keyed by column; the web compile's HashMap iterates in another order than the JDK's (and Java 8 sizes copies
 * differently from JDK 19+), which moved the last bits of ten solver corpus answers on the web. JavaHashMap iterates as
 * JDK 19+'s HashMap does, on every target. Without this the solver's scaling, tolerances, coefficients (ojAlgo works in
 * BigDecimal) and row order would differ between the mod, a player's JDK and the browser. Direct calls, constructors,
 * method references and method handles in the constant pool, local variable types and generic signatures are all
 * rewritten; a class that names HashMap or HashSet anywhere else stops the rewrite. Nothing else in the jar changes
 * (entries keep their order and names; a class with nothing to rewrite keeps its bytes). Run by Gradle
 * (build.gradle.kts beside this file, task ojalgoExactMath) on the JDK 25 toolchain for the class-file API, as a
 * single-file program:
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

    static final ClassDesc JAVA_HASH_MAP = ClassDesc.of("com.gtnhplanner.core.base.JavaHashMap");
    static final ClassDesc JAVA_HASH_SET = ClassDesc.of("com.gtnhplanner.core.base.JavaHashSet");
    /** The classes replaced, by internal name, and what replaces them. */
    static final Map<String, ClassDesc> HASHED = Map.of("java/util/HashMap", JAVA_HASH_MAP, "java/util/HashSet",
        JAVA_HASH_SET);

    /** Where a static call goes instead, or null to keep it: ExactMath by the same name, or NumberText.javaDecimal. */
    static Target target(final String owner, final String name, final String descriptor) {
        if (OWNERS.contains(owner) && FUNCTIONS.contains(name + descriptor)) return new Target(EXACT, name);
        if (owner.equals("java/math/BigDecimal") && name.equals("valueOf") && descriptor.equals("(D)Ljava/math/BigDecimal;")) {
            return new Target(NUMBER_TEXT, "javaDecimal");
        }
        return null;
    }

    record Target(ClassDesc owner, String name) {}

    private static String internal(final ClassDesc c) {
        final String d = c.descriptorString();
        return d.substring(1, d.length() - 1);
    }

    /** The class (or array of it) with HashMap and HashSet replaced; the same instance when nothing is. count[1] counts. */
    static ClassDesc remap(final ClassDesc c, final int[] count) {
        if (c.isArray()) {
            final ClassDesc component = c.componentType();
            final ClassDesc r = remap(component, count);
            return r == component ? c : r.arrayType();
        }
        if (!c.isClassOrInterface()) return c;
        final ClassDesc r = HASHED.get(internal(c));
        if (r == null) return c;
        count[1]++;
        return r;
    }

    static MethodTypeDesc remap(final MethodTypeDesc t, final int[] count) {
        final ClassDesc ret = remap(t.returnType(), count);
        final ClassDesc[] params = t.parameterArray();
        boolean changed = ret != t.returnType();
        for (int i = 0; i < params.length; i++) {
            final ClassDesc p = remap(params[i], count);
            changed |= p != params[i];
            params[i] = p;
        }
        return changed ? MethodTypeDesc.of(ret, params) : t;
    }

    /** A generic signature with HashMap and HashSet replaced. */
    static String remapSignature(final String s, final int[] count) {
        String r = s;
        for (final Map.Entry<String, ClassDesc> e : HASHED.entrySet()) {
            final String to = "L" + internal(e.getValue());
            r = r.replace("L" + e.getKey() + "<", to + "<")
                .replace("L" + e.getKey() + ";", to + ";");
        }
        if (!r.equals(s)) count[1]++;
        return r;
    }

    static ConstantDesc handle(final ConstantDesc c, final int[] count) {
        if (c instanceof DirectMethodHandleDesc h) {
            if (h.kind() == DirectMethodHandleDesc.Kind.STATIC) {
                final Target t = target(internal(h.owner()), h.methodName(), h.lookupDescriptor());
                if (t != null) {
                    count[0]++;
                    return MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, t.owner(), t.name(),
                        h.invocationType());
                }
            }
            switch (h.kind()) {
                case GETTER, SETTER, STATIC_GETTER, STATIC_SETTER:
                    return c; // a field handle naming HashMap is caught by the check after the rewrite
                default:
                    final ClassDesc was = h.owner(), owner = remap(was, count);
                    final MethodTypeDesc lookup = MethodTypeDesc.ofDescriptor(h.lookupDescriptor());
                    final MethodTypeDesc type = remap(lookup, count);
                    if (owner == was && type == lookup) return c;
                    return h.kind() == DirectMethodHandleDesc.Kind.CONSTRUCTOR
                        ? MethodHandleDesc.ofConstructor(owner, type.parameterArray())
                        : MethodHandleDesc.ofMethod(h.kind(), owner, h.methodName(), type);
            }
        }
        if (c instanceof MethodTypeDesc t) return remap(t, count);
        if (c instanceof ClassDesc d) return remap(d, count);
        return c;
    }

    static CodeTransform code(final int[] count) {
        return (b, e) -> {
            switch (e) {
                case InvokeInstruction i -> {
                    final Target t = i.opcode() == Opcode.INVOKESTATIC
                        ? target(i.owner().asInternalName(), i.name().stringValue(), i.type().stringValue()) : null;
                    if (t != null) {
                        count[0]++;
                        b.invokestatic(t.owner(), t.name(), i.typeSymbol());
                        return;
                    }
                    final ClassDesc owner = i.owner().asSymbol(), newOwner = remap(owner, count);
                    final MethodTypeDesc type = i.typeSymbol(), newType = remap(type, count);
                    if (newOwner == owner && newType == type) b.with(e);
                    else b.invoke(i.opcode(), newOwner, i.name().stringValue(), newType, i.isInterface());
                }
                case InvokeDynamicInstruction d -> {
                    final int seen = count[0] + count[1];
                    final List<ConstantDesc> args = d.bootstrapArgs().stream().map(a -> handle(a, count)).toList();
                    final MethodTypeDesc type = remap(d.typeSymbol(), count);
                    if (count[0] + count[1] == seen) b.with(e);
                    else b.invokedynamic(DynamicCallSiteDesc.of(d.bootstrapMethod(), d.name().stringValue(), type,
                        args.toArray(ConstantDesc[]::new)));
                }
                case ConstantInstruction.LoadConstantInstruction l -> {
                    final ConstantDesc was = l.constantValue(), v = handle(was, count);
                    if (v == was) b.with(e);
                    else b.ldc(v);
                }
                case NewObjectInstruction n -> {
                    final ClassDesc c = n.className().asSymbol(), r = remap(c, count);
                    if (r == c) b.with(e);
                    else b.new_(r);
                }
                case TypeCheckInstruction t -> {
                    final ClassDesc c = t.type().asSymbol(), r = remap(c, count);
                    if (r == c) b.with(e);
                    else if (t.opcode() == Opcode.CHECKCAST) b.checkcast(r);
                    else b.instanceOf(r);
                }
                case NewReferenceArrayInstruction a -> {
                    final ClassDesc c = a.componentType().asSymbol(), r = remap(c, count);
                    if (r == c) b.with(e);
                    else b.anewarray(r);
                }
                case FieldInstruction f -> {
                    final ClassDesc owner = f.owner().asSymbol(), newOwner = remap(owner, count);
                    final ClassDesc type = f.typeSymbol(), newType = remap(type, count);
                    if (newOwner == owner && newType == type) b.with(e);
                    else b.fieldAccess(f.opcode(), newOwner, f.name().stringValue(), newType);
                }
                case LocalVariable v -> {
                    final ClassDesc c = v.typeSymbol(), r = remap(c, count);
                    if (r == c) b.with(e);
                    else b.localVariable(v.slot(), v.name().stringValue(), r, v.startScope(), v.endScope());
                }
                case LocalVariableType v -> {
                    final String s = v.signature().stringValue(), r = remapSignature(s, count);
                    if (r.equals(s)) b.with(e);
                    else b.localVariableType(v.slot(), v.name().stringValue(), Signature.parseFrom(r), v.startScope(),
                        v.endScope());
                }
                default -> b.with(e);
            }
        };
    }

    /** A Signature attribute with HashMap and HashSet replaced, parsed back as the kind it is. */
    static SignatureAttribute signature(final SignatureAttribute a, final char kind, final int[] count) {
        final String s = a.signature().stringValue(), r = remapSignature(s, count);
        if (r.equals(s)) return a;
        return switch (kind) {
            case 'c' -> SignatureAttribute.of(ClassSignature.parseFrom(r));
            case 'm' -> SignatureAttribute.of(MethodSignature.parseFrom(r));
            default -> SignatureAttribute.of(Signature.parseFrom(r));
        };
    }

    static byte[] rewrite(final ClassFile cf, final ClassFile fresh, final byte[] bytes, final int[] count) {
        final ClassModel model = cf.parse(bytes);
        final String name = model.thisClass().asInternalName();
        final int[] mine = {0, 0};
        final CodeTransform code = code(mine);
        final ClassTransform classes = (b, e) -> {
            switch (e) {
                case Superclass s -> {
                    final ClassDesc c = s.superclassEntry().asSymbol(), r = remap(c, mine);
                    if (r == c) b.with(e);
                    else b.withSuperclass(r);
                }
                case SignatureAttribute s -> b.with(signature(s, 'c', mine));
                case MethodModel m -> {
                    final MethodTypeDesc type = m.methodTypeSymbol();
                    if (remap(type, new int[2]) != type) {
                        throw new IllegalStateException(name + "." + m.methodName() + " takes or returns HashMap or HashSet");
                    }
                    b.transformMethod(m, (mb, me) -> {
                        if (me instanceof CodeModel c) mb.transformCode(c, code);
                        else if (me instanceof SignatureAttribute s) mb.with(signature(s, 'm', mine));
                        else mb.with(me);
                    });
                }
                case FieldModel f -> {
                    final ClassDesc type = f.fieldTypeSymbol();
                    if (remap(type, new int[2]) != type) {
                        throw new IllegalStateException(name + "." + f.fieldName() + " is a HashMap or HashSet");
                    }
                    b.transformField(f, (fb, fe) -> {
                        if (fe instanceof SignatureAttribute s) fb.with(signature(s, 'f', mine));
                        else fb.with(fe);
                    });
                }
                default -> b.with(e);
            }
        };
        byte[] out = cf.transformClass(model, classes);
        if (mine[1] > 0) {
            // Replaced classes leave their names in a shared constant pool: build this class's pool afresh.
            mine[0] = mine[1] = 0;
            out = fresh.transformClass(fresh.parse(bytes), classes);
        }
        final byte[] result = mine[0] + mine[1] == 0 ? bytes : out;
        final String text = new String(result, StandardCharsets.ISO_8859_1);
        for (final String replaced : HASHED.keySet()) {
            if (text.contains(replaced)) throw new IllegalStateException(name + " still names " + replaced);
        }
        count[0] += mine[0];
        count[1] += mine[1];
        return result;
    }

    public static void main(final String[] args) throws IOException {
        final Path in = Path.of(args[0]), out = Path.of(args[1]);
        final int[] count = {0, 0};
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
        // JavaHashMap and JavaHashSet are core-base's, not the jar's: their place in the hierarchy is given here.
        final ClassHierarchyResolver hashed = ClassHierarchyResolver.of(List.of(), Map.of(JAVA_HASH_MAP,
            ClassDesc.of("java.util.AbstractMap"), JAVA_HASH_SET, ClassDesc.of("java.util.AbstractSet")));
        final ClassFile.ClassHierarchyResolverOption hierarchy = ClassFile.ClassHierarchyResolverOption.of(
            jar.orElse(hashed)
                .orElse(ClassHierarchyResolver.defaultResolver()));
        final ClassFile cf = ClassFile.of(hierarchy);
        final ClassFile fresh = ClassFile.of(hierarchy, ClassFile.ConstantPoolSharingOption.NEW_POOL);
        try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out))) {
            for (final Map.Entry<String, byte[]> e : entries.entrySet()) {
                final ZipEntry copy = new ZipEntry(e.getKey());
                copy.setTime(times.get(e.getKey()));
                zout.putNextEntry(copy);
                zout.write(e.getKey().endsWith(".class") ? rewrite(cf, fresh, e.getValue(), count) : e.getValue());
                zout.closeEntry();
            }
        }
        System.out.println("RewriteMath: " + count[0] + " calls sent to ExactMath and NumberText, " + count[1]
            + " HashMap and HashSet references sent to JavaHashMap and JavaHashSet, in " + in.getFileName());
        if (count[0] == 0) throw new IllegalStateException("no Math call rewritten in " + in + ": wrong jar?");
        if (count[1] == 0) throw new IllegalStateException("no HashMap or HashSet replaced in " + in + ": wrong jar?");
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        in.transferTo(b);
        return b.toByteArray();
    }
}
