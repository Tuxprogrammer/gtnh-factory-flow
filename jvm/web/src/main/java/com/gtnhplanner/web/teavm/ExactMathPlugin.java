package com.gtnhplanner.web.teavm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.teavm.interop.Platforms;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ElementModifier;
import org.teavm.model.Instruction;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.BinaryInstruction;
import org.teavm.model.instructions.BinaryOperation;
import org.teavm.model.instructions.CastNumberInstruction;
import org.teavm.model.instructions.DoubleConstantInstruction;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.FloatConstantInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.NegateInstruction;
import org.teavm.model.instructions.NumericOperandType;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/**
 * Makes the compiled core do Java's arithmetic where TeaVM's own differs, for every class it compiles (the core,
 * ojAlgo, TeaVM's class library), so no call site has to know. Found by TeaVM through
 * {@code META-INF/services/org.teavm.vm.spi.TeaVMPlugin}; it is a build tool, never compiled into the output.
 *
 * <ol>
 *   <li>{@code java.lang.Math} and {@code java.lang.StrictMath}: the functions in {@link #REDIRECTED} run core-base's
 *       {@code ExactMath} (fdlibm, the bits of the JVM's {@code StrictMath}) instead of the browser's {@code Math} or
 *       TeaVM's shortcuts ({@code cbrt} as a power, {@code round} as {@code (long) (x + signum(x) / 2)}, {@code rint}
 *       as {@code round}, {@code tanh} that overflows to NaN). On the JVM the core calls {@code ExactMath} itself.</li>
 *   <li>{@code java.math.BigDecimal.valueOf(double)}: core-base's {@code NumberText.javaDecimal}, the JDK 19+ decimal
 *       (TeaVM's reads its own {@code Double.toString}, which approximates the shortest digits, so ojAlgo's
 *       coefficients would be other numbers).</li>
 *   <li>Every negation of a double or float becomes a multiplication by -1: TeaVM's WebAssembly backend writes
 *       {@code -x} as {@code 0 - x}, which gives +0 for +0 where Java gives -0.</li>
 *   <li>JavaScript target only: a double or float converted to int or long goes through {@link JavaCasts}, which
 *       saturates as Java does (NaN is 0, out of range is the nearest end). TeaVM writes {@code x | 0} (wraps) and
 *       {@code BigInt} (throws on NaN). The WebAssembly backend already uses saturating conversions.</li>
 *   <li>JavaScript target only: TeaVM prints {@code a + (b - c)} as {@code a + b - c} and {@code a * (b / c)} as
 *       {@code a * b / c} (it treats double addition and multiplication as associative), which runs in another order
 *       and gives other bits. Such an operation is rewritten into a form it brackets ({@link Transformer#keepOrder}).</li>
 * </ol>
 * The conformance run in tools/web-core proves each of these on both targets.
 */
public final class ExactMathPlugin implements TeaVMPlugin {

    static final String EXACT = "com.gtnhplanner.core.base.ExactMath";
    static final String NUMBER_TEXT = "com.gtnhplanner.core.base.NumberText";
    static final String CASTS = JavaCasts.class.getName();

    private static final ValueType D = ValueType.DOUBLE;

    /** Name and signature (parameters, then result) of each java.lang.Math / StrictMath method sent to ExactMath. */
    static final List<MethodDescriptor> REDIRECTED = List.of(
        new MethodDescriptor("sin", D, D), new MethodDescriptor("cos", D, D), new MethodDescriptor("tan", D, D),
        new MethodDescriptor("asin", D, D), new MethodDescriptor("acos", D, D), new MethodDescriptor("atan", D, D),
        new MethodDescriptor("atan2", D, D, D), new MethodDescriptor("exp", D, D), new MethodDescriptor("log", D, D),
        new MethodDescriptor("log10", D, D), new MethodDescriptor("pow", D, D, D), new MethodDescriptor("cbrt", D, D),
        new MethodDescriptor("sinh", D, D), new MethodDescriptor("cosh", D, D), new MethodDescriptor("tanh", D, D),
        new MethodDescriptor("expm1", D, D), new MethodDescriptor("log1p", D, D),
        new MethodDescriptor("hypot", D, D, D), new MethodDescriptor("rint", D, D),
        new MethodDescriptor("round", D, ValueType.LONG), new MethodDescriptor("toRadians", D, D),
        new MethodDescriptor("toDegrees", D, D), new MethodDescriptor("ulp", D, D));

    @Override
    public void install(final TeaVMHost host) {
        final boolean javaScript = Arrays.asList(host.getPlatformTags()).contains(Platforms.JAVASCRIPT);
        host.add(new Transformer(javaScript));
    }

    static final class Transformer implements ClassHolderTransformer {
        private final boolean javaScript;

        Transformer(final boolean javaScript) {
            this.javaScript = javaScript;
        }

        @Override
        public void transformClass(final ClassHolder cls, final ClassHolderTransformerContext context) {
            final String name = cls.getName();
            if (name.equals("java.lang.Math") || name.equals("java.lang.StrictMath")) {
                for (final MethodDescriptor d : REDIRECTED) {
                    final MethodHolder method = cls.getMethod(d);
                    // A TeaVM upgrade that drops one of these must fail the build here, not silently keep its own.
                    if (method == null) throw new IllegalStateException(name + " has no " + d + " to redirect");
                    redirect(method, d, EXACT, d.getName());
                }
            }
            if (name.equals("java.math.BigDecimal")) {
                // valueOf(double) reads TeaVM's Double.toString, which approximates: the JDK 19+ decimal instead.
                final MethodDescriptor d = new MethodDescriptor("valueOf", D, ValueType.object("java.math.BigDecimal"));
                final MethodHolder method = cls.getMethod(d);
                if (method == null) throw new IllegalStateException(name + " has no " + d + " to redirect");
                redirect(method, d, NUMBER_TEXT, "javaDecimal");
            }
            // The helpers' own conversions are the raw ones they guard.
            if (name.equals(CASTS)) return;
            for (final MethodHolder method : cls.getMethods()) {
                if (method.getProgram() != null) rewrite(method.getProgram());
            }
        }

        /** The method's body becomes {@code return <owner>.<target>(<same arguments>)}. */
        private static void redirect(final MethodHolder method, final MethodDescriptor d, final String owner,
            final String target) {
            final Program program = new Program();
            program.createVariable(); // the receiver slot, unused by a static method
            final Variable[] args = new Variable[d.parameterCount()];
            for (int i = 0; i < args.length; i++) args[i] = program.createVariable();
            final Variable result = program.createVariable();
            final BasicBlock block = program.createBasicBlock();
            final InvokeInstruction call = new InvokeInstruction();
            call.setType(InvocationType.SPECIAL);
            call.setMethod(new MethodReference(owner, new MethodDescriptor(target, d.getSignature())));
            call.setArguments(args);
            call.setReceiver(result);
            block.add(call);
            final ExitInstruction exit = new ExitInstruction();
            exit.setValueToReturn(result);
            block.add(exit);
            method.setProgram(program);
            method.getModifiers().remove(ElementModifier.NATIVE);
            for (final String a : List.of("org.teavm.backend.javascript.spi.GeneratedBy",
                "org.teavm.backend.javascript.spi.InjectedBy", "org.teavm.interop.Import", "org.teavm.interop.Unmanaged",
                "org.teavm.interop.DelegateTo")) {
                // remove(String) fails on a method with no annotations at all: look first.
                if (method.getAnnotations().get(a) != null) method.getAnnotations().remove(a);
            }
        }

        private void rewrite(final Program program) {
            final List<Instruction> found = new ArrayList<>();
            for (int b = 0; b < program.basicBlockCount(); b++) {
                final BasicBlock block = program.basicBlockAt(b);
                if (block == null) continue;
                for (final Instruction insn : block) {
                    if (insn instanceof NegateInstruction n && floating(n.getOperandType())) {
                        found.add(insn);
                    } else if (javaScript && insn instanceof CastNumberInstruction c && floating(c.getSourceType())
                        && (c.getTargetType() == NumericOperandType.INT
                            || c.getTargetType() == NumericOperandType.LONG)) {
                        found.add(insn);
                    }
                }
            }
            for (final Instruction insn : found) {
                if (insn instanceof NegateInstruction n) {
                    negate(program, n);
                } else {
                    cast(program, (CastNumberInstruction) insn);
                }
            }
            if (javaScript) keepOrder(program);
        }

        /**
         * JavaScript target: TeaVM prints the right operand of a double {@code +} or {@code *} without brackets when it
         * is itself a {@code +}/{@code -} (or a {@code *}, {@code /}, {@code %}), so {@code a + (b - c)} runs as
         * {@code (a + b) - c} and {@code a * (b / c)} as {@code (a * b) / c}: another order, other bits. Such an
         * operation is rewritten into a form the printer must bracket and that gives the same bits:
         * {@code a + b} as {@code a - (-1 * b)}, {@code a * b} as {@code a * (b - 0)}.
         */
        private static void keepOrder(final Program program) {
            final Map<Variable, BinaryInstruction> made = new HashMap<>();
            final List<BinaryInstruction> all = new ArrayList<>();
            for (int b = 0; b < program.basicBlockCount(); b++) {
                final BasicBlock block = program.basicBlockAt(b);
                if (block == null) continue;
                for (final Instruction insn : block) {
                    if (insn instanceof BinaryInstruction bi && floating(bi.getOperandType())) {
                        made.put(bi.getReceiver(), bi);
                        all.add(bi);
                    }
                }
            }
            for (final BinaryInstruction bi : all) {
                final BinaryOperation op = bi.getOperation();
                if (op != BinaryOperation.ADD && op != BinaryOperation.MULTIPLY) continue;
                final BinaryInstruction right = made.get(bi.getSecondOperand());
                if (right == null) continue;
                final BinaryOperation r = right.getOperation();
                final boolean sameRank = op == BinaryOperation.ADD
                    ? r == BinaryOperation.ADD || r == BinaryOperation.SUBTRACT
                    : r == BinaryOperation.MULTIPLY || r == BinaryOperation.DIVIDE || r == BinaryOperation.MODULO;
                if (!sameRank) continue;
                final NumericOperandType type = bi.getOperandType();
                if (op == BinaryOperation.ADD) {
                    final Instruction minusOne = constant(program, type, -1.0);
                    final Variable negated = program.createVariable();
                    final BinaryInstruction mul = new BinaryInstruction(BinaryOperation.MULTIPLY, type);
                    mul.setFirstOperand(out(minusOne));
                    mul.setSecondOperand(bi.getSecondOperand());
                    mul.setReceiver(negated);
                    final BinaryInstruction sub = new BinaryInstruction(BinaryOperation.SUBTRACT, type);
                    sub.setFirstOperand(bi.getFirstOperand());
                    sub.setSecondOperand(negated);
                    sub.setReceiver(bi.getReceiver());
                    for (final Instruction i : List.of(minusOne, mul, sub)) i.setLocation(bi.getLocation());
                    bi.insertPrevious(minusOne);
                    bi.insertPrevious(mul);
                    bi.replace(sub);
                } else {
                    final Instruction zero = constant(program, type, 0.0);
                    final Variable same = program.createVariable();
                    final BinaryInstruction sub = new BinaryInstruction(BinaryOperation.SUBTRACT, type);
                    sub.setFirstOperand(bi.getSecondOperand());
                    sub.setSecondOperand(out(zero));
                    sub.setReceiver(same);
                    final BinaryInstruction mul = new BinaryInstruction(BinaryOperation.MULTIPLY, type);
                    mul.setFirstOperand(bi.getFirstOperand());
                    mul.setSecondOperand(same);
                    mul.setReceiver(bi.getReceiver());
                    for (final Instruction i : List.of(zero, sub, mul)) i.setLocation(bi.getLocation());
                    bi.insertPrevious(zero);
                    bi.insertPrevious(sub);
                    bi.replace(mul);
                }
            }
        }

        private static Instruction constant(final Program program, final NumericOperandType type, final double v) {
            final Variable into = program.createVariable();
            if (type == NumericOperandType.DOUBLE) {
                final DoubleConstantInstruction c = new DoubleConstantInstruction();
                c.setConstant(v);
                c.setReceiver(into);
                return c;
            }
            final FloatConstantInstruction c = new FloatConstantInstruction();
            c.setConstant((float) v);
            c.setReceiver(into);
            return c;
        }

        private static Variable out(final Instruction constant) {
            return constant instanceof DoubleConstantInstruction d ? d.getReceiver()
                : ((FloatConstantInstruction) constant).getReceiver();
        }

        private static boolean floating(final NumericOperandType t) {
            return t == NumericOperandType.DOUBLE || t == NumericOperandType.FLOAT;
        }

        /** {@code r = -x} becomes {@code r = -1 * x}: exact, and it flips the sign of a zero on every target. */
        private static void negate(final Program program, final NegateInstruction n) {
            final Variable minusOne = program.createVariable();
            final Instruction constant;
            if (n.getOperandType() == NumericOperandType.DOUBLE) {
                final DoubleConstantInstruction c = new DoubleConstantInstruction();
                c.setConstant(-1.0);
                c.setReceiver(minusOne);
                constant = c;
            } else {
                final FloatConstantInstruction c = new FloatConstantInstruction();
                c.setConstant(-1f);
                c.setReceiver(minusOne);
                constant = c;
            }
            constant.setLocation(n.getLocation());
            final BinaryInstruction mul = new BinaryInstruction(BinaryOperation.MULTIPLY, n.getOperandType());
            mul.setFirstOperand(minusOne);
            mul.setSecondOperand(n.getOperand());
            mul.setReceiver(n.getReceiver());
            mul.setLocation(n.getLocation());
            n.insertPrevious(constant);
            n.replace(mul);
        }

        /** {@code r = (int) x} (or long, or from a float) becomes {@code r = JavaCasts.d2i(x)}. */
        private static void cast(final Program program, final CastNumberInstruction c) {
            final boolean toLong = c.getTargetType() == NumericOperandType.LONG;
            final ValueType from = c.getSourceType() == NumericOperandType.DOUBLE ? D : ValueType.FLOAT;
            final String helper = (from == D ? "d" : "f") + (toLong ? "2l" : "2i");
            final InvokeInstruction call = new InvokeInstruction();
            call.setType(InvocationType.SPECIAL);
            call.setMethod(new MethodReference(CASTS, helper, from, toLong ? ValueType.LONG : ValueType.INTEGER));
            call.setArguments(c.getValue());
            call.setReceiver(c.getReceiver());
            call.setLocation(c.getLocation());
            c.replace(call);
        }
    }
}
