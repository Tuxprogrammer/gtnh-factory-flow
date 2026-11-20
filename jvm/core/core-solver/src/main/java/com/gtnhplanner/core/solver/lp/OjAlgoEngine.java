package com.gtnhplanner.core.solver.lp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;
import org.ojalgo.optimisation.integer.IntegerStrategy;
import org.ojalgo.optimisation.linear.LinearSolver;
import org.ojalgo.type.context.NumberContext;

import com.gtnhplanner.core.base.JavaHashMap;
import com.gtnhplanner.core.base.NumberText;

/**
 * The solver's LP engine: ojAlgo's {@link ExpressionsBasedModel}, one per program, every call passed straight through
 * (so a program built here is the model the kernel built on ojAlgo directly, value for value).
 */
public final class OjAlgoEngine implements LpEngine {

    public static final OjAlgoEngine INSTANCE = new OjAlgoEngine();

    /**
     * The solver's own ojAlgo environment, with no presolvers. ojAlgo 57.3.1's presolve restarts its pass over the rows
     * after every variable it fixes, in BigDecimal: 85% of a solve. Worse, it declared sets of gates infeasible that
     * balance (gtnh-flow's 230_platline, with the every-machine floors), so it decided answers. These models are small
     * and plain; the simplex needs none of it. Branch-and-bound (the OUTPUT and INPUT modes) still presolves its own
     * way.
     */
    private static final Optimisation.Environment ENVIRONMENT = environment();

    /**
     * The set-ups a model is solved under when one may get it wrong ({@link LinearProgram#setups()}), in turn: ojAlgo's
     * revised simplex with Ruiz equilibration (scaled, the fastest here), then its own pick. ojAlgo 57.3.1 is not
     * sure-footed on these models: one set-up can call a model infeasible that the other solves, or return a point that
     * does not conserve.
     */
    private static final List<LinearSolver.Configuration> SETUPS = List
        .of(new LinearSolver.Configuration().equilibration(5), new LinearSolver.Configuration());

    private OjAlgoEngine() {}

    /**
     * {@code NumberText.javaDecimal(value)}, null when not finite: exactly what ojAlgo makes of a double it is given
     * ({@code ModelEntity.toBigDecimal(double)}, whose valueOf core-ojalgo's rewritten jar sends here), handed to it
     * through its BigDecimal overloads, which take one as it is. A solve passes the same few hundred values to model
     * after model, and the web build turns each into text and back to make one, so they are kept: once seen by a
     * program, then for every program (bounded, never by identity, so no answer depends on what was kept).
     */
    private static final class Decimals {

        private static final int LIMIT = 1 << 15;
        private static final Map<Long, BigDecimal> SHARED = new JavaHashMap<>();

        private final Map<Long, BigDecimal> own = new JavaHashMap<>();

        @Nullable
        BigDecimal of(final double value) {
            if (!Double.isFinite(value)) return null;
            final Long key = Double.doubleToRawLongBits(value);
            BigDecimal decimal = own.get(key);
            if (decimal == null) {
                decimal = shared(key, value);
                own.put(key, decimal);
            }
            return decimal;
        }

        private static BigDecimal shared(final Long key, final double value) {
            synchronized (SHARED) {
                BigDecimal decimal = SHARED.get(key);
                if (decimal == null) {
                    if (SHARED.size() >= LIMIT) SHARED.clear();
                    decimal = NumberText.javaDecimal(value);
                    SHARED.put(key, decimal);
                }
                return decimal;
            }
        }
    }

    private static Optimisation.Environment environment() {
        final Optimisation.Environment environment = Optimisation.newEnvironment();
        environment.clearPresolvers();
        return environment;
    }

    @Override
    public LinearProgram newProgram(final long safetyMillis) {
        return new Program(safetyMillis);
    }

    private static final class Program implements LinearProgram {

        private final ExpressionsBasedModel m = ENVIRONMENT.newModel();
        private final List<Variable> columns = new ArrayList<>();
        private final List<Expression> rows = new ArrayList<>();
        private final Decimals decimals = new Decimals();

        Program(final long safetyMillis) {
            // The work a solve does is counted, never timed, so its answer is the same on every computer however busy;
            // the time limit is only the net.
            m.options.time_abort = safetyMillis;
            m.options.time_suffice = safetyMillis;
            // One branch-and-bound worker, so the node order is a property of the model, not of thread interleaving,
            // and the same chart answers the same on every machine.
            m.options.integer(IntegerStrategy.DEFAULT.withGapTolerance(NumberContext.of(12, 8)));
            m.options.parallelism(1);
            m.options.feasibility = NumberContext.of(12, 10);
        }

        @Override
        public int addColumn(final String name) {
            columns.add(m.addVariable(name));
            return columns.size() - 1;
        }

        @Override
        public void columnLower(final int column, final double lower) {
            columns.get(column)
                .lower(decimals.of(lower));
        }

        @Override
        public void columnUpper(final int column, final double upper) {
            columns.get(column)
                .upper(decimals.of(upper));
        }

        @Override
        public void integer(final int column) {
            columns.get(column)
                .integer();
        }

        @Override
        public void cost(final int column, final double cost) {
            columns.get(column)
                .weight(decimals.of(cost));
        }

        @Override
        public double cost(final int column) {
            final BigDecimal weight = columns.get(column)
                .getContributionWeight();
            return weight == null ? 0 : weight.doubleValue();
        }

        @Override
        public int addRow(final String name) {
            rows.add(m.addExpression(name));
            return rows.size() - 1;
        }

        @Override
        public void set(final int row, final int column, final double coefficient) {
            rows.get(row)
                .set(columns.get(column), decimals.of(coefficient));
        }

        @Override
        public void add(final int row, final int column, final double coefficient) {
            rows.get(row)
                .add(columns.get(column), decimals.of(coefficient));
        }

        @Override
        public void rowLower(final int row, final double lower) {
            rows.get(row)
                .lower(decimals.of(lower));
        }

        @Override
        public void rowUpper(final int row, final double upper) {
            rows.get(row)
                .upper(decimals.of(upper));
        }

        @Override
        public void rowLevel(final int row, final double level) {
            rows.get(row)
                .level(decimals.of(level));
        }

        @Override
        public int columns() {
            return m.countVariables();
        }

        @Override
        public int rows() {
            return m.countExpressions();
        }

        @Override
        public Result solve() {
            final long start = System.currentTimeMillis();
            final Optimisation.Result result = m.minimise();
            return result(result, System.currentTimeMillis() - start);
        }

        @Override
        public int setups() {
            return SETUPS.size();
        }

        @Override
        public Result solve(final int setup) {
            m.options.linear(SETUPS.get(setup));
            return solve();
        }

        private Result result(final Optimisation.Result result, final long millis) {
            final Optimisation.State state = result.getState();
            return new Result(state.isFeasible(), state.isOptimal(), millis >= m.options.time_abort, state.name());
        }

        @Override
        public double value(final int column) {
            final Number n = columns.get(column)
                .getValue();
            return n == null ? 0 : n.doubleValue();
        }
    }
}
