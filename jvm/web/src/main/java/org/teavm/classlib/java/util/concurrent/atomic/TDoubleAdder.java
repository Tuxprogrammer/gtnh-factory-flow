package org.teavm.classlib.java.util.concurrent.atomic;

/** Single-threaded DoubleAdder. */
public class TDoubleAdder extends Number {
    private double value;

    public void add(final double x) {
        value += x;
    }

    public double sum() {
        return value;
    }

    public void reset() {
        value = 0.0;
    }

    public double sumThenReset() {
        final double v = value;
        value = 0.0;
        return v;
    }

    @Override
    public int intValue() {
        return (int) value;
    }

    @Override
    public long longValue() {
        return (long) value;
    }

    @Override
    public float floatValue() {
        return (float) value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
