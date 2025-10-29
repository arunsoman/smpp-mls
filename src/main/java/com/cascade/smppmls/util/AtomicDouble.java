package com.cascade.smppmls.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe atomic double implementation using AtomicLong
 */
public class AtomicDouble {
    private final AtomicLong bits;

    public AtomicDouble(double initialValue) {
        this.bits = new AtomicLong(Double.doubleToLongBits(initialValue));
    }

    public double get() {
        return Double.longBitsToDouble(bits.get());
    }

    public void set(double newValue) {
        bits.set(Double.doubleToLongBits(newValue));
    }

    public double getAndSet(double newValue) {
        return Double.longBitsToDouble(bits.getAndSet(Double.doubleToLongBits(newValue)));
    }

    public boolean compareAndSet(double expect, double update) {
        return bits.compareAndSet(Double.doubleToLongBits(expect), Double.doubleToLongBits(update));
    }

    public double addAndGet(double delta) {
        while (true) {
            double current = get();
            double next = current + delta;
            if (compareAndSet(current, next)) {
                return next;
            }
        }
    }

    public double getAndAdd(double delta) {
        while (true) {
            double current = get();
            double next = current + delta;
            if (compareAndSet(current, next)) {
                return current;
            }
        }
    }

    public double updateAndGet(java.util.function.DoubleUnaryOperator updateFunction) {
        while (true) {
            double current = get();
            double next = updateFunction.applyAsDouble(current);
            if (compareAndSet(current, next)) {
                return next;
            }
        }
    }

    @Override
    public String toString() {
        return Double.toString(get());
    }
}
