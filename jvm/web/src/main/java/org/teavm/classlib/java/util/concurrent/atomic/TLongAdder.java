package org.teavm.classlib.java.util.concurrent.atomic;

/** Single-threaded LongAdder. */
public class TLongAdder extends Number {
    private long value;

    public void add(final long x) {
        value += x;
    }

    public void increment() {
        value++;
    }

    public void decrement() {
        value--;
    }
    public long sum() {
        return value;
    }

    public void reset() {
        value = 0L;
    }

    public long sumThenReset() {
        final long v = value;
        value = 0L;
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
